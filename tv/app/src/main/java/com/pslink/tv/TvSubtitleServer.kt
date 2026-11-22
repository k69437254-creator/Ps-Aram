// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import com.pslink.core.PsLinkProtocol
import com.pslink.core.PairingCode
import com.pslink.core.SubtitlePacket
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class TvCaptionLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class TvCaptionFrame(val timestampNs: Long, val lines: List<TvCaptionLine>)

class TvSubtitleServer(
    private val pairingCode: String,
    private val onMediaConfig: (TvMediaConfig) -> Unit,
    private val onMediaPacket: (ByteArray) -> Unit,
    private val onMediaStop: () -> Unit
) {
    private var engine: ApplicationEngine? = null
    private val mutableLatestPacket = MutableStateFlow<SubtitlePacket?>(null)
    val latestPacket: StateFlow<SubtitlePacket?> = mutableLatestPacket.asStateFlow()
    private val mutableCaptions = MutableStateFlow<TvCaptionFrame?>(null)
    val latestCaptions: StateFlow<TvCaptionFrame?> = mutableCaptions.asStateFlow()

    @Synchronized fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = "0.0.0.0", port = PsLinkProtocol.PORT) {
            install(WebSockets)
            routing {
                webSocket(PsLinkProtocol.PATH) {
                    val authFrame = withTimeoutOrNull(6_000L) { incoming.receive() }
                    if (authFrame !is Frame.Text) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pairing required"))
                        return@webSocket
                    }
                    val auth = runCatching { JSONObject(authFrame.readText()) }.getOrNull()
                    val accepted = auth != null &&
                        auth.optInt("v", -1) == PsLinkProtocol.VERSION &&
                        auth.optString("kind") == PsLinkProtocol.KIND_AUTH &&
                        PairingCode.normalize(auth.optString("code")) == PairingCode.normalize(pairingCode)
                    if (!accepted) {
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", "error")
                            .put("message", "رمز الاقتران غير صحيح أو لا يخص هذا التلفاز")
                            .toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid pairing code"))
                        return@webSocket
                    }
                    send(Frame.Text(JSONObject().put("v", PsLinkProtocol.VERSION).put("kind", PsLinkProtocol.KIND_READY).toString()))
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    if (frame.data.size in 29..(2 * 1024 * 1024 + 28))
                                        onMediaPacket(frame.data)
                                }
                                is Frame.Text -> {
                                    val raw = frame.readText()
                                    handleTextFrame(raw)
                                    val packet = runCatching { SubtitlePacket.fromJson(raw) }.getOrNull()
                                    if (packet != null) {
                                        send(Frame.Text(JSONObject()
                                            .put("v", PsLinkProtocol.VERSION)
                                            .put("kind", PsLinkProtocol.KIND_ACK)
                                            .put("id", packet.messageId)
                                            .toString()))
                                    }
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        mutableCaptions.value = TvCaptionFrame(0L, emptyList())
                        onMediaStop()
                    }
                }
            }
        }.start(wait = false)
    }

    private fun handleTextFrame(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optInt("v", -1) != PsLinkProtocol.VERSION) return
        when (json.optString("kind")) {
            "captions" -> {
                val array = json.optJSONArray("lines") ?: JSONArray()
                val lines = ArrayList<TvCaptionLine>(3)
                for (i in 0 until minOf(array.length(), 3)) {
                    val item = array.optJSONObject(i) ?: continue
                    val text = item.optString("text", "").take(1000).trim()
                    if (text.isBlank()) continue
                    val left = item.optDouble("left", -1.0).toFloat()
                    val top = item.optDouble("top", -1.0).toFloat()
                    val right = item.optDouble("right", -1.0).toFloat()
                    val bottom = item.optDouble("bottom", -1.0).toFloat()
                    if (left !in 0f..1f || top !in 0f..1f || right !in 0f..1f || bottom !in 0f..1f || right <= left || bottom <= top) continue
                    lines.add(TvCaptionLine(text, left, top, right, bottom))
                }
                mutableCaptions.value = TvCaptionFrame(
                    json.optLong("timestampNs", 0L).coerceAtLeast(0L),
                    lines
                )
            }
            "media_config" -> {
                val width = json.optInt("width", 0)
                val height = json.optInt("height", 0)
                val codec = json.optInt("codec", -1)
                val fps = json.optInt("fps", 60)
                if (width in 320..7680 && height in 240..4320 && codec in 0..2 && fps in 15..120)
                    onMediaConfig(TvMediaConfig(width, height, codec, fps))
            }
            PsLinkProtocol.KIND_SUBTITLE -> {
                val packet = runCatching { SubtitlePacket.fromJson(raw) }.getOrNull() ?: return
                mutableLatestPacket.value = packet
            }
        }
    }

    @Synchronized fun stop() {
        engine?.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L)
        engine = null
    }
}
