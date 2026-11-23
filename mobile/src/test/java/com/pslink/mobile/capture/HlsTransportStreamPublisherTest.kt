package com.pslink.mobile.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HlsTransportStreamPublisherTest {
    @Test fun writesIdrAlignedMpegTsThatCanBeDecodedByFfmpegInCi() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/hls-single-idr.h264")!!.use { it.readBytes() }
        val output = File(requireNotNull(System.getProperty("pslink.hls.test.outputDir")))
        output.deleteRecursively()
        assertTrue(output.mkdirs() || output.isDirectory)

        val publisher = HlsTransportStreamPublisher(output)
        publisher.start()
        publisher.writeAccessUnit(fixture, ptsUs = 0L, keyFrame = true)
        publisher.stop()

        val playlist = File(output, "index.m3u8")
        assertTrue("HLS playlist must be generated", playlist.isFile)
        val text = playlist.readText()
        assertTrue(text.contains("#EXTM3U"))
        assertTrue(text.contains("#EXT-X-ENDLIST"))
        val segmentName = Regex("pslink-segment-[0-9]{6}\\.ts").find(text)?.value
        assertFalse("Playlist must reference a transport-stream segment", segmentName.isNullOrBlank())
        val segment = File(output, segmentName!!)
        assertTrue(segment.isFile)
        val bytes = segment.readBytes()
        assertEquals("MPEG-TS must be packet-aligned", 0, bytes.size % 188)
        for (offset in bytes.indices step 188) {
            assertEquals("MPEG-TS sync byte at packet $offset", 0x47, bytes[offset].toInt() and 0xFF)
        }
    }

    @Test fun writesAacAudioPesAndDeclaresAacInPmt() {
        val fixture = javaClass.classLoader!!
            .getResourceAsStream("fixtures/hls-single-idr.h264")!!
            .use { it.readBytes() }

        val output = File(requireNotNull(System.getProperty("pslink.hls.test.outputDir")))
        output.deleteRecursively()
        assertTrue(output.mkdirs() || output.isDirectory)

        val publisher = HlsTransportStreamPublisher(output)
        publisher.start()

        publisher.writeAccessUnit(
            fixture,
            ptsUs = 0L,
            keyFrame = true
        )

        // Synthetic AAC access-unit payload: validates TS framing and signaling,
        // not perceptual decoding or real microphone/game-audio quality.
        val aacPayload = byteArrayOf(
            0x21, 0x10, 0x56, 0xE5.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x11, 0x22, 0x33, 0x44
        )

        publisher.writeAudioAccessUnit(
            aacPayload,
            ptsUs = 20_000L
        )
        publisher.stop()

        val playlist = File(output, "index.m3u8")
        assertTrue("HLS playlist must exist", playlist.isFile)

        val segmentName = Regex("pslink-segment-[0-9]{6}\\.ts")
            .find(playlist.readText())?.value
        assertFalse("Playlist must reference a TS segment", segmentName.isNullOrBlank())

        val bytes = File(output, segmentName!!).readBytes()
        assertTrue("TS output must not be empty", bytes.isNotEmpty())
        assertEquals("TS packets must be 188 bytes", 0, bytes.size % 188)

        val packetPids = mutableSetOf<Int>()
        for (offset in bytes.indices step 188) {
            assertEquals(0x47, bytes[offset].toInt() and 0xFF)
            val pid = ((bytes[offset + 1].toInt() and 0x1F) shl 8) or
                (bytes[offset + 2].toInt() and 0xFF)
            packetPids.add(pid)
        }

        assertTrue("AAC PID 0x0101 must be present", 0x0101 in packetPids)
        assertTrue("PMT PID 0x1000 must be present", 0x1000 in packetPids)

        val raw = bytes.toList().map { it.toInt() and 0xFF }

        fun containsPattern(pattern: List<Int>): Boolean =
            raw.windowed(pattern.size).any { it == pattern }

        assertTrue(
            "PMT must declare AAC stream type 0x0F on PID 0x0101",
            containsPattern(listOf(0x0F, 0xE1, 0x01, 0xF0, 0x00))
        )

        assertTrue(
            "Audio PES start code must be present",
            containsPattern(listOf(0x00, 0x00, 0x01, 0xC0))
        )

        assertTrue(
            "ADTS AAC framing must be present",
            containsPattern(listOf(0xFF, 0xF1))
        )
    }


    @Test fun checksContinuityCountersForEveryTransportStreamPid() {
        val fixture = javaClass.classLoader!!
            .getResourceAsStream("fixtures/hls-single-idr.h264")!!
            .use { it.readBytes() }

        val output = File(requireNotNull(System.getProperty("pslink.hls.test.outputDir")))
        output.deleteRecursively()
        assertTrue(output.mkdirs() || output.isDirectory)

        val publisher = HlsTransportStreamPublisher(output)
        publisher.start()

        publisher.writeAccessUnit(fixture, 0L, true)

        val audio = byteArrayOf(
            0x21, 0x10, 0x56, 0xE5.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x11, 0x22, 0x33, 0x44
        )

        publisher.writeAudioAccessUnit(audio, 20_000L)
        publisher.writeAudioAccessUnit(audio, 41_333L)
        publisher.writeAudioAccessUnit(audio, 62_666L)
        publisher.stop()

        val playlist = File(output, "index.m3u8")
        assertTrue(playlist.isFile)

        val segmentName = Regex("pslink-segment-[0-9]{6}\\.ts")
            .find(playlist.readText())?.value
        assertFalse(segmentName.isNullOrBlank())

        val bytes = File(output, segmentName!!).readBytes()
        assertTrue(bytes.isNotEmpty())
        assertEquals(0, bytes.size % 188)

        val lastCounterByPid = mutableMapOf<Int, Int>()
        val packetCountByPid = mutableMapOf<Int, Int>()

        for (offset in bytes.indices step 188) {
            assertEquals(0x47, bytes[offset].toInt() and 0xFF)

            val b1 = bytes[offset + 1].toInt() and 0xFF
            val b2 = bytes[offset + 2].toInt() and 0xFF
            val b3 = bytes[offset + 3].toInt() and 0xFF

            val pid = ((b1 and 0x1F) shl 8) or b2
            val adaptationControl = (b3 ushr 4) and 0x03
            val counter = b3 and 0x0F

            assertTrue("Invalid adaptation control for PID $pid", adaptationControl != 0)

            // Continuity counters advance on packets carrying payload.
            val carriesPayload = adaptationControl == 1 || adaptationControl == 3
            if (carriesPayload) {
                val previous = lastCounterByPid[pid]
                if (previous != null) {
                    assertEquals(
                        "Continuity counter discontinuity on PID 0x${pid.toString(16)}",
                        (previous + 1) and 0x0F,
                        counter
                    )
                }
                lastCounterByPid[pid] = counter
                packetCountByPid[pid] = (packetCountByPid[pid] ?: 0) + 1
            }
        }

        assertTrue("PAT PID must exist", 0x0000 in packetCountByPid)
        assertTrue("PMT PID must exist", 0x1000 in packetCountByPid)
        assertTrue("H.264 PID must exist", 0x0100 in packetCountByPid)
        assertTrue("AAC PID must exist", 0x0101 in packetCountByPid)
    }


    @Test
    fun writesRealAacFixtureAndH264IntoTransportStream() {
        val fixturePath = requireNotNull(
            System.getProperty("pslink.hls.test.aacFixture")
        )
        val fixture = File(fixturePath)
        assertTrue("FFmpeg AAC fixture must exist", fixture.isFile && fixture.length() > 0L)

        val outputPath = requireNotNull(
            System.getProperty("pslink.hls.test.aacOutputDir")
        )
        val output = File(outputPath)
        output.deleteRecursively()
        assertTrue(output.mkdirs() || output.isDirectory)

        val video = javaClass.classLoader!!
            .getResourceAsStream("fixtures/hls-single-idr.h264")!!
            .use { it.readBytes() }

        val adts = fixture.readBytes()
        val publisher = HlsTransportStreamPublisher(output)
        publisher.start()
        publisher.writeAccessUnit(video, ptsUs = 0L, keyFrame = true)

        var offset = 0
        var frameIndex = 0

        while (offset + 7 <= adts.size) {
            assertEquals("ADTS sync word", 0xFF, adts[offset].toInt() and 0xFF)
            assertEquals(
                "ADTS sync/layer bits",
                0xF0,
                adts[offset + 1].toInt() and 0xF6
            )

            val headerLength =
                if ((adts[offset + 1].toInt() and 0x01) == 1) 7 else 9

            val frameLength =
                ((adts[offset + 3].toInt() and 0x03) shl 11) or
                ((adts[offset + 4].toInt() and 0xFF) shl 3) or
                ((adts[offset + 5].toInt() and 0xE0) shr 5)

            assertTrue("ADTS frame must contain AAC payload", frameLength > headerLength)
            assertTrue(
                "ADTS frame must fit inside fixture",
                offset + frameLength <= adts.size
            )

            val rawAac = adts.copyOfRange(
                offset + headerLength,
                offset + frameLength
            )

            val ptsUs =
                (frameIndex.toLong() * 1024L * 1_000_000L) / 48_000L

            publisher.writeAudioAccessUnit(rawAac, ptsUs)

            frameIndex++
            offset += frameLength
        }

        publisher.stop()

        assertTrue("Fixture must contain AAC frames", frameIndex > 0)
        assertEquals("ADTS fixture must be fully consumed", adts.size, offset)

        val playlist = File(output, "index.m3u8")
        assertTrue("AAC integration playlist must exist", playlist.isFile)

        val segmentName = Regex("pslink-segment-[0-9]{6}\\.ts")
            .find(playlist.readText())?.value

        assertFalse(
            "AAC playlist must reference a TS segment",
            segmentName.isNullOrBlank()
        )

        val segment = File(output, segmentName!!)
        assertTrue("AAC TS segment must exist", segment.isFile && segment.length() > 0L)

        val bytes = segment.readBytes()
        assertEquals("AAC TS must be packet-aligned", 0, bytes.size % 188)

        for (packetOffset in bytes.indices step 188) {
            assertEquals(
                "AAC TS sync byte at packet $packetOffset",
                0x47,
                bytes[packetOffset].toInt() and 0xFF
            )
        }
    }

}
