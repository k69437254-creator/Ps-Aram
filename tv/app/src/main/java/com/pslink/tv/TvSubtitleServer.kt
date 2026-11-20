// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import com.pslink.core.PsLinkProtocol
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
import org.json.JSONObject

/** Local network receiver. The six-digit code is a pairing gate, not transport encryption. */
class TvSubtitleServer(private val pairingCode: String) {
    private var engine: ApplicationEngine? = null
    private val mutableLatestPacket = MutableStateFlow<SubtitlePacket?>(null)
    val latestPacket: StateFlow<SubtitlePacket?> = mutableLatestPacket.asStateFlow()

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
                        auth.optString("code") == pairingCode
                    if (!accepted) {
                        send(Frame.Text(JSONObject().put("kind", "error").put("message", "رمز الاقتران غير صحيح").toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid pairing code"))
                        return@webSocket
                    }
                    send(Frame.Text(JSONObject().put("v", PsLinkProtocol.VERSION).put("kind", PsLinkProtocol.KIND_READY).toString()))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val packet = runCatching { SubtitlePacket.fromJson(frame.readText()) }.getOrNull() ?: continue
                        mutableLatestPacket.value = packet
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", PsLinkProtocol.KIND_ACK)
                            .put("id", packet.messageId)
                            .toString()))
                    }
                }
            }
        }.start(wait = false)
    }

    @Synchronized fun stop() {
        engine?.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L)
        engine = null
    }
}
