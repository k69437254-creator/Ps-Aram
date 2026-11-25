// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import com.metallic.chiaki.BuildConfig
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pslink.mobile.TvSubtitleClient
import com.pslink.core.PairingCode
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-consented screen-to-TV H.264 streaming.
 * This captures the Android display; it does not capture internal game audio.
 */
class TvScreenStreamService : Service() {

    companion object {
        const val ACTION_START = "com.pslink.mobile.stream.START"
        const val ACTION_STOP = "com.pslink.mobile.stream.STOP"
        const val EXTRA_RESULT_CODE = "stream_projection_result_code"
        const val EXTRA_RESULT_DATA = "stream_projection_result_data"
        const val EXTRA_PHONE_PAIR_CODE = "stream_phone_pairing_code"

        @Volatile var mediaSink: TvSubtitleClient? = null
        @Volatile var statusListener: ((String) -> Unit)? = null
        @Volatile var isStreaming: Boolean = false
            private set

        private const val CHANNEL_ID = "pslink_screen_stream"
        private const val NOTIFICATION_ID = 5104
    }

    private val running = AtomicBoolean(false)
    private val encoderLock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderThread: Thread? = null
    private var phoneReceiverServer: PhoneReceiverServer? = null
    private var hlsPublisher: HlsTransportStreamPublisher? = null
    private var audioCapture: AacPlaybackCapture? = null
    private var stopping = false
    private var streamWidth = 0
    private var streamHeight = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopStream(stopProjection = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStream(intent, startId)
            ACTION_STOP -> stopStream()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun report(message: String) {
        statusListener?.invoke(message)
    }

    private fun startStream(intent: Intent, startId: Int) {
        if (running.get()) return

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val sink = mediaSink
        val phonePairingCode = PairingCode.normalize(
            intent.getStringExtra(EXTRA_PHONE_PAIR_CODE).orEmpty()
        )
        if (sink == null && phonePairingCode == null) {
            report("لم يتم إعداد مستقبل تلفاز مقترن أو رمز استقبال الهاتف.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        if (resultCode == 0 || data == null) {
            report("لم تصل موافقة مشاركة الشاشة من Android.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection غير متاح")

            projection = activeProjection
            activeProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val scale = minOf(
                1f,
                1920f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
            )
            streamWidth = ((metrics.widthPixels * scale).toInt() and -2).coerceAtLeast(320)
            streamHeight = ((metrics.heightPixels * scale).toInt() and -2).coerceAtLeast(240)

            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                streamWidth,
                streamHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfoColorFormat.SURFACE)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29) {
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            display = activeProjection.createVirtualDisplay(
                "PS Link TV Screen Stream",
                streamWidth,
                streamHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            ) ?: throw IllegalStateException("تعذر إنشاء شاشة البث الافتراضية")

            if (phonePairingCode != null) {
                val hlsDirectory = File(cacheDir, "pslink-hls")
                val receiver = PhoneReceiverServer(
                    phonePairingCode,
                    hlsDirectory,
                    BuildConfig.PSLINK_ALLOWED_ORIGINS
                )
                phoneReceiverServer = receiver
                val publisher = HlsTransportStreamPublisher(hlsDirectory) {
                    receiver.notifyStreamAvailable()
                }
                publisher.start()
                hlsPublisher = publisher
                receiver.start()

                if (Build.VERSION.SDK_INT >= 29) {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        try {
                            val capture = AacPlaybackCapture(
                                activeProjection,
                                { frame, ptsUs ->
                                    try {
                                        publisher.writeAudioAccessUnit(frame, ptsUs)
                                    } catch (audioError: Exception) {
                                        report(
                                            "تعذر نشر إطار AAC: " +
                                                (audioError.localizedMessage ?: "خطأ غير معروف")
                                        )
                                    }
                                },
                                { message ->
                                    report("تعذر التقاط صوت التطبيقات: $message")
                                }
                            )
                            capture.start()
                            audioCapture = capture
                            report("تم تشغيل التقاط صوت التطبيقات وترميز AAC-LC.")
                        } catch (audioError: Exception) {
                            report(
                                "سيستمر بث الفيديو دون صوت: " +
                                    (audioError.localizedMessage ?: "تعذر بدء التقاط الصوت")
                            )
                        }
                    } else {
                        report(
                            "سيستمر بث الفيديو دون صوت: إذن RECORD_AUDIO غير ممنوح."
                        )
                    }
                } else {
                    report("التقاط صوت التطبيقات يتطلب Android 10 أو أحدث.")
                }
            }

            sink?.configureMediaStream(streamWidth, streamHeight, 0, 30)
            running.set(true)
            isStreaming = true
            stopping = false

            encoderThread = Thread({ drainEncoder(codec, sink) }, "PSLink-H264-Encoder").apply {
                isDaemon = true
                start()
            }

            report("بث الشاشة يعمل الآن بدقة ${streamWidth}×${streamHeight} عبر H.264.")
        } catch (error: Exception) {
            report("فشل بدء بث الشاشة: ${error.localizedMessage ?: "خطأ MediaProjection أو MediaCodec"}")
            stopStream()
        }
    }

    private fun drainEncoder(codec: MediaCodec, sink: TvSubtitleClient?) {
        val info = MediaCodec.BufferInfo()
        var codecConfig = ByteArray(0)

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                synchronized(encoderLock) {
                    if (!running.get() || Thread.currentThread().isInterrupted) {
                        return
                    }

                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            codecConfig = combineCodecConfig(codec.outputFormat)
                            report("تم تجهيز مشفّر H.264؛ جارٍ إرسال إطارات الشاشة.")
                        }

                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                        else -> if (index >= 0) {
                            try {
                                val output = codec.getOutputBuffer(index)
                                if (
                                    output != null &&
                                    info.size > 0 &&
                                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    require(info.offset >= 0 && info.size >= 0 &&
                                        info.offset.toLong() + info.size <= output.capacity().toLong()) {
                                        "Invalid H.264 output buffer range"
                                    }
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val encoded = ByteArray(info.size)
                                    output.get(encoded)

                                    val keyFrame =
                                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    val packet = if (keyFrame && codecConfig.isNotEmpty()) {
                                        codecConfig + encoded
                                    } else {
                                        encoded
                                    }

                                    sink?.sendVideoSample(
                                        packet,
                                        System.nanoTime().coerceAtLeast(1L),
                                        keyFrame
                                    )

                                    try {
                                        hlsPublisher?.writeAccessUnit(
                                            packet,
                                            info.presentationTimeUs.coerceAtLeast(0L),
                                            keyFrame
                                        )
                                    } catch (hlsError: Exception) {
                                        report(
                                            "توقف نشر HLS: " +
                                                (hlsError.localizedMessage ?: "خطأ في حزمة H.264")
                                        )
                                        runCatching { hlsPublisher?.stop() }
                                        hlsPublisher = null
                                    }
                                }
                            } finally {
                                codec.releaseOutputBuffer(index, false)
                            }
                        } else {
                            throw IllegalStateException(
                                "Unexpected MediaCodec output status: $index"
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                report(
                    "توقف ترميز بث الشاشة: ${error.localizedMessage ?: "خطأ H.264"}"
                )
            }
        }
    }

    private fun combineCodecConfig(format: MediaFormat): ByteArray {
        val parts = ArrayList<ByteArray>()
        for (key in listOf("csd-0", "csd-1")) {
            if (!format.containsKey(key)) continue
            val buffer = format.getByteBuffer(key) ?: continue
            val copy = buffer.duplicate()
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            if (bytes.isNotEmpty()) parts.add(bytes)
        }
        return parts.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
    }

    private fun stopStream(stopProjection: Boolean = true) {
        if (stopping) return
        stopping = true
        running.set(false)
        isStreaming = false

        val oldThread = encoderThread
        encoderThread = null
        oldThread?.interrupt()
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            try {
                oldThread.join(2_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        runCatching { display?.release() }
        display = null

        // Serialize codec teardown with any in-flight encoder operation.
        synchronized(encoderLock) {
            val oldEncoder = encoder
            encoder = null
            if (oldEncoder != null) {
                runCatching { oldEncoder.signalEndOfInputStream() }
                runCatching { oldEncoder.stop() }
                runCatching { oldEncoder.release() }
            }
        }

        val oldProjection = projection
        projection = null
        if (stopProjection) runCatching { oldProjection?.stop() }

        runCatching { audioCapture?.stop() }
        audioCapture = null

        runCatching { hlsPublisher?.stop() }
        hlsPublisher = null
        runCatching { phoneReceiverServer?.stop() }
        phoneReceiverServer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        report("تم إيقاف بث الشاشة.")
        stopping = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PS Link screen streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TvScreenStreamService::class.java)
            .setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPending = PendingIntent.getService(this, 5105, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PS Link يبث الشاشة")
            .setContentText("بث الشاشة إلى التلفاز نشط")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف البث", stopPending)
            .build()
    }

    override fun onDestroy() {
        if (running.get() || projection != null || encoder != null) stopStream()
        super.onDestroy()
    }

    private object MediaCodecInfoColorFormat {
        const val SURFACE = 0x7F000789
    }
}
