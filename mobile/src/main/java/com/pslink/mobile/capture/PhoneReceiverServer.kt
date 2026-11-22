// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import com.pslink.core.PairingCode
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
    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val streamAvailable = AtomicBoolean(false)
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

                    get("/hls/index.m3u8") {
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
                    get("/hls/{name}") {
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
                                if (frame is Frame.Close) break
                            }
                        } finally {
                            sessions.remove(this)
                        }
                    }
                }
            }.start(wait = false)
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

    private fun streamUrlMessage(): String? {
        val address = localIpv4Address() ?: return null
        return JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "stream_url")
            .put("url", "http://$address:${PsLinkProtocol.PORT}/hls/index.m3u8")
            .toString()
    }

    @Synchronized fun stop() {
        sessions.clear()
        runCatching { engine?.stop(gracePeriodMillis = 300L, timeoutMillis = 1_000L) }
        engine = null
        serverScope?.cancel()
        serverScope = null
        streamAvailable.set(false)
    }

    companion object {
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
