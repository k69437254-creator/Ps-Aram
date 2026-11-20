// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

/** User-consented Android screen recorder for the active PS Link Remote Play screen. */
class ScreenRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.pslink.mobile.capture.START"
        const val ACTION_STOP = "com.pslink.mobile.capture.STOP"
        const val ACTION_SAVED = "com.pslink.mobile.capture.SAVED"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_MEDIA_URI = "recorded_media_uri"
        private const val CHANNEL_ID = "pslink_recording"
        private const val NOTIFICATION_ID = 5102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var finishing = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { finishRecording() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishRecording()
            ACTION_START -> startRecording(intent)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (recorder != null) return
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                   else intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        if (resultCode == 0 || data == null) {
            fail("لم تصل موافقة مشاركة الشاشة من Android")
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection!!.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val scale = minOf(1f, 1920f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
            val width = ((metrics.widthPixels * scale).toInt() and -2).coerceAtLeast(2)
            val height = ((metrics.heightPixels * scale).toInt() and -2).coerceAtLeast(2)
            val density = metrics.densityDpi
            val file = File(cacheDir, "pslink_record_${System.currentTimeMillis()}.mp4")
            outputFile = file

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            recorder = mediaRecorder
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoEncodingBitRate(6_000_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            virtualDisplay = projection!!.createVirtualDisplay(
                "PS Link screen recording", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, null
            ) ?: throw IllegalStateException("Android could not create the recording display")
            mediaRecorder.start()
        } catch (error: Exception) {
            fail("تعذر بدء تسجيل الشاشة: ${error.localizedMessage ?: "خطأ MediaProjection"}")
        }
    }

    private fun finishRecording() {
        if (finishing) return
        finishing = true
        val file = outputFile
        var usable = false
        try {
            recorder?.stop()
            usable = file?.isFile == true && file.length() > 1024
        } catch (_: RuntimeException) {
            usable = false // MediaRecorder.stop throws for clips that are too short to form a valid MP4.
        } finally {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching { projection?.stop() }
            projection = null
        }

        val resultUri = if (usable && file != null) Uri.fromFile(file).toString() else ""
        if (resultUri.isBlank()) file?.delete()
        getSharedPreferences("pslink_capture_pending", MODE_PRIVATE).edit()
            .putString("uri", resultUri)
            .putString("error", if (resultUri.isBlank()) "لم ينتج تسجيل صالح. جرّب مدة أطول." else "")
            .commit()
        // Keep the raw capture private until the user trims/saves it in VideoEditorActivity.
        sendBroadcast(Intent(ACTION_SAVED).setPackage(packageName).putExtra(EXTRA_MEDIA_URI, resultUri))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }


    private fun fail(message: String) {
        finishing = true
        getSharedPreferences("pslink_capture_pending", MODE_PRIVATE).edit()
            .remove("uri").putString("error", message).commit()
        sendBroadcast(Intent(ACTION_SAVED).setPackage(packageName).putExtra(EXTRA_MEDIA_URI, "").putExtra("error", message))
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "PS Link video recording", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPending = PendingIntent.getService(this, 5103, stopIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PS Link يسجل الشاشة")
            .setContentText("اضغط إيقاف لإنهاء التسجيل وفتح محرر الفيديو")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف التسجيل", stopPending)
            .build()
    }

    override fun onDestroy() {
        if (!finishing && recorder != null) finishRecording()
        super.onDestroy()
    }
}
