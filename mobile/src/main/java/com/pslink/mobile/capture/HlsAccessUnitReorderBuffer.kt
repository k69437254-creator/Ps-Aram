// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import java.util.PriorityQueue

/**
 * Bounded shared PTS scheduler for H.264 and AAC access units. It holds a small
 * watermark window for callback jitter, starts only on the first video keyframe,
 * and rejects packets that arrive behind the committed timeline.
 */
class HlsAccessUnitReorderBuffer(
    private val reorderWindowUs: Long = 150_000L,
    private val capacity: Int = 192,
    private val maxQueuedBytes: Long = 16L * 1024L * 1024L,
    private val maxAccessUnitBytes: Int = 2 * 1024 * 1024,
    private val writeVideo: (ByteArray, Long, Boolean) -> Unit,
    private val writeAudio: (ByteArray, Long, Int, Int) -> Unit
) : AutoCloseable {
    private sealed class Payload {
        data class Video(val bytes: ByteArray, val keyFrame: Boolean) : Payload()
        data class Audio(val bytes: ByteArray, val sampleRate: Int, val channels: Int) : Payload()
    }

    private data class Entry(val ptsUs: Long, val sequence: Long, val payload: Payload) {
        val byteSize: Int
            get() = when (payload) {
                is Payload.Video -> payload.bytes.size
                is Payload.Audio -> payload.bytes.size
            }
    }
    private val queue = PriorityQueue<Entry>(
        compareBy<Entry> { it.ptsUs }
            .thenBy { if (it.payload is Payload.Video) 0 else 1 }
            .thenBy { it.sequence }
    )
    private var sequence = 0L
    private var maxSeenPtsUs = Long.MIN_VALUE
    private var lastWrittenPtsUs = Long.MIN_VALUE
    private var firstVideoPtsUs = Long.MIN_VALUE
    private var closed = false
    private var pendingBytes = 0L
    private var lateDropCount = 0L
    private var overflowFlushCount = 0L
    private var oversizedDropCount = 0L

    init {
        require(reorderWindowUs in 0L..2_000_000L)
        require(capacity in 2..4096)
        require(maxQueuedBytes in 1L..256L * 1024L * 1024L)
        require(maxAccessUnitBytes in 1..maxQueuedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    @Synchronized
    fun offerVideo(accessUnit: ByteArray, ptsUs: Long, keyFrame: Boolean): Boolean {
        check(!closed) { "HLS reorder buffer is closed" }
        require(accessUnit.isNotEmpty())
        require(ptsUs >= 0L)
        if (accessUnit.size > maxAccessUnitBytes) {
            oversizedDropCount++
            return false
        }
        if (firstVideoPtsUs == Long.MIN_VALUE && !keyFrame) return false
        val accepted = enqueue(ptsUs, Payload.Video(accessUnit.copyOf(), keyFrame))
        if (accepted && firstVideoPtsUs == Long.MIN_VALUE) firstVideoPtsUs = ptsUs
        return accepted
    }

    @Synchronized
    fun offerAudio(accessUnit: ByteArray, ptsUs: Long, sampleRate: Int, channels: Int): Boolean {
        check(!closed) { "HLS reorder buffer is closed" }
        require(accessUnit.isNotEmpty())
        require(ptsUs >= 0L)
        if (accessUnit.size > maxAccessUnitBytes) {
            oversizedDropCount++
            return false
        }
        require(sampleRate in 7350..192000)
        require(channels in 1..2)
        // Audio before the first video IDR cannot belong to a publishable segment.
        if (firstVideoPtsUs == Long.MIN_VALUE || ptsUs < firstVideoPtsUs) return false
        return enqueue(ptsUs, Payload.Audio(accessUnit.copyOf(), sampleRate, channels))
    }

    private fun enqueue(ptsUs: Long, payload: Payload): Boolean {
        check(!closed) { "HLS reorder buffer is closed" }
        val entry = Entry(ptsUs, sequence, payload)
        val bytes = entry.byteSize
        if (bytes > maxAccessUnitBytes || bytes.toLong() > maxQueuedBytes) {
            oversizedDropCount++
            return false
        }
        if (ptsUs < lastWrittenPtsUs) {
            lateDropCount++
            return false
        }
        maxSeenPtsUs = maxOf(maxSeenPtsUs, ptsUs)
        // Bound memory by both packet count and actual payload bytes. When the
        // cap is reached, commit the oldest PTS first rather than growing RAM.
        while (queue.isNotEmpty() &&
            (queue.size >= capacity || pendingBytes + bytes.toLong() > maxQueuedBytes)) {
            drainOne()
            overflowFlushCount++
        }
        if (queue.size >= capacity || pendingBytes + bytes.toLong() > maxQueuedBytes) {
            oversizedDropCount++
            return false
        }
        sequence++
        queue.add(entry)
        pendingBytes += bytes.toLong()
        drainThrough(maxSeenPtsUs - reorderWindowUs)
        return true
    }

    private fun drainThrough(watermarkUs: Long) {
        while (queue.peek()?.ptsUs?.let { it <= watermarkUs } == true) drainOne()
    }

    private fun drainOne() {
        val entry = queue.poll() ?: return
        pendingBytes = (pendingBytes - entry.byteSize.toLong()).coerceAtLeast(0L)
        if (entry.ptsUs < lastWrittenPtsUs) {
            lateDropCount++
            return
        }
        when (val value = entry.payload) {
            is Payload.Video -> writeVideo(value.bytes, entry.ptsUs, value.keyFrame)
            is Payload.Audio -> writeAudio(value.bytes, entry.ptsUs, value.sampleRate, value.channels)
        }
        lastWrittenPtsUs = maxOf(lastWrittenPtsUs, entry.ptsUs)
    }

    @Synchronized
    fun flush() {
        while (queue.isNotEmpty()) drainOne()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        flush()
        closed = true
    }

    @Synchronized fun lateDrops(): Long = lateDropCount
    @Synchronized fun overflowFlushes(): Long = overflowFlushCount
    @Synchronized fun oversizedDrops(): Long = oversizedDropCount
    @Synchronized fun pendingPackets(): Int = queue.size
    @Synchronized fun pendingBytes(): Long = pendingBytes
}
