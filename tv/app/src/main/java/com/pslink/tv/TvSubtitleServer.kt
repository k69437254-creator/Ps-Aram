// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import com.pslink.core.PsLinkProtocol
import com.pslink.core.PairingCode
import com.pslink.core.PairingAttemptLimiter
import com.pslink.core.OriginAllowlist
import com.pslink.core.SessionIds
import com.pslink.core.ConnectionSlotLimiter
import com.pslink.core.SubtitlePacket
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.request.origin
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class TvCaptionLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class TvCaptionFrame(val timestampNs: Long, val lines: List<TvCaptionLine>)

class TvSubtitleServer(
    private val pairingCode: String,
    private val onMediaConfig: (TvMediaConfig) -> Unit,
    private val onMediaPacket: (ByteArray) -> Unit,
    private val onMediaStop: () -> Unit,
    allowedOrigins: String = ""
) {
    private var engine: ApplicationEngine? = null
    private val activeSession = AtomicReference<Job?>(null)
    private val activeSessionId = AtomicReference<String?>(null)
    private val sessionLock = Any()
    private val connectionSlots = ConnectionSlotLimiter(MAX_CONNECTIONS)
    private val connectionLeases = ConcurrentHashMap<Job, ConnectionSlotLimiter.Slot>()
    private val stopping = AtomicBoolean(true)
    private val pairingAttempts = PairingAttemptLimiter()
    private val originAllowlist = OriginAllowlist(allowedOrigins)
    private val mutableLatestPacket = MutableStateFlow<SubtitlePacket?>(null)
    val latestPacket: StateFlow<SubtitlePacket?> = mutableLatestPacket.asStateFlow()
    private val mutableCaptions = MutableStateFlow<TvCaptionFrame?>(null)
    val latestCaptions: StateFlow<TvCaptionFrame?> = mutableCaptions.asStateFlow()

    @Synchronized fun start() {
        if (engine != null) return
        stopping.set(false)
        engine = embeddedServer(CIO, host = "0.0.0.0", port = PsLinkProtocol.PORT) {
            install(WebSockets) {
                maxFrameSize = 2 * 1024 * 1024L + 28L
                pingInterval = 25_000L
                timeoutMillis = 90_000L
            }
            routing {
                webSocket(PsLinkProtocol.PATH) {
                    val pairingSource = call.request.origin.remoteHost
                    suspend fun rejectPairing(errorCode: String, message: String) {
                        if (errorCode != "PAIRING_RATE_LIMITED" &&
                            errorCode != "TOO_MANY_CONNECTIONS" &&
                            errorCode != "SERVER_STOPPING") {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                        }
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", "error")
                            .put("errorCode", errorCode)
                            .put("message", message)
                            .toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, errorCode))
                    }

                    val connectionJob = coroutineContext[Job]
                        ?: throw IllegalStateException("WebSocket connection has no Job")
                    val connectionSlot = connectionSlots.tryAcquire()
                    if (connectionSlot == null) {
                        rejectPairing("TOO_MANY_CONNECTIONS", "تم بلوغ الحد الأقصى لاتصالات التلفاز")
                        return@webSocket
                    }
                    val connectionRegistered = synchronized(sessionLock) {
                        if (stopping.get()) false
                        else {
                            connectionLeases[connectionJob] = connectionSlot
                            true
                        }
                    }
                    if (!connectionRegistered) {
                        connectionSlot.close()
                        rejectPairing("SERVER_STOPPING", "خادم التلفاز قيد الإيقاف")
                        return@webSocket
                    }
                    connectionJob.invokeOnCompletion {
                        synchronized(sessionLock) {
                            connectionLeases.remove(connectionJob, connectionSlot)
                        }
                        connectionSlot.close()
                    }

                    if (!originAllowlist.allows(call.request.headers["Origin"])) {
                        rejectPairing("ORIGIN_NOT_ALLOWED", "مصدر اتصال الويب غير مصرح به")
                        return@webSocket
                    }
                    if (!pairingAttempts.isAllowed(pairingSource, monotonicMillis())) {
                        rejectPairing("PAIRING_RATE_LIMITED", "محاولات كثيرة؛ انتظر دقيقة ثم أعد المحاولة")
                        return@webSocket
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
                    if (authFrame.data.size > 16 * 1024) {
                        rejectPairing("AUTH_FRAME_TOO_LARGE", "إطار المصادقة أكبر من الحد المسموح")
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
                        rejectPairing("INVALID_AUTH_REQUEST", "نوع طلب الاقتران غير صالح؛ أعد الاتصال")
                        return@webSocket
                    }
                    val suppliedCode = PairingCode.normalize(auth.optString("code"))
                    val expectedCode = PairingCode.normalize(pairingCode)
                    if (suppliedCode == null || expectedCode == null || suppliedCode != expectedCode) {
                        rejectPairing("INVALID_PAIRING_CODE", "رمز الاقتران غير صحيح أو لا يخص هذا التلفاز")
                        return@webSocket
                    }
                    val authenticatedSessionId = SessionIds.validate(auth.optString("sessionId", ""))
                    if (authenticatedSessionId == null) {
                        rejectPairing("INVALID_SESSION_ID", "معرّف الجلسة فارغ أو أطول من الحد المسموح")
                        return@webSocket
                    }
                    val sessionJob = coroutineContext[Job]
                        ?: throw IllegalStateException("Authenticated WebSocket session has no Job")
                    val admission = synchronized(sessionLock) {
                        if (stopping.get()) {
                            false to null
                        } else {
                            val previous = activeSession.getAndSet(sessionJob)
                            activeSessionId.set(authenticatedSessionId)
                            true to previous
                        }
                    }
                    if (!admission.first) {
                        rejectPairing("SERVER_STOPPING", "خادم التلفاز قيد الإيقاف")
                        return@webSocket
                    }
                    pairingAttempts.recordSuccess(pairingSource)
                    val previousSession = admission.second
                    if (previousSession != null && previousSession !== sessionJob) {
                        // The new authenticated session replaces the old one.
                        previousSession.cancel()
                    }
                    try {
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", PsLinkProtocol.KIND_READY)
                            .put("sessionId", authenticatedSessionId)
                            .toString()))
                        for (frame in incoming) {
                            if (!synchronized(sessionLock) { activeSession.get() === sessionJob }) break
                            when (frame) {
                                is Frame.Binary -> {
                                    if (frame.data.size in 29..(2 * 1024 * 1024 + 28)) {
                                        synchronized(sessionLock) {
                                            if (activeSession.get() === sessionJob)
                                                onMediaPacket(frame.data)
                                        }
                                    }
                                }
                                is Frame.Text -> {
                                    if (frame.data.size > PsLinkProtocol.MAX_CONTROL_FRAME_BYTES) {
                                        send(Frame.Text(JSONObject()
                                            .put("v", PsLinkProtocol.VERSION)
                                            .put("kind", "error")
                                            .put("errorCode", "CONTROL_FRAME_TOO_LARGE")
                                            .put("message", "رسالة التحكم أكبر من الحد المسموح")
                                            .put("sessionId", authenticatedSessionId)
                                            .toString()))
                                        close(CloseReason(
                                            CloseReason.Codes.VIOLATED_POLICY,
                                            "CONTROL_FRAME_TOO_LARGE"
                                        ))
                                        break
                                    }
                                    val raw = frame.readText()
                                    val message = runCatching { JSONObject(raw) }.getOrNull()
                                    if (message == null ||
                                        message.optString("sessionId", "") != authenticatedSessionId) continue
                                    if (message?.optInt("v", -1) == PsLinkProtocol.VERSION &&
                                        message.optString("kind") == PsLinkProtocol.KIND_HEARTBEAT) {
                                        send(Frame.Text(JSONObject()
                                            .put("v", PsLinkProtocol.VERSION)
                                            .put("kind", PsLinkProtocol.KIND_HEARTBEAT_ACK)
                                            .put("sessionId", authenticatedSessionId)
                                            .toString()))
                                        continue
                                    }
                                    val acknowledgements = synchronized(sessionLock) {
                                        if (activeSession.get() !== sessionJob ||
                                            activeSessionId.get() != authenticatedSessionId) {
                                            emptyList()
                                        } else {
                                            handleTextFrame(raw)
                                            val configAck = mediaConfigAck(raw)
                                            val packet = if (message.optString("kind") == PsLinkProtocol.KIND_SUBTITLE) {
                                                runCatching { SubtitlePacket.fromJson(raw) }.getOrNull()
                                            } else {
                                                null
                                            }
                                            listOfNotNull(configAck, packet?.let {
                                                JSONObject()
                                                    .put("v", PsLinkProtocol.VERSION)
                                                    .put("kind", PsLinkProtocol.KIND_ACK)
                                                    .put("id", it.messageId)
                                                    .put("sessionId", authenticatedSessionId)
                                                    .toString()
                                            })
                                        }
                                    }
                                    acknowledgements.forEach { send(Frame.Text(it)) }
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        synchronized(sessionLock) {
                            if (activeSession.compareAndSet(sessionJob, null)) {
                                activeSessionId.set(null)
                                mutableCaptions.value = TvCaptionFrame(0L, emptyList())
                                onMediaStop()
                            }
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
            json.optString("kind") != "media_config" ||
            SessionIds.validate(json.optString("sessionId", "")) != activeSessionId.get()) {
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
            .put("sessionId", json.optString("sessionId"))
            .toString()
    }

    private fun handleTextFrame(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optInt("v", -1) != PsLinkProtocol.VERSION) return
        val sessionId = SessionIds.validate(json.optString("sessionId", "")) ?: return
        if (sessionId != activeSessionId.get()) return
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
        stopping.set(true)
        val stoppingEngine = engine
        engine = null
        try {
            synchronized(sessionLock) {
                connectionLeases.values.forEach { it.close() }
                connectionLeases.clear()
                activeSession.getAndSet(null)?.cancel()
                activeSessionId.set(null)
                mutableCaptions.value = TvCaptionFrame(0L, emptyList())
                onMediaStop()
            }
        } finally {
            stoppingEngine?.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L)
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    companion object {
        const val MAX_CONNECTIONS = 16
    }
}
