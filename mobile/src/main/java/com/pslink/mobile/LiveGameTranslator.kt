// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.PixelCopy
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pslink.core.SubtitlePacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task

/** Samples the actual Chiaki SurfaceView, performs on-device OCR + translation, and forwards captions to the TV. */
class LiveGameTranslator(
    private val activity: Activity,
    private val surface: SurfaceView,
    private val tvClient: TvSubtitleClient?,
    private val onCaption: (List<CaptionLine>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    data class Language(val label: String, val code: String, val script: Script)
    enum class Script { LATIN, JAPANESE, CHINESE, KOREAN }

    companion object {
        val languages = listOf(
            Language("الإنجليزية", TranslateLanguage.ENGLISH, Script.LATIN),
            Language("اليابانية", TranslateLanguage.JAPANESE, Script.JAPANESE),
            Language("الصينية", TranslateLanguage.CHINESE, Script.CHINESE),
            Language("الكورية", TranslateLanguage.KOREAN, Script.KOREAN)
        )
        private const val SAMPLE_INTERVAL_MS = 1_150L
        private const val MAX_OCR_CHARS = 2_500
        private const val MAX_CAPTURE_WIDTH = 960
    }

    data class CaptionLine(
        val source: String,
        val translated: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var language = languages.first()
    private var loop: Job? = null
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var lastSource = ""
    private var lastTranslation = ""
    private var emptyFrameCount = 0

    val isEnabled: Boolean get() = enabled

    fun start(languageCode: String = language.code) {
        val selected = languages.firstOrNull { it.code == languageCode } ?: languages.first()
        if (enabled && selected.code != language.code) pause()
        language = selected
        enabled = true
        beginLoop()
    }

    fun pause() {
        loop?.cancel()
        loop = null
    }

    fun resume() {
        if (enabled && loop?.isActive != true) beginLoop()
    }

    fun stop() {
        enabled = false
        pause()
        recognizer?.close()
        recognizer = null
        translator?.close()
        translator = null
        lastSource = ""
        lastTranslation = ""
        emptyFrameCount = 0
        onCaption(emptyList())
    }

    fun close() {
        stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun beginLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            try {
                onStatus("جارٍ تجهيز التعرف والترجمة على الجهاز…")
                val newRecognizer = createRecognizer(language.script)
                val newTranslator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(language.code)
                        .setTargetLanguage(TranslateLanguage.ARABIC)
                        .build()
                )
                recognizer?.close()
                translator?.close()
                recognizer = newRecognizer
                translator = newTranslator
                newTranslator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).awaitResult()
                onStatus("الترجمة الحية تعمل: ${language.label} ← العربية")
                while (isActive && enabled) {
                    val sampleStartedAt = SystemClock.elapsedRealtime()
                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        try {
                            val result = newRecognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                            // Game subtitles normally occupy the lower portion of the streamed frame.
                            // Keep OCR geometry so the Arabic replacement can cover the original line.
                            val detected = result.textBlocks
                                .flatMap { block -> block.lines }
                                .mapNotNull { line ->
                                    val rect = line.boundingBox ?: return@mapNotNull null
                                    val sourceText = line.text.trim().replace(Regex("[\\t ]+"), " ")
                                    if (sourceText.length < 2 ||
                                        rect.centerY() < bitmap.height * 0.60f ||
                                        rect.width() < bitmap.width * 0.10f ||
                                        rect.height() < 8
                                    ) return@mapNotNull null
                                    CaptionLine(
                                        source = sourceText,
                                        translated = "",
                                        left = (rect.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                        top = (rect.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                                        right = (rect.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                                        bottom = (rect.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                                    )
                                }
                                .sortedWith(compareBy<CaptionLine> { it.top }.thenBy { it.left })
                                .take(3)
                            val signature = detected.joinToString("|") {
                                "${it.source}@${(it.left * 100).toInt()},${(it.top * 100).toInt()}"
                            }
                            if (detected.isEmpty()) {
                                emptyFrameCount++
                                if (emptyFrameCount >= 2 && lastSource.isNotEmpty()) {
                                    lastSource = ""
                                    lastTranslation = ""
                                    onCaption(emptyList())
                                }
                            } else {
                                emptyFrameCount = 0
                                if (signature != lastSource) {
                                    val translatedLines = detected.map { line ->
                                        newTranslator.translate(line.source).awaitResult().trim()
                                    }
                                    val captions = detected.zip(translatedLines)
                                        .mapNotNull { (line, translated) ->
                                            if (translated.isBlank()) null else line.copy(translated = translated)
                                        }
                                    lastSource = signature
                                    lastTranslation = captions.joinToString("\n") { it.translated }
                                    onCaption(captions)
                                    if (captions.isNotEmpty()) {
                                        tvClient?.send(
                                            SubtitlePacket(
                                                game = "PS5 Remote Play",
                                                sourceText = "",
                                                translatedText = lastTranslation
                                            )
                                        )
                                    }
                                }
                            }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                    val remaining = SAMPLE_INTERVAL_MS - (SystemClock.elapsedRealtime() - sampleStartedAt)
                    if (remaining > 0) delay(remaining)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enabled = false
                runCatching { recognizer?.close() }
                runCatching { translator?.close() }
                recognizer = null
                translator = null
                onStatus("توقفت الترجمة الحية: ${error.localizedMessage ?: "تعذر معالجة إطار الفيديو"}")
            }
        }
    }

    private fun createRecognizer(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.Main.immediate) {
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) return@withContext null
        val scale = minOf(1f, MAX_CAPTURE_WIDTH.toFloat() / surface.width.toFloat())
        val width = (surface.width * scale).toInt().coerceAtLeast(1)
        val height = (surface.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(surface, bitmap, { result ->
                    if (!continuation.isActive) {
                        bitmap.recycle()
                    } else if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        continuation.resume(null)
                    }
                }, mainHandler)
            } catch (error: Exception) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
