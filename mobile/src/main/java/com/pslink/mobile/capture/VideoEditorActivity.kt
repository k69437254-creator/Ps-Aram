// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.concurrent.thread

/** Lightweight pre-save clip editor: preview + trim start/end without re-encoding. */
class VideoEditorActivity : AppCompatActivity() {
    private lateinit var videoUri: Uri
    private lateinit var videoView: VideoView
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var status: TextView
    private var durationSeconds = 0.0
    @Volatile private var exportRunning = false
    private lateinit var exportBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.metallic.chiaki.R.layout.activity_pslink_video_editor)
        findViewById<MaterialToolbar>(com.metallic.chiaki.R.id.editorToolbar).setNavigationOnClickListener {
            if (exportRunning) status.text = "انتظر اكتمال حفظ الفيديو قبل الخروج" else finish()
        }
        exportBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { status.text = "انتظر اكتمال حفظ الفيديو قبل الخروج" }
        }
        onBackPressedDispatcher.addCallback(this, exportBackCallback)
        videoView = findViewById(com.metallic.chiaki.R.id.editorVideoView)
        startInput = findViewById(com.metallic.chiaki.R.id.trimStartInput)
        endInput = findViewById(com.metallic.chiaki.R.id.trimEndInput)
        status = findViewById(com.metallic.chiaki.R.id.editorStatus)
        val raw = intent.getStringExtra(ScreenRecordingService.EXTRA_MEDIA_URI).orEmpty()
        videoUri = Uri.parse(raw)
        if (videoUri.scheme != "file" || videoUri.path.isNullOrBlank() || !File(videoUri.path!!).isFile) {
            status.text = "ملف التسجيل المؤقت غير موجود أو غير صالح"
            finish()
            return
        }
        videoView.setVideoURI(videoUri)
        videoView.setOnPreparedListener { player ->
            durationSeconds = player.duration / 1000.0
            endInput.setText(String.format(Locale.US, "%.2f", durationSeconds))
            videoView.start()
        }
        findViewById<Button>(com.metallic.chiaki.R.id.previewButton).setOnClickListener {
            videoView.seekTo((startInput.text.toString().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0).times(1000).toInt())
            videoView.start()
        }
        findViewById<Button>(com.metallic.chiaki.R.id.saveEditedVideoButton).setOnClickListener { exportTrimmed() }
    }

    override fun onDestroy() {
        super.onDestroy()
        val path = if (::videoUri.isInitialized) videoUri.path else null
        if (videoUri.scheme == "file" && path != null && path.startsWith(cacheDir.absolutePath + File.separator)) {
            File(path).delete()
        }
    }

    private fun exportTrimmed() {
        val startUs = ((startInput.text.toString().toDoubleOrNull() ?: 0.0) * 1_000_000).toLong().coerceAtLeast(0)
        val endUs = ((endInput.text.toString().toDoubleOrNull() ?: durationSeconds) * 1_000_000).toLong()
        if (exportRunning) return
        if (endUs <= startUs || endUs > (durationSeconds * 1_000_000L) + 250_000L) {
            status.text = "حدد بداية ونهاية صحيحتين للمقطع"
            return
        }
        exportRunning = true
        exportBackCallback.isEnabled = true
        findViewById<Button>(com.metallic.chiaki.R.id.saveEditedVideoButton).isEnabled = false
        status.text = "جارٍ قص المقطع دون إعادة ضغط الفيديو…"
        thread(name = "PSLink-VideoTrim") {
            val result = runCatching { trimVideo(startUs, endUs) }
            runOnUiThread {
                exportRunning = false
                exportBackCallback.isEnabled = false
                findViewById<Button>(com.metallic.chiaki.R.id.saveEditedVideoButton).isEnabled = true
                result.onSuccess {
                    status.text = "تم حفظ المقطع المعدل في Movies/PS Link/Edited"
                }.onFailure {
                    status.text = "تعذر القص: ${it.localizedMessage ?: "صيغة فيديو غير مدعومة"}"
                }
            }
        }
    }

    private fun trimVideo(startUs: Long, endUs: Long): Uri {
        val extractor = MediaExtractor()
        val temp = File(cacheDir, "pslink_trim_${System.currentTimeMillis()}.mp4")
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(this, videoUri, null)
            val sourceTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("لا يحتوي التسجيل على مسار فيديو")
            val format = extractor.getTrackFormat(sourceTrack)
            muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            extractor.selectTrack(sourceTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val requestedSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 4 shl 20
            val maxSize = requestedSize.coerceIn(1 shl 20, 32 shl 20)
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = android.media.MediaCodec.BufferInfo()
            var firstPts = -1L
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (firstPts < 0) firstPts = pts
                info.set(0, size, (pts - firstPts).coerceAtLeast(0), extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                muxer.writeSampleData(outputTrack, buffer, info)
                samples++
                if (!extractor.advance()) break
            }
            if (samples == 0) throw IllegalStateException("لم توجد إطارات داخل النطاق المحدد")
            muxer.stop()
            muxer.release()
            muxer = null
            return publishEditedFile(temp)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            temp.delete()
        }
    }

    private fun publishEditedFile(file: File): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "PSLink_Edited_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PS Link/Edited")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
            try {
                contentResolver.openOutputStream(uri, "w")!!.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return uri
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val target = File(dir, "PSLink_Edited_${System.currentTimeMillis()}.mp4")
        FileInputStream(file).use { input -> target.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(target)
    }
}
