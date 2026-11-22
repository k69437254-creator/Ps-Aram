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
}
