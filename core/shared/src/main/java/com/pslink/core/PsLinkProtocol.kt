// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.core

import org.json.JSONException
import org.json.JSONObject
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

    fun auth(code: String): String = JSONObject()
        .put("v", VERSION)
        .put("kind", KIND_AUTH)
        .put("code", code.trim())
        .put("client", "PS Link Mobile")
        .toString()
}

data class SubtitlePacket(
    val game: String,
    val sourceText: String,
    val translatedText: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val messageId: String = UUID.randomUUID().toString()
) {
    fun toJson(): String = JSONObject()
        .put("v", PsLinkProtocol.VERSION)
        .put("kind", PsLinkProtocol.KIND_SUBTITLE)
        .put("game", game.take(100))
        .put("source", sourceText.take(4000))
        .put("translation", translatedText.take(4000))
        .put("timestamp", timestampMs)
        .put("id", messageId)
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
                messageId = id
            )
        }
    }
}
