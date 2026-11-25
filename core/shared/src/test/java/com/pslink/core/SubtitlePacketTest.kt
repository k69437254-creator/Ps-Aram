package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitlePacketTest {
    @Test fun sessionIdRoundTrips() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            sessionId = "session-a"
        )

        val decoded = SubtitlePacket.fromJson(packet.toJson())

        assertEquals("session-a", decoded.sessionId)
        assertEquals(packet.messageId, decoded.messageId)
        assertEquals("مرحبا", decoded.translatedText)
    }

    @Test fun sessionReplacementUsesDistinctId() {
        val first = SubtitlePacket(
            game = "PS Link",
            sourceText = "A",
            translatedText = "أ",
            sessionId = "session-a"
        )
        val replacement = first.copy(
            sessionId = "session-b",
            messageId = java.util.UUID.randomUUID().toString()
        )

        assertNotEquals(first.sessionId, replacement.sessionId)
    }
}
