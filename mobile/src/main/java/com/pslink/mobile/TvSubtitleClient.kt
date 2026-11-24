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
import java.util.concurrent.atomic.AtomicLong

/**
 * LAN-only paired WebSocket: text control/captions plus bounded binary game media.
 *
 * Improvements over the baseline:
 * - Automatic reconnection with capped exponential backoff.
 * - Outbound message queue for transient disconnects.
 * - Explicit close/reconnect lifecycle instead of fire-and-forget.
 *
 * Note: Only ws:// is supported. wss:// requires SSL/TLS configuration
 * (certificates, hostname verification) that is not part of this local-network
 * protocol.
 */
class TvSubtitleClient(
    private val onStatus: (String, Boolean) -> Unit
) {
    private data class QueuedMedia(
        val generation: Long,
        val socket: WebSocket,
        val packet: ByteArray
    )

    private enum class MediaEnqueueResult { ENQUEUED, DROPPED, OVERFLOW }

    companion object {
        private const val MEDIA_MAGIC = 0x50534C4D // PSLM
        private const val MEDIA_VERSION = 1
        private const val MEDIA_HEADER_BYTES = 28
        private const val MEDIA_VIDEO = 1
        private const val MEDIA_AUDIO_PCM16 = 2
        private const val FLAG_KEY_FRAME = 1
        private const val MAX_MEDIA_PACKET = 2 * 1024 * 1024
        private const val MAX_SOCKET_QUEUE_BYTES = 512L * 1024L
        private val MEDIA_CONFIG_SEQUENCE = AtomicLong(0L)

        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val MAX_PENDING_MESSAGE_IDS = 64
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var mediaConfigJson: String? = null
    @Volatile private var pendingMediaConfigId: String? = null
    // PSLINK_MEDIA_CONFIG_ACK_V1
    @Volatile private var mediaReady = false
    @Volatile private var terminalStatus: String? = null
    private val handshakeHandler = Handler(Looper.getMainLooper())
    private val handshakeLock = Any()
    @Volatile private var handshakeTimeoutSocket: WebSocket? = null
    private var handshakeTimeoutRunnable: Runnable? = null
    private var awaitingKeyFrame = true
    private val pendingMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mediaQueue = ArrayBlockingQueue<QueuedMedia>(32)
    private val outboundQueue = ArrayBlockingQueue<String>(128)

    // Reconnection state
    private val reconnectLock = Any()
    // Orders media sends against transitions without holding mediaStateLock during send().
    private val mediaSendLock = Any()
    private val mediaStateLock = java.lang.Object()
    private var mediaGeneration = 0L
    @Volatile private var reconnectEnabled = false
    @Volatile private var lastHost: String? = null
    @Volatile private var lastPairingCode: String? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var reconnectHandler: Handler? = null
    private var reconnectRunnable: Runnable? = null

    private val mediaWorker = Thread({
        while (!Thread.currentThread().isInterrupted) {
            val queued = takeMediaPacket() ?: break
            val current = queued.socket
            if (current.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
                if (resetMediaQueueIfCurrent(queued.generation)) {
                    onStatus("ازدحام في اتصال التلفاز؛ أُسقطت حزم الوسائط حتى إطار الفيديو المفتاحي التالي", false)
                }
                continue
            }
            val sent = synchronized(mediaSendLock) {
                val stillCurrent = synchronized(mediaStateLock) {
                    queued.generation == mediaGeneration &&
                        socket === current && ready && mediaReady
                }
                if (stillCurrent) current.send(ByteString.of(*queued.packet)) else null
            }
            if (sent == false) {
                resetMediaQueueIfCurrent(queued.generation)
                onStatus("تعذر إرسال بث اللعبة إلى التلفاز", false)
            }
        }
    }, "PSLink-TV-Media-Sender").apply { isDaemon = true; start() }

    private val outboundWorker = Thread({
        while (!Thread.currentThread().isInterrupted) {
            val text = try { outboundQueue.take() } catch (_: InterruptedException) { break }
            val current = socket
            if (current == null || !ready) continue
            if (!current.send(text)) {
                // Re-queue once; if still failing, drop to avoid unbounded growth.
                if (!outboundQueue.offer(text)) {
                    onStatus("تعذر إرسال رسالة؛ تم إسقاط الرسالة القديمة", false)
                }
            }
        }
    }, "PSLink-TV-Outbound-Sender").apply { isDaemon = true; start() }

    private fun takeMediaPacket(): QueuedMedia? = synchronized(mediaStateLock) {
        while (!Thread.currentThread().isInterrupted) {
            mediaQueue.poll()?.let { return@synchronized it }
            try {
                mediaStateLock.wait()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized null
            }
        }
        null
    }

    private fun resetMediaQueueLocked() {
        mediaQueue.clear()
        mediaGeneration++
        awaitingKeyFrame = true
        mediaStateLock.notifyAll()
    }

    private fun resetMediaQueue() {
        synchronized(mediaSendLock) {
            synchronized(mediaStateLock) { resetMediaQueueLocked() }
        }
    }

    private fun resetMediaQueueIfCurrent(generation: Long): Boolean =
        synchronized(mediaSendLock) {
            synchronized(mediaStateLock) {
                if (generation != mediaGeneration) {
                    false
                } else {
                    resetMediaQueueLocked()
                    true
                }
            }
        }

    private fun mediaConnection(): Pair<WebSocket, Long>? = synchronized(mediaStateLock) {
        val current = socket
        if (ready && mediaReady && current != null) current to mediaGeneration else null
    }

    private fun enqueueMediaPacket(
        connection: Pair<WebSocket, Long>,
        packet: ByteArray,
        keyFrame: Boolean
    ): MediaEnqueueResult = synchronized(mediaSendLock) {
        synchronized(mediaStateLock) {
            val (current, generation) = connection
            when {
                socket !== current || generation != mediaGeneration || !ready || !mediaReady ->
                    MediaEnqueueResult.DROPPED
                // Drop audio and dependent video until a keyframe can restore decoder and A/V sync.
                awaitingKeyFrame && !keyFrame -> MediaEnqueueResult.DROPPED
                !mediaQueue.offer(QueuedMedia(generation, current, packet)) -> {
                    resetMediaQueueLocked()
                    if (keyFrame) {
                        mediaQueue.offer(QueuedMedia(mediaGeneration, current, packet))
                        awaitingKeyFrame = false
                    }
                    mediaStateLock.notifyAll()
                    MediaEnqueueResult.OVERFLOW
                }
                else -> {
                    if (keyFrame) awaitingKeyFrame = false
                    mediaStateLock.notifyAll()
                    MediaEnqueueResult.ENQUEUED
                }
            }
        }
    }

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
            val expired = synchronized(mediaSendLock) {
                synchronized(handshakeLock) {
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
                        synchronized(mediaStateLock) {
                            ready = false
                            mediaReady = false
                            pendingMessageIds.clear()
                            socket = null
                            resetMediaQueueLocked()
                        }
                        true
                    }
                }
            }
            if (expired) {
                onStatus("انتهت مهلة التحقق من رمز الاقتران؛ إعادة المحاولة تلقائياً", false)
                scheduleReconnect(webSocket)
            }
        }

        synchronized(handshakeLock) {
            if (socket === webSocket && !ready && terminalStatus == null) {
                handshakeTimeoutRunnable?.let {
                    handshakeHandler.removeCallbacks(it)
                }
                handshakeTimeoutSocket = webSocket
                handshakeTimeoutRunnable = timeout
                handshakeHandler.postDelayed(timeout, HANDSHAKE_TIMEOUT_MS)
            }
        }
    }

    private fun scheduleReconnect(failedSocket: WebSocket? = null) {
        synchronized(reconnectLock) {
            if (!reconnectEnabled) return
            if (failedSocket != null && socket != null && socket !== failedSocket) return
            cancelReconnectLocked()
            backoffMs = minOf(MAX_BACKOFF_MS, backoffMs * 2)
            val runnable = Runnable {
                synchronized(reconnectLock) {
                    reconnectRunnable = null
                }
                if (lastHost != null && lastPairingCode != null) {
                    onStatus("إعادة الاتصال بالتلفاز بعد ${backoffMs / 1000} ثانية…", false)
                    connect(lastHost!!, lastPairingCode!!)
                }
            }
            synchronized(reconnectLock) {
                reconnectRunnable = runnable
            }
            (reconnectHandler ?: Handler(Looper.getMainLooper())).also { reconnectHandler = it }
                .postDelayed(runnable, backoffMs)
        }
    }

    private fun cancelReconnectLocked() {
        reconnectRunnable?.let {
            (reconnectHandler ?: Handler(Looper.getMainLooper())).removeCallbacks(it)
        }
        reconnectRunnable = null
        backoffMs = INITIAL_BACKOFF_MS
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

        // Remember for reconnection
        synchronized(reconnectLock) {
            lastHost = host
            lastPairingCode = pairingCode
            reconnectEnabled = true
            cancelReconnectLocked()
            backoffMs = INITIAL_BACKOFF_MS
        }

        val url = "ws://$host:${PsLinkProtocol.PORT}${PsLinkProtocol.PATH}"
        val request = runCatching {
            Request.Builder().url(url).build()
        }.getOrElse {
            onStatus("عنوان التلفاز غير صالح؛ أدخل عنوان IP أو اسم مضيف صحيحًا", false)
            return
        }

        synchronized(mediaSendLock) {
            val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val isCurrent = synchronized(mediaSendLock) {
                    synchronized(mediaStateLock) { socket === webSocket }
                }
                if (!isCurrent) { webSocket.close(1000, "Superseded connection"); return }
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
                val isCurrent = synchronized(mediaSendLock) {
                    synchronized(mediaStateLock) { socket === webSocket }
                }
                if (!isCurrent) return
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("kind")) {
                    PsLinkProtocol.KIND_READY -> {
                        val (accepted, configSent) = synchronized(mediaSendLock) {
                            val accepted = synchronized(handshakeLock) {
                                if (socket !== webSocket) {
                                    false
                                } else {
                                    cancelHandshakeTimeoutLocked(webSocket)
                                    synchronized(mediaStateLock) {
                                        ready = true
                                        mediaReady = false
                                        resetMediaQueueLocked()
                                    }
                                    true
                                }
                            }
                            val config = if (accepted) {
                                synchronized(mediaStateLock) { mediaConfigJson }
                            } else {
                                null
                            }
                            accepted to (config == null || webSocket.send(config))
                        }
                        if (!accepted) return
                        // Reset backoff on successful handshake
                        synchronized(reconnectLock) { backoffMs = INITIAL_BACKOFF_MS }
                        if (!configSent) {
                            onStatus("تعذر إرسال إعدادات بث الوسائط إلى التلفاز", false)
                        }
                        onStatus("تم اقتران التلفاز؛ بانتظار تأكيد إعداد بث الوسائط", true)
                    }
                    "media_config_ack" -> {
                        val ackId = json.optString("requestId", "")
                        val accepted = synchronized(mediaSendLock) {
                            synchronized(mediaStateLock) {
                                if (socket === webSocket &&
                                    ackId.isNotBlank() &&
                                    ackId == pendingMediaConfigId
                                ) {
                                    mediaReady = true
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        if (accepted) {
                            onStatus("أكد التلفاز قبول إعدادات بث اللعبة؛ مسار الوسائط جاهز", true)
                        }
                    }
                    PsLinkProtocol.KIND_ACK -> {
                        val ackId = json.optString("id", "")
                        val accepted = synchronized(mediaSendLock) {
                            synchronized(mediaStateLock) {
                                socket === webSocket &&
                                    ackId.isNotBlank() &&
                                    pendingMessageIds.remove(ackId)
                            }
                        }
                        if (accepted) {
                            onStatus("استلم التلفاز الترجمة", ready)
                        }
                    }
                    "error" -> {
                        val accepted = synchronized(mediaSendLock) {
                            synchronized(handshakeLock) {
                                if (socket !== webSocket) {
                                    false
                                } else {
                                    cancelHandshakeTimeoutLocked(webSocket)
                                    synchronized(mediaStateLock) {
                                        ready = false
                                        mediaReady = false
                                        resetMediaQueueLocked()
                                    }
                                    terminalStatus = "رفض التلفاز الاقتران [${json.optString("errorCode", "UNKNOWN_AUTH_ERROR")}]: ${json.optString("message", "خطأ غير معروف")}"
                                    true
                                }
                            }
                        }
                        if (!accepted) return
                        pendingMessageIds.clear()
                        onStatus(terminalStatus!!, false)
                        scheduleReconnect(webSocket)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failureOutcome = synchronized(mediaSendLock) {
                    synchronized(handshakeLock) {
                        if (socket !== webSocket) {
                            false to false
                        } else {
                            cancelHandshakeTimeoutLocked(webSocket)
                            synchronized(mediaStateLock) {
                                socket = null
                                ready = false
                                mediaReady = false
                                pendingMessageIds.clear()
                                resetMediaQueueLocked()
                            }
                            val finalStatus = terminalStatus
                            terminalStatus = null
                            true to (finalStatus == null)
                        }
                    }
                }
                if (failureOutcome.second) {
                    onStatus("فشل الاتصال بالتلفاز: ${t.localizedMessage ?: "تحقق من الشبكة"}", false)
                }
                scheduleReconnect(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val disconnectOutcome = synchronized(mediaSendLock) {
                    synchronized(handshakeLock) {
                        if (socket !== webSocket) {
                            false to false
                        } else {
                            cancelHandshakeTimeoutLocked(webSocket)
                            synchronized(mediaStateLock) {
                                socket = null
                                ready = false
                                mediaReady = false
                                pendingMessageIds.clear()
                                resetMediaQueueLocked()
                            }
                            val finalStatus = terminalStatus
                            terminalStatus = null
                            true to (finalStatus == null)
                        }
                    }
                }
                if (disconnectOutcome.second) {
                    onStatus("انقطع اتصال التلفاز", false)
                    scheduleReconnect(webSocket)
                }
            }
            })
            synchronized(mediaStateLock) {
                socket = newSocket
                ready = false
                mediaReady = false
                resetMediaQueueLocked()
            }
        }
    }

    fun configureMediaStream(width: Int, height: Int, codec: Int, fps: Int) {
        if (width !in 320..7680 || height !in 240..4320 || codec !in 0..2 || fps !in 15..120) {
            onStatus("إعدادات بث الفيديو غير صالحة", false)
            return
        }
        val requestId = "${System.nanoTime()}-${MEDIA_CONFIG_SEQUENCE.incrementAndGet()}"
        val config = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "media_config")
            .put("requestId", requestId)
            .put("width", width)
            .put("height", height)
            .put("codec", codec)
            .put("fps", fps)
            .toString()

        val sendResult = synchronized(mediaSendLock) {
            val current = synchronized(mediaStateLock) {
                mediaConfigJson = config
                pendingMediaConfigId = requestId
                mediaReady = false
                resetMediaQueueLocked()
                socket.takeIf { ready }
            }
            current?.send(config)
        }

        if (sendResult == false) {
            onStatus("تعذر إرسال إعدادات بث الوسائط؛ أعد المحاولة", false)
        }
    }

    fun sendVideoSample(data: ByteArray, ptsNs: Long, keyFrame: Boolean) {
        if (!ready || !mediaReady) return
        if (data.isEmpty() || ptsNs <= 0L) return
        if (data.size > MAX_MEDIA_PACKET) {
            resetMediaQueue()
            onStatus("إطار فيديو أكبر من الحد المسموح؛ جارٍ انتظار إطار مفتاح جديد", ready)
            return
        }
        val connection = mediaConnection() ?: return
        val packet = mediaPacket(MEDIA_VIDEO, if (keyFrame) FLAG_KEY_FRAME else 0, ptsNs, 0, 0, data)
        if (enqueueMediaPacket(connection, packet, keyFrame) == MediaEnqueueResult.OVERFLOW) {
            val message = if (keyFrame) {
                "امتلأ طابور الوسائط؛ أُسقطت الحزم القديمة وتبدأ الاستعادة من الإطار المفتاحي الحالي"
            } else {
                "امتلأ طابور الوسائط؛ أُسقطت الحزم القديمة وانتظار إطار مفتاح جديد لاستعادة المزامنة"
            }
            onStatus(message, ready)
        }
    }

    fun sendAudioSample(data: ByteArray, sampleRate: Int, channels: Int, ptsNs: Long) {
        if (!ready || !mediaReady || data.isEmpty() || ptsNs <= 0L || data.size > 256 * 1024 || sampleRate !in 8000..192000 || channels !in 1..2) return
        if (data.size % (channels * 2) != 0) return
        val connection = mediaConnection() ?: return
        val packet = mediaPacket(MEDIA_AUDIO_PCM16, 0, ptsNs, sampleRate, channels, data)
        if (enqueueMediaPacket(connection, packet, keyFrame = false) == MediaEnqueueResult.OVERFLOW) {
            onStatus("امتلأ طابور الوسائط؛ أُسقط الصوت والفيديو المنتظران حتى إطار الفيديو المفتاحي التالي", ready)
        }
    }

    fun sendCaptions(lines: List<LiveGameTranslator.CaptionLine>, ptsNs: Long, sessionId: String = ""): Boolean {
        if (!ready || ptsNs <= 0L) return false
        val array = JSONArray()
        lines.take(3).forEach { line ->
            array.put(JSONObject()
                .put("text", line.translated.take(1000))
                .put("left", line.left.coerceIn(0f, 1f).toDouble())
                .put("top", line.top.coerceIn(0f, 1f).toDouble())
                .put("right", line.right.coerceIn(0f, 1f).toDouble())
                .put("bottom", line.bottom.coerceIn(0f, 1f).toDouble()))
        }
        val payload = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "captions")
            .put("timestampNs", ptsNs)
            .put("sessionId", sessionId)
            .put("lines", array)
            .toString()
        val current = socket
        if (current == null || !ready) return false
        return current.send(payload)
    }

    fun send(packet: SubtitlePacket): Boolean {
        if (!ready) {
            onStatus("اربط التلفاز أولًا", false)
            return false
        }
        if (pendingMessageIds.size >= MAX_PENDING_MESSAGE_IDS) pendingMessageIds.clear()
        pendingMessageIds.add(packet.messageId)
        val json = packet.toJson()
        val current = socket
        if (current == null || !ready) {
            pendingMessageIds.remove(packet.messageId)
            onStatus("تعذر إرسال الترجمة", false)
            return false
        }
        val sent = current.send(json)
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
        synchronized(reconnectLock) {
            reconnectEnabled = false
            cancelReconnectLocked()
        }
        val previousSocket = synchronized(mediaSendLock) {
            synchronized(handshakeLock) {
                cancelHandshakeTimeoutLocked()
                terminalStatus = null
                synchronized(mediaStateLock) {
                    ready = false
                    mediaReady = false
                    pendingMessageIds.clear()
                    val previous = socket
                    socket = null
                    resetMediaQueueLocked()
                    previous
                }
            }
        }
        previousSocket?.close(1000, "User disconnected")
    }

    fun enableReconnect(enabled: Boolean) {
        synchronized(reconnectLock) {
            reconnectEnabled = enabled
            if (!enabled) cancelReconnectLocked()
        }
    }

    fun isConnected(): Boolean = socket != null && ready

    fun close() {
        disconnect()
        mediaWorker.interrupt()
        outboundWorker.interrupt()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
