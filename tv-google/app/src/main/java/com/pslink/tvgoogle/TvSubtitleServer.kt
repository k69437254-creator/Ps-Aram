// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tvgoogle

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import java.security.SecureRandom

data class TvCaptionLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class TvCaptionFrame(val timestampNs: Long, val lines: List<TvCaptionLine>)

/**
 * WebSocket receiver for PS Link TV applications.
 *
 * Improvements over the baseline:
 * - Accepts ws://. wss:// requires SSL/TLS configuration not part of this local-network protocol.
 * - Explicit pairing code rotation support.
 * - Session replacement with proper cleanup of the previous session.
 * - Bounded media packet validation (PSLM v1 header).
 * - Graceful shutdown with caption clearing.
 */
class TvSubtitleServer(
    private var pairingCode: String,
    private val onMediaConfig: (TvMediaConfig) -> Unit,
    private val onMediaPacket: (ByteArray) -> Unit,
    private val onMediaStop: () -> Unit
) {
    private var engine: ApplicationEngine? = null
    private val activeSession = AtomicReference<Job?>(null)
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
                    suspend fun rejectPairing(errorCode: String, message: String) {
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", "error")
                            .put("errorCode", errorCode)
                            .put("message", message)
                            .toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, errorCode))
                    }

                    val authFrame = try {
                        withTimeoutOrNull(6_000L) { incoming.receive() }
                    } catch (_: ClosedReceiveChannelException) {
                        return@webSocket
                    }
                    if (authFrame == null) {
                        rejectPairing("AUTH_TIMEOUT", "انتهت مهلة إدخال رمز الاقتران؛ أعد المحاولة")
                        return@webSocket
                    }
                    if (authFrame !is Frame.Text) {
                        rejectPairing("INVALID_AUTH_FRAME", "طلب الاقتران غير صالح؛ أعد الاتصال من تطبيق الهاتف")
                        return@webSocket
                    }

                    val auth = runCatching { JSONObject(authFrame.readText()) }.getOrNull()
                    if (auth == null) {
                        rejectPairing("INVALID_AUTH_JSON", "تعذر قراءة بيانات الاقتران؛ حدّث التطبيق ثم أعد المحاولة")
                        return@webSocket
                    }
                    if (auth.optInt("v", -1) != PsLinkProtocol.VERSION) {
                        rejectPairing("PROTOCOL_MISMATCH", "إصدار بروتوكول الهاتف والتلفاز غير متوافق؛ حدّث التطبيقين")
                        return@webSocket
                    }
                    if (auth.optString("kind") != PsLinkProtocol.KIND_AUTH) {
                        rejectPairing("INVALID_AUTH_REQUEST", "نوع طلب الاقتران غير صالح؛ إعادة الاتصال")
                        return@webSocket
                    }
                    val suppliedCode = PairingCode.normalize(auth.optString("code"))
                    val expectedCode = PairingCode.normalize(pairingCode)
                    if (suppliedCode == null || expectedCode == null || suppliedCode != expectedCode) {
                        rejectPairing("INVALID_PAIRING_CODE", "رمز الاقتران غير صحيح أو لا يخص هذا التلفاز")
                        return@webSocket
                    }
                    val sessionJob = coroutineContext[Job]
                        ?: throw IllegalStateException("Authenticated WebSocket session has no Job")
                    val previousSession = activeSession.getAndSet(sessionJob)
                    if (previousSession != null && previousSession !== sessionJob) {
                        // The new authenticated session replaces the old one.
                        previousSession.cancel()
                    }
                    try {
                        send(Frame.Text(JSONObject().put("v", PsLinkProtocol.VERSION).put("kind", PsLinkProtocol.KIND_READY).toString()))
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    if (frame.data.size in 29..(2 * 1024 * 1024 + 28))
                                        onMediaPacket(frame.data)
                                }
                                is Frame.Text -> {
                                    val raw = frame.readText()
                                    handleTextFrame(raw)

                                    // PSLINK_MEDIA_CONFIG_ACK_V1 — only acknowledge media_config messages,
                                    // not every text frame. Prevents spurious acks on captions/subtitles.
                                    val ackJson = runCatching { org.json.JSONObject(raw) }.getOrNull()
                                    if (ackJson != null && ackJson.optString("kind") == "media_config") {
                                        mediaConfigAck(raw)?.let { acknowledgement ->
                                            send(Frame.Text(acknowledgement))
                                        }
                                    }

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
                        if (activeSession.compareAndSet(sessionJob, null)) {
                            mutableCaptions.value = TvCaptionFrame(0L, emptyList())
                            onMediaStop()
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    // PSLINK_MEDIA_CONFIG_ACK_V1
    // Confirms only that the receiver accepted the configuration callback.
    // It does not claim that a hardware decoder has rendered a frame.
    private fun mediaConfigAck(raw: String): String? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        if (json.optInt("v", -1) != PsLinkProtocol.VERSION ||
            json.optString("kind") != "media_config") {
            return null
        }

        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        val codec = json.optInt("codec", -1)
        val fps = json.optInt("fps", 60)
        val requestId = json.optString("requestId", "").take(100)

        if (requestId.isBlank() ||
            width !in 320..7680 ||
            height !in 240..4320 ||
            codec !in 0..2 ||
            fps !in 15..120) {
            return null
        }

        return JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "media_config_ack")
            .put("requestId", requestId)
            .toString()
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

    /**
     * Rotate the pairing code for the next connection cycle.
     * Call this from the UI when the user requests a new code.
     */
    fun rotatePairingCode(): String {
        val newCode = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        pairingCode = newCode
        // Note: the active WebSocket sessions keep using the old code until they reconnect.
        // New connections will use the new code because this field is read at handshake time.
        // The caller should persist the new code (e.g., SharedPreferences) across restarts.
        return newCode
    }

    @Synchronized fun stop() {
        engine?.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L)
        engine = null
    }
}
