// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import com.pslink.core.PairingCode
import com.pslink.core.SubtitlePacket
import com.pslink.core.PsLinkProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-side PS Link receiver endpoint for Tizen/webOS clients.
 * WebSocket pairing and HLS HTTP files share the existing PS Link port.
 */
class PhoneReceiverServer(
    pairingCode: String,
    private val hlsDirectory: File
) {
    private val normalizedCode = requireNotNull(PairingCode.normalize(pairingCode)) {
        "Phone receiver pairing code must contain exactly six digits"
    }
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
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val streamAvailable = AtomicBoolean(false)
    private val subtitleHistoryLock = Any()
    private val subtitleHistory = mutableListOf<Pair<Long, String>>()
    private var subtitleSequence = 0L
    private var engine: ApplicationEngine? = null
    private var serverScope: CoroutineScope? = null

    @Synchronized fun start() {
        if (engine != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        try {
            engine = embeddedServer(CIO, host = "0.0.0.0", port = PsLinkProtocol.PORT) {
                install(WebSockets)
                routing {
                    post("/receiver/roku/stream-url") {
                        val request = runCatching {
                            JSONObject(call.receiveText())
                        }.getOrNull()

                        if (request == null) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val suppliedCode = PairingCode.normalize(
                            request.optString("code")
                        )

                        if (suppliedCode == null || suppliedCode != normalizedCode) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }

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
                        val request = runCatching {
                            JSONObject(call.receiveText())
                        }.getOrNull()

                        if (request == null) {
                            call.respond(HttpStatusCode.BadRequest)
                            return@post
                        }

                        val suppliedCode = PairingCode.normalize(
                            request.optString("code")
                        )

                        if (suppliedCode == null || suppliedCode != normalizedCode) {
                            call.respond(HttpStatusCode.Unauthorized)
                            return@post
                        }

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
                                "no-cache, no-store, must-revalidate"
                            )
                            call.respondBytes(
                                playlist.readBytes(),
                                ContentType.parse("application/vnd.apple.mpegurl")
                            )
                        }
                    }
                    get("/hls/{token}/{name}") {
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
                            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                            call.respondBytes(segment.readBytes(), ContentType.parse("video/mp2t"))
                        }
                    }
                    webSocket(PsLinkProtocol.PATH) {
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

                        sessions.add(this)
                        try {
                            send(Frame.Text(JSONObject()
                                .put("v", PsLinkProtocol.VERSION)
                                .put("kind", PsLinkProtocol.KIND_READY)
                                .toString()))
                            if (streamAvailable.get()) sendStreamUrl(this)
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Close -> break
                                    is Frame.Text -> {
                                        val message = runCatching {
                                            JSONObject(frame.readText())
                                        }.getOrNull()
                                        if (message?.optInt("v", -1) == PsLinkProtocol.VERSION &&
                                            message.optString("kind") == PsLinkProtocol.KIND_HEARTBEAT) {
                                            send(Frame.Text(JSONObject()
                                                .put("v", PsLinkProtocol.VERSION)
                                                .put("kind", PsLinkProtocol.KIND_HEARTBEAT_ACK)
                                                .toString()))
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        } finally {
                            sessions.remove(this)
                        }
                    }
                }
            }.start(wait = false)
            activeServer = this
        } catch (error: Exception) {
            engine = null
            scope.cancel()
            serverScope = null
            throw error
        }
    }

    private suspend fun DefaultWebSocketServerSession.reject(code: String, message: String) {
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

    /**
     * Broadcast one translated subtitle packet to all authenticated TV receivers.
     * Returns false when the receiver server is stopped; true means the packet
     * was retained in history, not that a TV has displayed it.
     */
    private fun publishSubtitle(packet: SubtitlePacket): Boolean {
        val scope = serverScope ?: return false
        val payload = packet.toJson()

        // Retain a bounded history for independent Roku polling cursors.
        synchronized(subtitleHistoryLock) {
            subtitleSequence += 1L
            subtitleHistory.add(subtitleSequence to payload)

            while (subtitleHistory.size > 100) {
                subtitleHistory.removeAt(0)
            }
        }

        // WebSocket receivers receive the same packet immediately.
        val targets = sessions.toList()

        if (targets.isNotEmpty()) {
            scope.launch {
                targets.forEach { session ->
                    runCatching {
                        session.send(Frame.Text(payload))
                    }
                }
            }
        }

        // True means accepted into the receiver history, not confirmed displayed.
        return true
    }

    /** Called after the first complete IDR-aligned HLS segment is published. */
    fun notifyStreamAvailable() {
        streamAvailable.set(true)
        val scope = serverScope ?: return
        scope.launch {
            val messageReady = streamUrlMessage() ?: return@launch
            sessions.toList().forEach { session ->
                runCatching { session.send(Frame.Text(messageReady)) }
            }
        }
    }

    private suspend fun sendStreamUrl(session: DefaultWebSocketServerSession) {
        val message = streamUrlMessage() ?: return
        session.send(Frame.Text(message))
    }

    private fun isValidStreamToken(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.US_ASCII),
            streamToken.toByteArray(Charsets.US_ASCII)
        )
    }

    private fun streamUrlMessage(): String? {
        val address = localIpv4Address() ?: return null
        return JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "stream_url")
            .put("url", "http://$address:${PsLinkProtocol.PORT}/hls/$streamToken/index.m3u8")
            .toString()
    }

    @Synchronized fun stop() {
        if (activeServer === this) activeServer = null
        sessions.clear()
        runCatching { engine?.stop(gracePeriodMillis = 300L, timeoutMillis = 1_000L) }
        engine = null
        serverScope?.cancel()
        serverScope = null
        streamAvailable.set(false)
        synchronized(subtitleHistoryLock) {
            subtitleHistory.clear()
            subtitleSequence = 0L
        }
    }

    companion object {
        @Volatile
        private var activeServer: PhoneReceiverServer? = null

        /**
         * Publish a subtitle to currently connected phone-side TV receivers.
         * This does not replace TvSubtitleClient's Android TV delivery.
         */
        fun publishToReceivers(packet: SubtitlePacket): Boolean {
            return activeServer?.publishSubtitle(packet) ?: false
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
