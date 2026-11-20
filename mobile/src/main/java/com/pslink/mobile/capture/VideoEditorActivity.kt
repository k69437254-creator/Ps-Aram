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
        var muxerStarted = false
        try {
            extractor.setDataSource(this, videoUri, null)
            val sourceTracks = (0 until extractor.trackCount).filter { track ->
                val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }
            val videoTracks = sourceTracks.filter { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (videoTracks.isEmpty()) throw IllegalStateException("لا يحتوي التسجيل على مسار فيديو")
            if (sourceTracks.isEmpty()) throw IllegalStateException("لا توجد مسارات فيديو أو صوت قابلة للقص")

            val activeMuxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val outputTracks = HashMap<Int, Int>()
            sourceTracks.forEach { inputTrack ->
                outputTracks[inputTrack] = activeMuxer.addTrack(extractor.getTrackFormat(inputTrack))
                extractor.selectTrack(inputTrack)
            }
            activeMuxer.start()
            muxerStarted = true
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val requestedSize = sourceTracks.maxOfOrNull { track ->
                val format = extractor.getTrackFormat(track)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1)
                else 4 shl 20
            } ?: (4 shl 20)
            val maxSize = requestedSize.coerceIn(1 shl 20, 32 shl 20)
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = android.media.MediaCodec.BufferInfo()
            var firstVideoPts = -1L
            var videoSamples = 0
            while (true) {
                buffer.clear()
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                val isVideoTrack = inputTrack in videoTracks
                if (isVideoTrack && firstVideoPts < 0) firstVideoPts = pts
                // Audio samples before the first copied video key frame must not
                // shift the MP4 timeline or create negative/duplicate timestamps.
                if (firstVideoPts < 0 || (!isVideoTrack && pts < firstVideoPts)) {
                    if (!extractor.advance()) break
                    continue
                }
                val outputTrack = outputTracks[inputTrack]
                    ?: throw IllegalStateException("ظهر مسار غير محدد أثناء قص الفيديو")
                info.set(0, size, (pts - firstVideoPts).coerceAtLeast(0), extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                activeMuxer.writeSampleData(outputTrack, buffer, info)
                if (isVideoTrack) videoSamples++
                if (!extractor.advance()) break
            }
            if (videoSamples == 0) throw IllegalStateException("لم توجد إطارات فيديو داخل النطاق المحدد")
            activeMuxer.stop()
            muxerStarted = false
            activeMuxer.release()
            muxer = null
            return publishEditedFile(temp)
        } finally {
            val remainingMuxer = muxer
            if (remainingMuxer != null) {
                if (muxerStarted) runCatching { remainingMuxer.stop() }
                runCatching { remainingMuxer.release() }
            }
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
