// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.core

import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object PsLinkProtocol {
    const val VERSION = 2
    const val PORT = 8765
    const val PATH = "/subtitle"
    const val KIND_AUTH = "auth"
    const val KIND_READY = "ready"
    const val KIND_HEARTBEAT = "heartbeat"
    const val KIND_HEARTBEAT_ACK = "heartbeat_ack"
    const val KIND_SUBTITLE = "subtitle"
    const val KIND_ACK = "ack"

    fun socketUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.any(Char::isWhitespace)) return null

        val withScheme = if ("://" in value) {
            value
        } else {
            "//$value"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val explicitScheme = uri.scheme?.lowercase()
        val scheme = when (explicitScheme) {
            null -> "ws"
            "ws", "wss" -> explicitScheme
            "http" -> "ws"
            "https" -> "wss"
            else -> return null
        }
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null)
            return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/" && path != PATH) return null
        val effectivePort = when {
            port != -1 -> ":$port"
            scheme == "ws" -> ":$PORT"
            else -> ""
        }
        return "$scheme://$host$effectivePort$PATH"
    }

    fun auth(code: String, sessionId: String = ""): String = JSONObject()
        .put("v", VERSION)
        .put("kind", KIND_AUTH)
        .put("code", code.trim())
        .put("client", "PS Link Mobile")
        .put("sessionId", sessionId.take(80))
        .toString()
}

data class SubtitlePacket(
    val game: String,
    val sourceText: String,
    val translatedText: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val messageId: String = UUID.randomUUID().toString(),
    val sessionId: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("v", PsLinkProtocol.VERSION)
        .put("kind", PsLinkProtocol.KIND_SUBTITLE)
        .put("game", game.take(100))
        .put("source", sourceText.take(4000))
        .put("translation", translatedText.take(4000))
        .put("timestamp", timestampMs)
        .put("id", messageId)
        .put("sessionId", sessionId.take(80))
        .toString()

    companion object {
        @Throws(JSONException::class)
        fun fromJson(raw: String): SubtitlePacket {
            val json = JSONObject(raw)
            require(json.optInt("v", -1) == PsLinkProtocol.VERSION) { "Unsupported protocol version" }
            require(json.optString("kind") == PsLinkProtocol.KIND_SUBTITLE) { "Unexpected packet type" }
            val id = json.optString("id", "")
            require(id.isNotBlank() && id.length <= 80) { "Missing packet ID" }
            return SubtitlePacket(
                game = json.optString("game", "").take(100),
                sourceText = json.optString("source", "").take(4000),
                translatedText = json.optString("translation", "").take(4000),
                timestampMs = json.optLong("timestamp", System.currentTimeMillis()),
                messageId = id,
                sessionId = json.optString("sessionId", "").take(80)
            )
        }
    }
}
