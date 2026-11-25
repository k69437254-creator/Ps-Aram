// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAccessUnitReorderBufferTest {
    private data class Written(val kind: String, val ptsUs: Long)

    @Test fun ordersVideoAndAudioBySharedPresentationTimestamp() {
        val written = mutableListOf<Written>()
        val buffer = HlsAccessUnitReorderBuffer(
            reorderWindowUs = 150L,
            writeVideo = { _, pts, _ -> written += Written("video", pts) },
            writeAudio = { _, pts, _, _ -> written += Written("audio", pts) }
        )
        buffer.offerAudio(byteArrayOf(1), 100L, 48000, 2) // ignored before first IDR
        buffer.offerVideo(byteArrayOf(0), 100L, keyFrame = true)
        buffer.offerVideo(byteArrayOf(3), 300L, keyFrame = false)
        buffer.offerAudio(byteArrayOf(2), 200L, 48000, 2)
        buffer.offerVideo(byteArrayOf(4), 250L, keyFrame = false)
        buffer.close()
        assertEquals(listOf(100L, 200L, 250L, 300L), written.map { it.ptsUs })
        assertEquals(listOf("video", "audio", "video", "video"), written.map { it.kind })
        assertEquals(0, buffer.pendingPackets())
    }

    @Test fun dropsPacketsThatArriveBehindCommittedWatermark() {
        val written = mutableListOf<Long>()
        val buffer = HlsAccessUnitReorderBuffer(
            reorderWindowUs = 0L,
            writeVideo = { _, pts, _ -> written += pts },
            writeAudio = { _, pts, _, _ -> written += pts }
        )
        assertTrue(buffer.offerVideo(byteArrayOf(1), 100L, keyFrame = true))
        assertTrue(buffer.offerAudio(byteArrayOf(2), 200L, 48000, 2))
        assertFalse(buffer.offerVideo(byteArrayOf(3), 150L, keyFrame = false))
        buffer.close()
        assertEquals(listOf(100L, 200L), written)
        assertEquals(1L, buffer.lateDrops())
    }

    @Test fun boundsQueueAndRecordsForcedFlushUnderOverload() {
        val written = mutableListOf<Long>()
        val buffer = HlsAccessUnitReorderBuffer(
            reorderWindowUs = 2_000_000L,
            capacity = 2,
            writeVideo = { _, pts, _ -> written += pts },
            writeAudio = { _, pts, _, _ -> written += pts }
        )
        buffer.offerVideo(byteArrayOf(1), 100L, keyFrame = true)
        buffer.offerAudio(byteArrayOf(2), 200L, 48000, 2)
        buffer.offerVideo(byteArrayOf(3), 300L, false)
        assertTrue(buffer.pendingPackets() <= 2)
        assertEquals(1L, buffer.overflowFlushes())
        buffer.close()
        assertTrue(written.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun writesVideoBeforeAudioWhenTimestampsAreEqual() {
        val order = mutableListOf<String>()
        val buffer = HlsAccessUnitReorderBuffer(
            reorderWindowUs = 0L,
            writeVideo = { _, _, _ -> order += "video" },
            writeAudio = { _, _, _, _ -> order += "audio" }
        )
        buffer.offerVideo(byteArrayOf(1), 100L, keyFrame = true)
        buffer.offerAudio(byteArrayOf(2), 100L, 48000, 2)
        buffer.close()
        assertEquals(listOf("video", "audio"), order)
    }
    @Test fun enforcesBothAccessUnitAndAggregateByteCaps() {
        val buffer = HlsAccessUnitReorderBuffer(
            reorderWindowUs = 2_000_000L,
            capacity = 8,
            maxQueuedBytes = 12L,
            maxAccessUnitBytes = 8,
            writeVideo = { _, _, _ -> },
            writeAudio = { _, _, _, _ -> }
        )
        assertFalse("oversized first IDR must not arm the timeline", buffer.offerVideo(ByteArray(9), 100L, true))
        assertFalse("audio must remain gated until an accepted IDR", buffer.offerAudio(byteArrayOf(1), 110L, 48000, 2))
        assertTrue(buffer.offerVideo(ByteArray(8), 120L, true))
        assertTrue(buffer.offerAudio(ByteArray(5), 130L, 48000, 2))
        assertTrue("aggregate payload bytes must stay within configured cap", buffer.pendingBytes() <= 12L)
        assertTrue("packet count must stay within configured cap", buffer.pendingPackets() <= 8)
        assertEquals(1L, buffer.oversizedDrops())
        assertTrue("byte pressure must commit the oldest timestamp first", buffer.overflowFlushes() > 0L)
        buffer.close()
        assertEquals(0L, buffer.pendingBytes())
    }

}
