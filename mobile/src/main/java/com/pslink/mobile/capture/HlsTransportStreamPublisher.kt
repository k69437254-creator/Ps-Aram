// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import android.system.Os

/** Pure Kotlin H.264 Annex-B to MPEG-TS live HLS segment writer. */
class HlsTransportStreamPublisher(
    private val directory: File,
    private val segmentTargetUs: Long = 2_000_000L,
    private val windowSize: Int = 6,
    private val onPlaylistReady: (() -> Unit)? = null,
    private val playlistRenamer: (File, File) -> Unit = { source, destination ->
        Os.rename(source.absolutePath, destination.absolutePath)
    }
) {
    private data class Segment(val name: String, val durationUs: Long)
    private var output: OutputStream? = null
    private var currentName: String? = null
    private var segmentStartUs = 0L
    private var lastPtsUs = 0L
    private var nextSequence = 0L
    private var playlistReady = false
    private val completed = java.util.ArrayDeque<Segment>()
    private val continuity = HashMap<Int, Int>()
    private var closed = false

    @Synchronized fun start() {
        check(!closed) { "Publisher already closed" }
        check(directory.exists() || directory.mkdirs()) { "Unable to create HLS directory" }
        // Keep the currently published playlist and its segments available
        // until the first playlist for this session is atomically published.
        directory.listFiles()?.filter { it.name == "index.m3u8.tmp" }?.forEach {
            check(it.delete() || !it.exists()) { "Unable to remove stale temporary playlist" }
        }
        completed.clear()
        val segmentPattern = Regex("pslink-segment-([0-9]{6})\\.ts")
        nextSequence = (directory.listFiles()?.mapNotNull { file ->
            segmentPattern.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        }?.maxOrNull()?.plus(1L)) ?: 0L
        check(nextSequence <= 999999L) { "HLS segment sequence exhausted" }
        playlistReady = false
    }

    /** Access unit must be a complete Annex-B or 4-byte length-prefixed H.264 access unit. */
    @Synchronized fun writeAccessUnit(accessUnit: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        check(!closed) { "Publisher is closed" }
        require(accessUnit.isNotEmpty()) { "Empty H.264 access unit" }
        require(ptsUs >= 0L) { "Negative presentation timestamp" }
        val annexB = normalizeAnnexB(accessUnit)
        if (keyFrame && !containsIdr(annexB)) {
            throw IllegalArgumentException("MediaCodec marked a key frame but access unit contains no IDR NAL")
        }
        if (output == null) {
            if (!keyFrame) return
            openSegment(ptsUs)
        } else if (keyFrame && ptsUs - segmentStartUs >= segmentTargetUs) {
            closeSegment()
            openSegment(ptsUs)
        }
        val target = output ?: return
        val pts90k = (ptsUs * 9L) / 100L
        writePes(target, annexB, pts90k)
        lastPtsUs = maxOf(lastPtsUs, ptsUs)
    }

    @Synchronized fun stop() {
        if (closed) return
        closeSegment()
        if (playlistReady) writePlaylist(endList = true)
        closed = true
    }

    private fun openSegment(ptsUs: Long) {
        val name = String.format(Locale.US, "pslink-segment-%06d.ts", nextSequence++)
        output = BufferedOutputStream(FileOutputStream(File(directory, name)), 64 * 1024)
        currentName = name
        segmentStartUs = ptsUs
        lastPtsUs = ptsUs
        continuity.clear()
        writePsi(output!!, 0x0000, patSection())
        writePsi(output!!, 0x1000, pmtSection())
    }

    private fun closeSegment() {
        val stream = output ?: return
        val name = currentName ?: return
        stream.flush()
        stream.close()
        output = null
        currentName = null
        val file = File(directory, name)
        if (file.isFile && file.length() >= 188L * 3L) {
            val duration = (lastPtsUs - segmentStartUs).coerceAtLeast(200_000L)
            completed.addLast(Segment(name, duration))
            while (completed.size > windowSize) {
                completed.removeFirst()
            }
            val firstPlaylist = !playlistReady
            playlistReady = true
            // Publish the new playlist before deleting any segment it replaces.
            writePlaylist(endList = false)
            val retainedNames = completed.mapTo(HashSet()) { it.name }
            directory.listFiles()
                ?.filter {
                    it.name.matches(Regex("pslink-segment-[0-9]{6}\\.ts")) &&
                        it.name !in retainedNames
                }
                ?.forEach { it.delete() }
            if (firstPlaylist) onPlaylistReady?.invoke()
        } else {
            file.delete()
        }
    }

    private fun writePlaylist(endList: Boolean) {
        if (completed.isEmpty()) return
        val maxDurationSeconds = completed.maxOf { it.durationUs } / 1_000_000.0
        val targetDuration = kotlin.math.ceil(maxDurationSeconds).toInt().coerceAtLeast(1)
        val sequence = nextSequence - completed.size
        val body = buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$targetDuration\n")
            append("#EXT-X-MEDIA-SEQUENCE:$sequence\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            completed.forEach { segment ->
                append(String.format(Locale.US, "#EXTINF:%.3f,\n", segment.durationUs / 1_000_000.0))
                append(segment.name).append('\n')
            }
            if (endList) append("#EXT-X-ENDLIST\n")
        }
        val temporary = File(directory, "index.m3u8.tmp")
        temporary.writeText(body, Charsets.UTF_8)
        val destination = File(directory, "index.m3u8")
        // Atomic same-filesystem replacement. Never delete the published playlist
        // as a fallback: on failure, keep the previous playlist intact.
        try {
            playlistRenamer(temporary, destination)
        } catch (error: Exception) {
            temporary.delete()
            throw IllegalStateException("Unable to atomically publish HLS playlist", error)
        }
    }

    private fun writePsi(stream: OutputStream, pid: Int, section: ByteArray) {
        val payload = ByteArray(184) { 0xFF.toByte() }
        payload[0] = 0 // pointer_field
        System.arraycopy(section, 0, payload, 1, section.size)
        writeTsPayload(stream, pid, true, payload, null)
    }


    /**
     * Writes one raw AAC-LC access unit.
     * The publisher adds an ADTS header before placing it in MPEG-TS.
     */
    @Synchronized fun writeAudioAccessUnit(
        accessUnit: ByteArray,
        ptsUs: Long,
        sampleRate: Int = 48000,
        channels: Int = 2
    ) {
        check(!closed) { "Publisher is closed" }
        require(accessUnit.isNotEmpty()) { "Empty AAC access unit" }
        require(ptsUs >= 0L) { "Negative AAC timestamp" }

        val stream = output ?: return
        val adts = addAacAdtsHeader(accessUnit, sampleRate, channels)
        val pts90k = (ptsUs * 9L) / 100L
        writeAudioPes(stream, adts, pts90k)
    }

    // PSLINK_REMAINING_REPAIRS_V3_DYNAMIC_AAC
    private fun addAacAdtsHeader(raw: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val frequencyIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350 -> 12
            else -> throw IllegalArgumentException("AAC ADTS does not support sample rate $sampleRate")
        }
        require(channels in 1..2) { "AAC ADTS supports mono or stereo only" }
        val profile = 1 // AAC-LC
        val frameLength = raw.size + 7
        require(frameLength <= 0x1FFF) { "AAC frame exceeds ADTS frame length" }

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = ((profile shl 6) or (frequencyIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 3) shl 6) or (frameLength shr 11)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header + raw
    }

    private fun writeAudioPes(
        stream: OutputStream,
        accessUnit: ByteArray,
        pts90k: Long
    ) {
        val pesAfterLength = 8L + accessUnit.size
        val pesLength = if (pesAfterLength <= 0xFFFFL) {
            pesAfterLength.toInt()
        } else {
            0
        }

        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xC0.toByte()
        header[4] = ((pesLength ushr 8) and 0xFF).toByte()
        header[5] = (pesLength and 0xFF).toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 0x05
        encodePts(pts90k, header, 9)

        val payload = ByteArray(header.size + accessUnit.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(accessUnit, 0, payload, header.size, accessUnit.size)

        writeTsPayload(stream, 0x0101, true, payload, null)
    }

    private fun writePes(stream: OutputStream, accessUnit: ByteArray, pts90k: Long) {
        val pesAfterLength = 8L + accessUnit.size
        val pesLength = if (pesAfterLength <= 0xFFFFL) pesAfterLength.toInt() else 0
        val header = ByteArray(14)
        header[0] = 0x00
        header[1] = 0x00
        header[2] = 0x01
        header[3] = 0xE0.toByte()
        header[4] = ((pesLength ushr 8) and 0xFF).toByte()
        header[5] = (pesLength and 0xFF).toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 0x05
        encodePts(pts90k, header, 9)
        val payload = ByteArray(header.size + accessUnit.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(accessUnit, 0, payload, header.size, accessUnit.size)
        writeTsPayload(stream, 0x0100, true, payload, pts90k)
    }

    private fun writeTsPayload(stream: OutputStream, pid: Int, start: Boolean, payload: ByteArray, pcr90k: Long?) {
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val packet = ByteArray(188) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((pid ushr 8) and 0x1F) or if (start && first) 0x40 else 0).toByte()
            packet[2] = (pid and 0xFF).toByte()
            val usePcr = first && pcr90k != null
            val maxPayload = if (usePcr) 176 else 184
            val count = minOf(maxPayload, payload.size - offset)
            val hasAdaptation = usePcr || count < 184
            val cc = continuity[pid] ?: 0
            continuity[pid] = (cc + 1) and 0x0F
            if (!hasAdaptation) {
                packet[3] = (0x10 or cc).toByte()
                System.arraycopy(payload, offset, packet, 4, count)
            } else {
                packet[3] = (0x30 or cc).toByte()
                val adaptationLength = 183 - count
                packet[4] = adaptationLength.toByte()
                if (adaptationLength > 0) {
                    packet[5] = if (usePcr) 0x10 else 0x00
                    if (usePcr) encodePcr(pcr90k!!, packet, 6)
                }
                val payloadOffset = 5 + adaptationLength
                System.arraycopy(payload, offset, packet, payloadOffset, count)
            }
            stream.write(packet)
            offset += count
            first = false
        }
    }

    private fun encodePts(value: Long, target: ByteArray, offset: Int) {
        val pts = value and ((1L shl 33) - 1L)
        target[offset] = (((pts ushr 29) and 0x0E) or 0x21).toByte()
        target[offset + 1] = ((pts ushr 22) and 0xFF).toByte()
        target[offset + 2] = (((pts ushr 14) and 0xFE) or 0x01).toByte()
        target[offset + 3] = ((pts ushr 7) and 0xFF).toByte()
        target[offset + 4] = (((pts shl 1) and 0xFE) or 0x01).toByte()
    }

    private fun encodePcr(value: Long, target: ByteArray, offset: Int) {
        val base = value and ((1L shl 33) - 1L)
        target[offset] = ((base ushr 25) and 0xFF).toByte()
        target[offset + 1] = ((base ushr 17) and 0xFF).toByte()
        target[offset + 2] = ((base ushr 9) and 0xFF).toByte()
        target[offset + 3] = ((base ushr 1) and 0xFF).toByte()
        target[offset + 4] = ((((base and 1L) shl 7) or 0x7E) and 0xFF).toByte()
        target[offset + 5] = 0
    }

    private fun patSection(): ByteArray = withCrc(byteArrayOf(
        0x00, 0xB0.toByte(), 0x0D,
        0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0x00, 0x01, 0xF0.toByte(), 0x00
    ))

    private fun pmtSection(): ByteArray = withCrc(byteArrayOf(
        0x02, 0xB0.toByte(), 0x17,
        0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
        0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
        0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00
    ))

    private fun withCrc(section: ByteArray): ByteArray {
        var crc = -1
        for (byte in section) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and Int.MIN_VALUE) != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return section + byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(),
            (crc ushr 8).toByte(), crc.toByte()
        )
    }

    private fun containsIdr(data: ByteArray): Boolean = nalUnits(data).any {
        it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5
    }

    private fun normalizeAnnexB(data: ByteArray): ByteArray {
        if (hasStartCode(data, 0)) return data
        val out = ByteArrayOutputStream(data.size + 64)
        var offset = 0
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (length <= 0 || length > data.size - offset - 4) {
                throw IllegalArgumentException("H.264 unit is not Annex-B or valid 4-byte length-prefixed data")
            }
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(data, offset + 4, length)
            offset += 4 + length
        }
        if (offset != data.size || out.size() == 0) throw IllegalArgumentException("Malformed H.264 access unit")
        return out.toByteArray()
    }

    private fun hasStartCode(data: ByteArray, at: Int): Boolean =
        at + 3 < data.size && data[at] == 0.toByte() && data[at + 1] == 0.toByte() &&
            (data[at + 2] == 1.toByte() ||
                (at + 4 <= data.size && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte()))

    private fun nalUnits(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var i = 0
        var nalStart = -1
        while (i < data.size) {
            val startLength = when {
                i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startLength > 0) {
                if (nalStart >= 0 && i > nalStart) result.add(data.copyOfRange(nalStart, i))
                nalStart = i + startLength
                i += startLength
            } else i++
        }
        if (nalStart >= 0 && nalStart < data.size) result.add(data.copyOfRange(nalStart, data.size))
        return result
    }
}
