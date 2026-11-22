// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.os.Handler
import android.os.Looper
import com.pslink.core.PsLinkProtocol
import com.pslink.core.PairingCode
import com.pslink.core.SubtitlePacket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** LAN-only paired WebSocket: text control/captions plus bounded binary game media. */
class TvSubtitleClient(
    private val onStatus: (String, Boolean) -> Unit
) {
    companion object {
        private const val MEDIA_MAGIC = 0x50534C4D // PSLM
        private const val MEDIA_VERSION = 1
        private const val MEDIA_HEADER_BYTES = 28
        private const val MEDIA_VIDEO = 1
        private const val MEDIA_AUDIO_PCM16 = 2
        private const val FLAG_KEY_FRAME = 1
        private const val MAX_MEDIA_PACKET = 2 * 1024 * 1024
        private const val MAX_SOCKET_QUEUE_BYTES = 512L * 1024L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var mediaConfigJson: String? = null
    @Volatile private var mediaReady = false
    @Volatile private var terminalStatus: String? = null
    private val handshakeHandler = Handler(Looper.getMainLooper())
    private val handshakeLock = Any()
    @Volatile private var handshakeTimeoutSocket: WebSocket? = null
    private var handshakeTimeoutRunnable: Runnable? = null
    @Volatile private var awaitingKeyFrame = true
    private val pendingMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mediaQueue = ArrayBlockingQueue<ByteArray>(32)

    private val mediaWorker = Thread({
        while (!Thread.currentThread().isInterrupted) {
            val packet = try { mediaQueue.take() } catch (_: InterruptedException) { break }
            val current = socket
            if (!ready || current == null) continue
            if (current.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
                mediaQueue.clear()
                awaitingKeyFrame = true
                continue
            }
            if (!current.send(ByteString.of(*packet))) {
                mediaQueue.clear()
                awaitingKeyFrame = true
                onStatus("تعذر إرسال بث اللعبة إلى التلفاز", false)
            }
        }
    }, "PSLink-TV-Media-Sender").apply { isDaemon = true; start() }

    private fun cancelHandshakeTimeoutLocked(expectedSocket: WebSocket? = null) {
        if (expectedSocket != null && handshakeTimeoutSocket !== expectedSocket) return
        val pending = handshakeTimeoutRunnable
        handshakeTimeoutRunnable = null
        handshakeTimeoutSocket = null
        if (pending != null) handshakeHandler.removeCallbacks(pending)
    }

    private fun startHandshakeTimeout(webSocket: WebSocket) {
        lateinit var timeout: Runnable
        timeout = Runnable {
            val expired = synchronized(handshakeLock) {
                if (socket !== webSocket ||
                    handshakeTimeoutSocket !== webSocket ||
                    handshakeTimeoutRunnable !== timeout ||
                    ready ||
                    terminalStatus != null
                ) {
                    false
                } else {
                    handshakeTimeoutRunnable = null
                    handshakeTimeoutSocket = null
                    ready = false
                    mediaReady = false
                    mediaQueue.clear()
                    pendingMessageIds.clear()
                    socket = null
                    true
                }
            }
            if (expired) {
                onStatus("انتهت مهلة التحقق من رمز الاقتران؛ أعد المحاولة", false)
                webSocket.cancel()
            }
        }

        synchronized(handshakeLock) {
            if (socket === webSocket && !ready && terminalStatus == null) {
                handshakeTimeoutRunnable?.let {
                    handshakeHandler.removeCallbacks(it)
                }
                handshakeTimeoutSocket = webSocket
                handshakeTimeoutRunnable = timeout
                handshakeHandler.postDelayed(timeout, 10_000L)
            }
        }
    }

    fun connect(hostInput: String, pairingCode: String) {
        val host = hostInput.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
        val normalizedCode = PairingCode.normalize(pairingCode)
        if (host.isBlank() || host.contains('/') || host.contains(' ')) {
            onStatus("أدخل عنوان IP صحيحًا للتلفاز", false)
            return
        }
        if (normalizedCode == null) {
            onStatus("رمز الاقتران يجب أن يتكون من 6 أرقام", false)
            return
        }
        disconnect()
        terminalStatus = null

        val request = runCatching {
            Request.Builder()
                .url("ws://$host:${PsLinkProtocol.PORT}${PsLinkProtocol.PATH}")
                .build()
        }.getOrElse {
            onStatus("عنوان التلفاز غير صالح؛ أدخل عنوان IP أو اسم مضيف صحيحًا", false)
            return
        }

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) { webSocket.close(1000, "Superseded connection"); return }
                val sent = webSocket.send(PsLinkProtocol.auth(normalizedCode))
                if (!sent) {
                    terminalStatus = "تعذر إرسال رمز الاقتران"
                    onStatus(terminalStatus!!, false)
                    webSocket.cancel()
                } else {
                    onStatus("تم الاتصال؛ جارٍ التحقق من الرمز…", false)
                    startHandshakeTimeout(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("kind")) {
                    PsLinkProtocol.KIND_READY -> {
                        val accepted = synchronized(handshakeLock) {
                            if (socket !== webSocket) {
                                false
                            } else {
                                cancelHandshakeTimeoutLocked(webSocket)
                                ready = true
                                true
                            }
                        }
                        if (!accepted) return
                        mediaReady = false
                        awaitingKeyFrame = true
                        mediaQueue.clear()
                        val config = mediaConfigJson
                        mediaReady = config != null && webSocket.send(config)
                        onStatus("تم اقتران التلفاز بنجاح؛ الترجمة جاهزة", true)
                    }
                    PsLinkProtocol.KIND_ACK -> {
                        val ackId = json.optString("id", "")
                        if (ackId.isNotBlank() && pendingMessageIds.remove(ackId))
                            onStatus("استلم التلفاز الترجمة", ready)
                    }
                    "error" -> {
                        val accepted = synchronized(handshakeLock) {
                            if (socket !== webSocket) {
                                false
                            } else {
                                cancelHandshakeTimeoutLocked(webSocket)
                                ready = false
                                mediaReady = false
                                terminalStatus = "رفض التلفاز الاقتران [${json.optString("errorCode", "UNKNOWN_AUTH_ERROR")}]: ${json.optString("message", "خطأ غير معروف")}"
                                true
                            }
                        }
                        if (!accepted) return
                        mediaQueue.clear()
                        pendingMessageIds.clear()
                        onStatus(terminalStatus!!, false)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val shouldReportFailure = synchronized(handshakeLock) {
                    if (socket !== webSocket) {
                        false
                    } else {
                        cancelHandshakeTimeoutLocked(webSocket)
                        socket = null
                        ready = false
                        mediaReady = false
                        pendingMessageIds.clear()
                        mediaQueue.clear()
                        val finalStatus = terminalStatus
                        terminalStatus = null
                        finalStatus == null
                    }
                }
                if (shouldReportFailure) {
                    onStatus("فشل الاتصال بالتلفاز: ${t.localizedMessage ?: "تحقق من الشبكة"}", false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val shouldReportDisconnect = synchronized(handshakeLock) {
                    if (socket !== webSocket) {
                        false
                    } else {
                        cancelHandshakeTimeoutLocked(webSocket)
                        socket = null
                        ready = false
                        mediaReady = false
                        pendingMessageIds.clear()
                        mediaQueue.clear()
                        val finalStatus = terminalStatus
                        terminalStatus = null
                        finalStatus == null
                    }
                }
                if (shouldReportDisconnect) onStatus("انقطع اتصال التلفاز", false)
            }
        })
    }

    fun configureMediaStream(width: Int, height: Int, codec: Int, fps: Int) {
        if (width !in 320..7680 || height !in 240..4320 || codec !in 0..2 || fps !in 15..120) {
            onStatus("إعدادات بث الفيديو غير صالحة", false)
            return
        }
        val config = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "media_config")
            .put("width", width)
            .put("height", height)
            .put("codec", codec)
            .put("fps", fps)
            .toString()
        mediaConfigJson = config
        mediaReady = false
        if (ready) mediaReady = socket?.send(config) == true
    }

    fun sendVideoSample(data: ByteArray, ptsNs: Long, keyFrame: Boolean) {
        if (!ready || !mediaReady) return
        if (data.isEmpty() || ptsNs <= 0L) return
        if (data.size > MAX_MEDIA_PACKET) {
            // A dropped access unit can break the H.264/H.265 reference chain.
            // Discard queued dependants and resume only from the next IDR/CRA.
            mediaQueue.clear()
            awaitingKeyFrame = true
            onStatus("إطار فيديو أكبر من الحد المسموح؛ جارٍ انتظار إطار مفتاح جديد", ready)
            return
        }
        if (awaitingKeyFrame && !keyFrame) return
        val packet = mediaPacket(MEDIA_VIDEO, if (keyFrame) FLAG_KEY_FRAME else 0, ptsNs, 0, 0, data)
        if (!mediaQueue.offer(packet)) {
            mediaQueue.clear()
            awaitingKeyFrame = true
            if (keyFrame && mediaQueue.offer(packet)) awaitingKeyFrame = false
            return
        }
        if (keyFrame) awaitingKeyFrame = false
    }

    fun sendAudioSample(data: ByteArray, sampleRate: Int, channels: Int, ptsNs: Long) {
        if (!ready || !mediaReady || data.isEmpty() || ptsNs <= 0L || data.size > 256 * 1024 || sampleRate !in 8000..192000 || channels !in 1..2) return
        if (data.size % (channels * 2) != 0) return
        val packet = mediaPacket(MEDIA_AUDIO_PCM16, 0, ptsNs, sampleRate, channels, data)
        // Audio is allowed to lose an occasional block under network pressure; it must
        // never block Chiaki's audio decoder thread.
        mediaQueue.offer(packet)
    }

    fun sendCaptions(lines: List<LiveGameTranslator.CaptionLine>, ptsNs: Long) {
        if (!ready || ptsNs <= 0L) return
        val array = JSONArray()
        lines.take(3).forEach { line ->
            array.put(JSONObject()
                .put("text", line.translated.take(1000))
                .put("left", line.left.coerceIn(0f, 1f).toDouble())
                .put("top", line.top.coerceIn(0f, 1f).toDouble())
                .put("right", line.right.coerceIn(0f, 1f).toDouble())
                .put("bottom", line.bottom.coerceIn(0f, 1f).toDouble()))
        }
        socket?.send(JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "captions")
            .put("timestampNs", ptsNs)
            .put("lines", array)
            .toString())
    }

    fun send(packet: SubtitlePacket): Boolean {
        if (!ready) {
            onStatus("اربط التلفاز أولًا", false)
            return false
        }
        if (pendingMessageIds.size >= 64) pendingMessageIds.clear()
        pendingMessageIds.add(packet.messageId)
        val sent = socket?.send(packet.toJson()) == true
        if (!sent) {
            pendingMessageIds.remove(packet.messageId)
            onStatus("تعذر إرسال الترجمة", false)
        }
        return sent
    }

    private fun mediaPacket(type: Int, flags: Int, ptsNs: Long, arg1: Int, arg2: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(MEDIA_HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MEDIA_MAGIC)
        buffer.put(MEDIA_VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putShort(flags.toShort())
        buffer.putLong(ptsNs)
        buffer.putInt(arg1)
        buffer.putInt(arg2)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun disconnect() {
        val previousSocket = synchronized(handshakeLock) {
            cancelHandshakeTimeoutLocked()
            ready = false
            mediaReady = false
            terminalStatus = null
            pendingMessageIds.clear()
            mediaQueue.clear()
            awaitingKeyFrame = true
            val previous = socket
            socket = null
            previous
        }
        previousSocket?.close(1000, "User disconnected")
    }

    fun close() {
        disconnect()
        mediaWorker.interrupt()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
