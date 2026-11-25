package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONException

class SubtitlePacketTest {
    @Test fun sessionIdRoundTrips() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            timestampMs = 123456789L,
            sessionId = "session-a"
        )

        val decoded = SubtitlePacket.fromJson(packet.toJson())

        assertEquals("session-a", decoded.sessionId)
        assertEquals(packet.messageId, decoded.messageId)
        assertEquals("PS Link", decoded.game)
        assertEquals("Hello", decoded.sourceText)
        assertEquals("مرحبا", decoded.translatedText)
        assertEquals(123456789L, decoded.timestampMs)
    }

    @Test fun malformedJsonIsRejected() {
        assertThrows(JSONException::class.java) {
            SubtitlePacket.fromJson("{")
        }
    }

    @Test fun unsupportedProtocolVersionIsRejected() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            sessionId = "session-a"
        )
        val raw = packet.toJson().replace("\"v\":2", "\"v\":1")
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePacket.fromJson(raw)
        }
    }

    @Test fun unexpectedPacketKindIsRejected() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            sessionId = "session-a"
        )
        val raw = packet.toJson().replace("\"kind\":\"subtitle\"", "\"kind\":\"ready\"")
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePacket.fromJson(raw)
        }
    }

    @Test fun blankSessionIdIsRejected() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            sessionId = ""
        )
        assertThrows(IllegalArgumentException::class.java) {
            packet.toJson()
        }
    }

    @Test fun oversizedSessionIdIsRejected() {
        val raw = """
            {"v":2,"kind":"subtitle","game":"PS Link","source":"Hello","translation":"مرحبا",
             "timestamp":123456789,"id":"message-a","sessionId":"${"x".repeat(81)}"}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePacket.fromJson(raw)
        }
    }

    @Test fun oversizedSessionIdIsNotTruncatedWhenSerialized() {
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = "Hello",
            translatedText = "مرحبا",
            sessionId = "x".repeat(SessionIds.MAX_LENGTH + 1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            packet.toJson()
        }
    }

    @Test fun authRequiresValidSessionId() {
        assertThrows(IllegalArgumentException::class.java) {
            PsLinkProtocol.auth("123456", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PsLinkProtocol.auth("123456", "x".repeat(SessionIds.MAX_LENGTH + 1))
        }
        assertEquals(
            "session-a",
            org.json.JSONObject(PsLinkProtocol.auth("123456", "session-a"))
                .getString("sessionId")
        )
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
