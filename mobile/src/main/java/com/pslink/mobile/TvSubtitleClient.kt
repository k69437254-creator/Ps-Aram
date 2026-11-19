// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import com.pslink.core.PsLinkProtocol
import com.pslink.core.SubtitlePacket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** LAN-only WebSocket client for the paired PS Link TV receiver. */
class TvSubtitleClient(
    private val onStatus: (String, Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false

    fun connect(hostInput: String, pairingCode: String) {
        disconnect()
        val host = hostInput.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
        if (host.isBlank() || host.contains('/') || host.contains(' ')) {
            onStatus("أدخل عنوان IP صحيحًا للتلفاز", false)
            return
        }
        if (!pairingCode.matches(Regex("\\d{6}"))) {
            onStatus("رمز الاقتران يجب أن يتكون من 6 أرقام", false)
            return
        }

        val request = Request.Builder()
            .url("ws://$host:${PsLinkProtocol.PORT}${PsLinkProtocol.PATH}")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sent = webSocket.send(PsLinkProtocol.auth(pairingCode))
                if (!sent) onStatus("تعذر إرسال رمز الاقتران", false)
                else onStatus("تم الاتصال؛ جارٍ التحقق من الرمز…", false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val kind = runCatching { JSONObject(text).optString("kind") }.getOrDefault("")
                if (kind == PsLinkProtocol.KIND_READY) {
                    ready = true
                    onStatus("التلفاز متصل وجاهز لاستقبال الترجمة", true)
                } else if (kind == "error") {
                    ready = false
                    onStatus("رفض التلفاز الاقتران: ${runCatching { JSONObject(text).optString("message") }.getOrDefault("خطأ غير معروف")}", false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                onStatus("فشل الاتصال بالتلفاز: ${t.localizedMessage ?: "تحقق من الشبكة"}", false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                onStatus("انقطع اتصال التلفاز", false)
            }
        })
    }

    fun send(packet: SubtitlePacket): Boolean {
        if (!ready) {
            onStatus("اربط التلفاز أولًا", false)
            return false
        }
        val sent = socket?.send(packet.toJson()) == true
        if (!sent) onStatus("تعذر إرسال الترجمة", false)
        return sent
    }

    fun disconnect() {
        ready = false
        socket?.close(1000, "User disconnected")
        socket = null
    }

    fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
