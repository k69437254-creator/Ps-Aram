// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import com.pslink.core.PairingCode
import com.pslink.core.PairingAttemptLimiter
import com.pslink.core.OriginAllowlist
import com.pslink.core.ConnectionSlotLimiter
import com.pslink.core.SessionIds
import com.pslink.core.BoundedByteAccumulator
import com.pslink.core.SubtitlePacket
import com.pslink.core.PsLinkProtocol
import com.pslink.mobile.LiveGameTranslator
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.options
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONException
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private sealed class LimitedJsonBody {
    data class Parsed(val value: JSONObject) : LimitedJsonBody()
    object Invalid : LimitedJsonBody()
    object TooLarge : LimitedJsonBody()
}

private data class ActivePhoneSession(
    val sessionId: String,
    val slot: ConnectionSlotLimiter.Slot
)

/**
 * Phone-side PS Link receiver endpoint for Tizen/webOS clients.
 * WebSocket pairing and HLS HTTP files share the existing PS Link port.
 */
class PhoneReceiverServer(
    pairingCode: String,
    private val hlsDirectory: File,
    allowedOrigins: String = "",
    publicBaseUrl: String = ""
) {
    private val normalizedCode = requireNotNull(PairingCode.normalize(pairingCode)) {
        "Phone receiver pairing code must contain exactly six digits"
    }
    private val pairingAttempts = PairingAttemptLimiter()
    private val originAllowlist = OriginAllowlist(allowedOrigins)
    private val configuredPublicBaseUrl = normalizePublicBaseUrl(publicBaseUrl)
    // Bearer token for HLS GET requests; the pairing code is never placed in media URLs.
    private val streamToken: String = run {
        val alphabet = "0123456789abcdef"
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
    private val sessions = ConcurrentHashMap<DefaultWebSocketServerSession, ActivePhoneSession>()
    private val connectionLeases = ConcurrentHashMap<Job, ConnectionSlotLimiter.Slot>()
    private val sessionLock = Any()
    private val sessionSlots = ConnectionSlotLimiter(MAX_SESSIONS)
    private val connectionSlots = ConnectionSlotLimiter(MAX_CONNECTIONS)
    private val stopping = AtomicBoolean(true)
    private val streamAvailable = AtomicBoolean(false)
    private val subtitleHistoryLock = Any()
    private val subtitleHistory = mutableListOf<Pair<Long, String>>()
    private var subtitleSequence = 0L
    private var lastPublishedCaptionText = ""
    private var engine: ApplicationEngine? = null
    @Volatile private var serverScope: CoroutineScope? = null

    private suspend fun receiveLimitedJson(call: ApplicationCall, limitBytes: Int): LimitedJsonBody {
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > limitBytes) return LimitedJsonBody.TooLarge

        val channel = call.receiveChannel()
        val accumulator = BoundedByteAccumulator(limitBytes)
        val buffer = ByteArray(minOf(limitBytes + 1, 8 * 1024))
        var received = 0
        while (received <= limitBytes) {
            val readLimit = minOf(buffer.size, limitBytes + 1 - received)
            val count = channel.readAvailable(buffer, 0, readLimit)
            if (count < 0) break
            if (count == 0) continue
            received += count
            if (!accumulator.append(buffer, 0, count)) return LimitedJsonBody.TooLarge
        }

        val raw = accumulator.toByteArray() ?: return LimitedJsonBody.TooLarge
        return try {
            LimitedJsonBody.Parsed(JSONObject(String(raw, Charsets.UTF_8)))
        } catch (_: JSONException) {
            LimitedJsonBody.Invalid
        }
    }

    @Synchronized fun start() {
        if (engine != null) return
        stopping.set(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = PsLinkProtocol.PORT) {
                install(WebSockets) {
                    maxFrameSize = MAX_CONTROL_FRAME_BYTES.toLong() + 1L
                    pingInterval = 25_000L
                    timeoutMillis = SESSION_IDLE_TIMEOUT_MS
                }
                routing {
                    options("/hls/{token}/{name}") {
                        val origin = call.request.headers["Origin"]
                        if (origin != null && !originAllowlist.allows(call.request.headers["Origin"])) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@options
                        }
                        if (origin != null) {
                            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
                            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
                            call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, OPTIONS")
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/receiver/roku/stream-url") {
                        val pairingSource = call.request.origin.remoteHost
                        if (!originAllowlist.allows(call.request.headers["Origin"])) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.Forbidden)
                            return@post
                        }
                        if (!pairingAttempts.isAllowed(pairingSource, monotonicMillis())) {
                            call.respond(HttpStatusCode.TooManyRequests)
                            return@post
                        }
                        val body = receiveLimitedJson(call, MAX_HTTP_BODY_BYTES)
                        if (body === LimitedJsonBody.TooLarge) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.PayloadTooLarge)
                            return@post
                        }
                        val request = (body as? LimitedJsonBody.Parsed)?.value
                        if (request == null) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val suppliedCode = PairingCode.normalize(
                            request.optString("code")
                        )

                        if (suppliedCode == null || suppliedCode != normalizedCode) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                        pairingAttempts.recordSuccess(pairingSource)

                        if (!streamAvailable.get()) {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                            return@post
                        }

                        val payload = streamUrlMessage()
                        if (payload == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                        } else {
                            call.respondText(
                                payload,
                                ContentType.Application.Json,
                                HttpStatusCode.OK
                            )
                        }
                    }

                    post("/receiver/roku/subtitle") {
                        val pairingSource = call.request.origin.remoteHost
                        if (!originAllowlist.allows(call.request.headers["Origin"])) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.Forbidden)
                            return@post
                        }
                        if (!pairingAttempts.isAllowed(pairingSource, monotonicMillis())) {
                            call.respond(HttpStatusCode.TooManyRequests)
                            return@post
                        }
                        val body = receiveLimitedJson(call, MAX_HTTP_BODY_BYTES)
                        if (body === LimitedJsonBody.TooLarge) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.PayloadTooLarge)
                            return@post
                        }
                        val request = (body as? LimitedJsonBody.Parsed)?.value
                        if (request == null) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val suppliedCode = PairingCode.normalize(
                            request.optString("code")
                        )

                        if (suppliedCode == null || suppliedCode != normalizedCode) {
                            pairingAttempts.recordFailure(pairingSource, monotonicMillis())
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }
                        pairingAttempts.recordSuccess(pairingSource)

                        val after = request.optLong("after", 0L).coerceAtLeast(0L)

                        val snapshot = synchronized(subtitleHistoryLock) {
                            val oldest = subtitleHistory.firstOrNull()?.first
                                ?: (subtitleSequence + 1L)

                            val entries = subtitleHistory
                                .filter { it.first > after }
                                .take(50)

                            Triple(oldest, entries, subtitleSequence)
                        }

                        val items = org.json.JSONArray()

                        snapshot.second.forEach { entry ->
                            items.put(
                                JSONObject()
                                    .put("sequence", entry.first)
                                    .put("packet", JSONObject(entry.second))
                            )
                        }

                        val nextCursor = snapshot.second.lastOrNull()?.first ?: after

                        val response = JSONObject()
                            .put("items", items)
                            .put("nextCursor", nextCursor)
                            .put("oldestSequence", snapshot.first)
                            .put("latestSequence", snapshot.third)

                        call.respondText(
                            response.toString(),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }

                    get("/hls/{token}/index.m3u8") {
                        val origin = call.request.headers["Origin"]
                        if (!originAllowlist.allows(call.request.headers["Origin"])) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                        if (!isValidStreamToken(call.parameters["token"])) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        val playlist = File(hlsDirectory, "index.m3u8")
                        if (!playlist.isFile) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.response.headers.append(
                                HttpHeaders.CacheControl,
                                "no-store, private"
                            )
                            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
                            if (origin != null) {
                                call.response.headers.append(
                                    HttpHeaders.AccessControlAllowOrigin,
                                    origin
                                )
                            }
                            call.respondBytes(
                                playlist.readBytes(),
                                ContentType.parse("application/vnd.apple.mpegurl")
                            )
                        }
                    }
                    get("/hls/{token}/{name}") {
                        val origin = call.request.headers["Origin"]
                        if (!originAllowlist.allows(call.request.headers["Origin"])) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@get
                        }
                        if (!isValidStreamToken(call.parameters["token"])) {
                            call.respond(HttpStatusCode.NotFound)
                            return@get
                        }
                        val name = call.parameters["name"].orEmpty()
                        if (!name.matches(Regex("pslink-segment-[0-9]{6}\\.ts"))) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@get
                        }
                        val directory = hlsDirectory.canonicalFile
                        val segment = File(directory, name).canonicalFile
                        if (segment.parentFile != directory || !segment.isFile) {
                            call.respond(HttpStatusCode.NotFound)
                        } else {
                            call.response.headers.append(HttpHeaders.CacheControl, "no-store, private")
                            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Origin)
                            if (origin != null) {
                                call.response.headers.append(
                                    HttpHeaders.AccessControlAllowOrigin,
                                    origin
                                )
                            }
                            call.respondBytes(segment.readBytes(), ContentType.parse("video/mp2t"))
                        }
                    }
                    webSocket(PsLinkProtocol.PATH) {
                        val pairingSource = call.request.origin.remoteHost
                        val connectionJob = coroutineContext[Job]
                            ?: throw IllegalStateException("WebSocket connection has no Job")
                        val connectionSlot = connectionSlots.tryAcquire()
                        if (connectionSlot == null) {
                            reject("TOO_MANY_CONNECTIONS", "تم بلوغ الحد الأقصى لاتصالات الهاتف", false)
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
                            reject("SERVER_STOPPING", "خادم الهاتف قيد الإيقاف", false)
                            return@webSocket
                        }
                        connectionJob.invokeOnCompletion {
                            synchronized(sessionLock) {
                                connectionLeases.remove(connectionJob, connectionSlot)
                            }
                            connectionSlot.close()
                        }
                        if (!originAllowlist.allows(call.request.headers["Origin"])) {
                            reject("ORIGIN_NOT_ALLOWED", "مصدر اتصال الويب غير مصرح به")
                            return@webSocket
                        }
                        if (!pairingAttempts.isAllowed(pairingSource, monotonicMillis())) {
                            reject("PAIRING_RATE_LIMITED", "محاولات كثيرة؛ انتظر دقيقة ثم أعد المحاولة", false)
                            return@webSocket
                        }
                        val authFrame = try {
                            withTimeoutOrNull(6_000L) { incoming.receive() }
                        } catch (_: ClosedReceiveChannelException) {
                            return@webSocket
                        } catch (_: CancellationException) {
                            return@webSocket
                        }
                        if (authFrame !is Frame.Text) {
                            reject("INVALID_AUTH_FRAME", "طلب الاقتران غير صالح")
                            return@webSocket
                        }
                        if (authFrame.data.size > MAX_AUTH_FRAME_BYTES) {
                            reject("AUTH_FRAME_TOO_LARGE", "بيانات الاقتران أكبر من الحد المسموح")
                            return@webSocket
                        }
                        val auth = runCatching { JSONObject(authFrame.readText()) }.getOrNull()
                        if (auth == null ||
                            auth.optInt("v", -1) != PsLinkProtocol.VERSION ||
                            auth.optString("kind") != PsLinkProtocol.KIND_AUTH) {
                            reject("INVALID_AUTH_REQUEST", "إصدار أو صيغة طلب الاقتران غير متوافقة")
                            return@webSocket
                        }
                        val supplied = PairingCode.normalize(auth.optString("code"))
                        if (supplied == null || supplied != normalizedCode) {
                            reject("INVALID_PAIRING_CODE", "رمز اقتران الهاتف غير صحيح")
                            return@webSocket
                        }
                        val authenticatedSessionId = SessionIds.validate(auth.optString("sessionId", ""))
                        if (authenticatedSessionId == null) {
                            reject("INVALID_SESSION_ID", "معرّف الجلسة فارغ أو أطول من الحد المسموح")
                            return@webSocket
                        }
                        val sessionSlot = sessionSlots.tryAcquire()
                        if (sessionSlot == null) {
                            reject("TOO_MANY_SESSIONS", "تم بلوغ الحد الأقصى لاتصالات الهاتف")
                            return@webSocket
                        }
                        val activeSession = ActivePhoneSession(
                            authenticatedSessionId,
                            sessionSlot
                        )
                        try {
                            val registered = synchronized(sessionLock) {
                                if (stopping.get()) false
                                else {
                                    sessions[this] = activeSession
                                    true
                                }
                            }
                            if (!registered) {
                                reject("SERVER_STOPPING", "خادم الهاتف قيد الإيقاف")
                                return@webSocket
                            }
                            pairingAttempts.recordSuccess(pairingSource)
                            send(Frame.Text(JSONObject()
                                .put("v", PsLinkProtocol.VERSION)
                                .put("kind", PsLinkProtocol.KIND_READY)
                                .put("sessionId", authenticatedSessionId)
                                .toString()))
                            if (streamAvailable.get()) sendStreamUrl(this, authenticatedSessionId)
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Close -> break
                                    is Frame.Text -> {
                                        if (frame.data.size > MAX_CONTROL_FRAME_BYTES) {
                                            send(Frame.Text(JSONObject()
                                                .put("v", PsLinkProtocol.VERSION)
                                                .put("kind", "error")
                                                .put("errorCode", "CONTROL_FRAME_TOO_LARGE")
                                                .put("message", "رسالة التحكم أكبر من الحد المسموح")
                                                .put("sessionId", authenticatedSessionId)
                                                .toString()))
                                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "CONTROL_FRAME_TOO_LARGE"))
                                            break
                                        }
                                        val message = runCatching {
                                            JSONObject(frame.readText())
                                        }.getOrNull()
                                        if (message?.optInt("v", -1) == PsLinkProtocol.VERSION &&
                                            message.optString("sessionId", "") == authenticatedSessionId &&
                                            message.optString("kind") == PsLinkProtocol.KIND_HEARTBEAT) {
                                            send(Frame.Text(JSONObject()
                                                .put("v", PsLinkProtocol.VERSION)
                                                .put("kind", PsLinkProtocol.KIND_HEARTBEAT_ACK)
                                                .put("sessionId", authenticatedSessionId)
                                                .toString()))
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        } finally {
                            synchronized(sessionLock) {
                                sessions.remove(this, activeSession)
                            }
                            sessionSlot.close()
                        }
                    }
                }
            }.start(wait = false)
            activeServer = this
        } catch (error: Exception) {
            stopping.set(true)
            engine = null
            scope.cancel()
            serverScope = null
            throw error
        }
    }

    private suspend fun DefaultWebSocketServerSession.reject(
        code: String,
        message: String,
        recordFailure: Boolean = true
    ) {
        if (recordFailure) {
            pairingAttempts.recordFailure(call.request.origin.remoteHost, monotonicMillis())
        }
        runCatching {
            send(Frame.Text(JSONObject()
                .put("v", PsLinkProtocol.VERSION)
                .put("kind", "error")
                .put("errorCode", code)
                .put("message", message)
                .toString()))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, code))
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L

    private fun appendSubtitleHistory(payload: String) {
        synchronized(subtitleHistoryLock) {
            subtitleSequence += 1L
            subtitleHistory.add(subtitleSequence to payload)
            while (subtitleHistory.size > 100) {
                subtitleHistory.removeAt(0)
            }
        }
    }

    private fun broadcastBoundJson(baseJson: JSONObject): Boolean {
        val scope = serverScope ?: return false
        val targets = sessions.entries.toList()
        if (targets.isNotEmpty()) {
            scope.launch {
                targets.forEach { (session, activeSession) ->
                    runCatching {
                        val boundPayload = JSONObject(baseJson.toString())
                            .put("sessionId", activeSession.sessionId)
                            .toString()
                        session.send(Frame.Text(boundPayload))
                    }
                }
            }
        }
        return true
    }

    /**
     * Broadcast one translated subtitle packet to all authenticated TV receivers.
     * Returns false when the receiver server is stopped; true means the packet
     * was retained in history, not that a TV has displayed it.
     */
    private fun publishSubtitle(packet: SubtitlePacket): Boolean {
        if (serverScope == null) return false
        val payload = packet.toJson()
        appendSubtitleHistory(payload)
        return broadcastBoundJson(JSONObject(payload))
    }

    /**
     * Broadcast live OCR spatial captions to all authenticated phone-side WebSocket receivers
     * and record changed dialogue states in Roku's polling history.
     */
    private fun publishCaptions(lines: List<LiveGameTranslator.CaptionLine>, timestampNs: Long): Boolean {
        if (serverScope == null || timestampNs <= 0L) return false
        val boundedLines = lines.take(3)
        val array = org.json.JSONArray()
        boundedLines.forEach { line ->
            val text = line.translated.take(1000).trim()
            if (text.isNotEmpty()) {
                array.put(JSONObject()
                    .put("text", text)
                    .put("left", line.left.coerceIn(0f, 1f).toDouble())
                    .put("top", line.top.coerceIn(0f, 1f).toDouble())
                    .put("right", line.right.coerceIn(0f, 1f).toDouble())
                    .put("bottom", line.bottom.coerceIn(0f, 1f).toDouble()))
            }
        }
        val payload = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "captions")
            .put("timestampNs", timestampNs)
            .put("lines", array)

        val combinedText = boundedLines
            .map { it.translated.take(1000).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val rokuPacketJson = synchronized(subtitleHistoryLock) {
            if (combinedText == lastPublishedCaptionText) {
                null
            } else {
                lastPublishedCaptionText = combinedText
                JSONObject()
                    .put("v", PsLinkProtocol.VERSION)
                    .put("kind", PsLinkProtocol.KIND_SUBTITLE)
                    .put("game", "PS Link")
                    .put("source", boundedLines.joinToString("\n") { it.source.take(1000).trim() })
                    .put("translation", combinedText)
                    .put("clear", combinedText.isEmpty())
                    .put("timestamp", System.currentTimeMillis())
                    .toString()
            }
        }
        if (rokuPacketJson != null) appendSubtitleHistory(rokuPacketJson)
        return broadcastBoundJson(payload)
    }

    /** Called after the first complete IDR-aligned HLS segment is published. */
    fun notifyStreamAvailable() {
        if (stopping.get()) return
        streamAvailable.set(true)
        val scope = serverScope ?: return
        scope.launch {
            sessions.entries.toList().forEach { (session, activeSession) ->
                val messageReady = streamUrlMessage(activeSession.sessionId) ?: return@forEach
                runCatching { session.send(Frame.Text(messageReady)) }
            }
        }
    }

    private suspend fun sendStreamUrl(session: DefaultWebSocketServerSession, sessionId: String) {
        val message = streamUrlMessage(sessionId) ?: return
        session.send(Frame.Text(message))
    }

    private fun isValidStreamToken(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            streamToken.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun normalizePublicBaseUrl(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null

        val parsed = runCatching { URI(raw) }.getOrElse {
            throw IllegalArgumentException("pslink.publicBaseUrl must be a valid HTTPS origin", it)
        }
        require(parsed.scheme.equals("https", ignoreCase = true)) {
            "pslink.publicBaseUrl must use HTTPS"
        }
        require(!parsed.host.isNullOrBlank() && parsed.rawAuthority != null) {
            "pslink.publicBaseUrl must include a valid host"
        }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "pslink.publicBaseUrl must not contain credentials, query, or fragment"
        }
        require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") {
            "pslink.publicBaseUrl must be an origin without a path prefix"
        }
        val authority = requireNotNull(parsed.rawAuthority)
        require(!authority.any { it.isWhitespace() || it == '\\' || it == '%' }) {
            "pslink.publicBaseUrl contains an invalid authority"
        }
        require(parsed.port == -1 || parsed.port in 1..65535) {
            "pslink.publicBaseUrl contains an invalid port"
        }
        val portSuffix = if (authority.startsWith("[")) {
            val closingBracket = authority.indexOf(']')
            require(closingBracket > 0) {
                "pslink.publicBaseUrl contains an invalid IPv6 authority"
            }
            authority.substring(closingBracket + 1)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) authority.substring(colon) else ""
        }
        require(
            portSuffix.isEmpty() ||
                (portSuffix.startsWith(":") &&
                    portSuffix.drop(1).isNotEmpty() &&
                    portSuffix.drop(1).all { it.isDigit() })
        ) {
            "pslink.publicBaseUrl contains a malformed port"
        }
        return "https://$authority"
    }

    private fun streamUrlMessage(sessionId: String? = null): String? {
        val base = configuredPublicBaseUrl
            ?: localIpv4Address()?.let { "http://$it:${PsLinkProtocol.PORT}" }
            ?: return null
        val message = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "stream_url")
            .put("url", "$base/hls/$streamToken/index.m3u8")
        sessionId?.let { message.put("sessionId", it) }
        return message.toString()
    }

    @Synchronized fun stop() {
        stopping.set(true)
        if (activeServer === this) activeServer = null
        val stoppingEngine = engine
        engine = null
        val stoppingScope = serverScope
        serverScope = null
        stoppingScope?.cancel()
        streamAvailable.set(false)
        synchronized(subtitleHistoryLock) {
            subtitleHistory.clear()
            subtitleSequence = 0L
            lastPublishedCaptionText = ""
        }
        synchronized(sessionLock) {
            connectionLeases.values.forEach { it.close() }
            connectionLeases.clear()
            sessions.values.forEach { it.slot.close() }
            sessions.clear()
        }
        try {
            stoppingEngine?.stop(gracePeriodMillis = 300L, timeoutMillis = 1_000L)
        } finally {
            synchronized(sessionLock) {
                connectionLeases.values.forEach { it.close() }
                connectionLeases.clear()
                sessions.values.forEach { it.slot.close() }
                sessions.clear()
            }
        }
    }

    companion object {
        const val MAX_AUTH_FRAME_BYTES = PsLinkProtocol.MAX_AUTH_FRAME_BYTES
        const val MAX_CONTROL_FRAME_BYTES = PsLinkProtocol.MAX_CONTROL_FRAME_BYTES
        const val MAX_HTTP_BODY_BYTES = 32 * 1024
        const val MAX_SESSIONS = 8
        const val MAX_CONNECTIONS = 16
        const val SESSION_IDLE_TIMEOUT_MS = 90_000L

        @Volatile
        private var activeServer: PhoneReceiverServer? = null

        /**
         * Publish a subtitle to currently connected phone-side TV receivers.
         * This does not replace TvSubtitleClient's Android TV delivery.
         */
        fun publishToReceivers(packet: SubtitlePacket): Boolean {
            return activeServer?.publishSubtitle(packet) ?: false
        }

        /**
         * Broadcast live OCR spatial captions to phone-side WebSocket receivers and Roku history.
         */
        fun publishCaptionsToReceivers(
            lines: List<LiveGameTranslator.CaptionLine>,
            timestampNs: Long
        ): Boolean {
            return activeServer?.publishCaptions(lines, timestampNs) ?: false
        }

        /** Prefer Wi-Fi IPv4; return null rather than advertising loopback. */
        fun localIpv4Address(): String? {
            return runCatching {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                val candidates = interfaces.filter { network ->
                    runCatching { network.isUp && !network.isLoopback && !network.isVirtual }.getOrDefault(false)
                }.flatMap { network ->
                    Collections.list(network.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        .map { address ->
                            val name = network.name.lowercase()
                            val rank = if (name.contains("wlan") || name.contains("wifi") || name.contains("ap")) 0 else 1
                            rank to address.hostAddress
                        }
                }
                candidates.sortedBy { it.first }.firstOrNull()?.second
            }.getOrNull()
        }
    }
}
