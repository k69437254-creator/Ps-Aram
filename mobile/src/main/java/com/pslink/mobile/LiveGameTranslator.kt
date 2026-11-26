// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import android.view.PixelCopy
import com.pslink.core.StableTextGate
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.google.android.gms.tasks.Task

// PSLINK_ARABIC_OCR_V1
// Arabic OCR uses Tesseract (tess-two) because ML Kit does not support Arabic as an OCR source language.
// Add to app build.gradle.kts:
//   implementation("com.rmtheis:tess-two:9.1.0")
// Copy ara.traineddata to assets/tessdata/ara.traineddata
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.ResultIterator
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Samples the actual Chiaki SurfaceView, performs on-device OCR + translation, and forwards captions to the TV. */
class LiveGameTranslator(
    private val activity: Activity,
    private val surface: SurfaceView,
    private val tvClient: TvSubtitleClient?,
    private val onCaption: (List<CaptionLine>) -> Unit,
    private val onStatus: (String) -> Unit,
    val sessionId: String = ""
) {
    data class Language(val label: String, val code: String, val script: Script)
    enum class Script { LATIN, JAPANESE, CHINESE, KOREAN, ARABIC }

    companion object {
        val languages = listOf(
            Language("الإنجليزية", TranslateLanguage.ENGLISH, Script.LATIN),
            Language("اليابانية", TranslateLanguage.JAPANESE, Script.JAPANESE),
            Language("الصينية", TranslateLanguage.CHINESE, Script.CHINESE),
            Language("الكورية", TranslateLanguage.KOREAN, Script.KOREAN),
            Language("العربية", TranslateLanguage.ARABIC, Script.ARABIC)
        )
        // PSLINK_REMAINING_REPAIRS_V3_SAMPLING
        private const val SAMPLE_INTERVAL_MS = 350L
        private const val MAX_OCR_CHARS = 160
        private const val MAX_CAPTURE_WIDTH = 960
        private const val TESSERACT_PSM_SPARSE_TEXT = 11
    }

    data class CaptionLine(
        val source: String,
        val translated: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class ArabicOcrLine(
        val text: String,
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
    private var loopGeneration = 0L
    private val loopMutex = Mutex()
    private var lastSource = ""
    private var lastTranslation = ""
    private val stableTextGate = StableTextGate(requiredMatches = 2)
    private var emptyFrameCount = 0
    private var consecutiveCaptureFailures = 0

    val isEnabled: Boolean get() = enabled

    fun start(languageCode: String = language.code) {
        val selected = languages.firstOrNull { it.code == languageCode } ?: languages.first()
        if (selected.code != language.code) {
            pause()
            lastSource = ""
            lastTranslation = ""
            stableTextGate.reset()
            emptyFrameCount = 0
            consecutiveCaptureFailures = 0
            onCaption(emptyList())
        }
        language = selected
        enabled = true
        beginLoop()
    }

    fun pause() {
        tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)
        loop?.cancel()
        lastSource = ""
        lastTranslation = ""
        emptyFrameCount = 0
        stableTextGate.reset()
    }

    fun resume() {
        if (enabled && loop?.isActive != true) beginLoop()
    }

    fun stop() {
        tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)
        enabled = false
        pause()
        lastSource = ""
        lastTranslation = ""
        stableTextGate.reset()
        emptyFrameCount = 0
        consecutiveCaptureFailures = 0
        onCaption(emptyList())
    }

    fun close() {
        stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun beginLoop() {
        if (loop?.isActive == true) return
        val generation = ++loopGeneration
        loop = scope.launch {
            loopMutex.withLock {
                var activeRecognizer: TextRecognizer? = null
                var activeTranslator: Translator? = null
                var tessBaseApi: TessBaseAPI? = null
                try {
                    val activeLanguage = language
                    onStatus("جارٍ تجهيز التعرف والترجمة على الجهاز…")
                    val arabicOcr = activeLanguage.script == Script.ARABIC
                    activeRecognizer = if (arabicOcr) null else createRecognizer(activeLanguage.script)
                    activeTranslator = if (arabicOcr) null else Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(activeLanguage.code)
                            .setTargetLanguage(TranslateLanguage.ARABIC)
                            .build()
                    )
                    activeTranslator?.downloadModelIfNeeded(
                        DownloadConditions.Builder().requireWifi().build()
                    )?.awaitResult()
                    currentCoroutineContext().ensureActive()
                    onStatus("الترجمة الحية تعمل: ${activeLanguage.label} ← العربية")
                    while (isActive && enabled) {
                        currentCoroutineContext().ensureActive()
                        val sampleStartedAt = SystemClock.elapsedRealtime()
                        val bitmap = captureFrame()
                        val samplePtsNs = System.nanoTime()
                        if (bitmap == null) {
                            consecutiveCaptureFailures++
                            if (consecutiveCaptureFailures == 5) {
                                onStatus("تعذر التقاط إطارات اللعبة من سطح البث. تأكد من أن بث PS5 ظاهر ثم أعد تشغيل الترجمة.")
                            }
                        } else {
                            consecutiveCaptureFailures = 0
                            try {
                                if (activeLanguage.script == Script.ARABIC) {
                                    // PSLINK_ARABIC_OCR_V1 — Use Tesseract for Arabic OCR (source IS Arabic,
                                    // no translation step needed; ML Kit does not support Arabic OCR).
                                    if (tessBaseApi == null) {
                                        val (api, errorMessage) = withContext(NonCancellable + Dispatchers.IO) {
                                            initTessBaseAPI(activity)
                                        }
                                        tessBaseApi = api
                                        currentCoroutineContext().ensureActive()
                                        if (errorMessage != null) {
                                            onStatus("تعذر تشغيل OCR العربي (Tesseract): $errorMessage")
                                        }
                                    }
                                    val tessAPI = tessBaseApi
                                    if (tessAPI == null) {
                                        emptyFrameCount++
                                        if (emptyFrameCount >= 2 && lastSource.isNotEmpty()) {
                                            lastSource = ""
                                            lastTranslation = ""
                                            onCaption(emptyList())
                                            tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)
                                        }
                                    } else {
                                        val arabicLines = withContext(Dispatchers.Default) {
                                            ocrArabic(tessAPI, bitmap)
                                        } ?: emptyList()
                                        currentCoroutineContext().ensureActive()
                                        val detected = arabicLines
                                            .asSequence()
                                            .filter { it.text.length <= MAX_OCR_CHARS }
                                            .filter { line ->
                                                val centerY = (line.top + line.bottom) / 2f
                                                val width = line.right - line.left
                                                val heightPixels = (line.bottom - line.top) * bitmap.height
                                                centerY >= 0.48f &&
                                                    width >= 0.06f &&
                                                    heightPixels >= 8f
                                            }
                                            .sortedWith(compareBy({ it.top }, { it.left }))
                                            .take(3)
                                            .map { line ->
                                                CaptionLine(
                                                    source = line.text,
                                                    translated = line.text,
                                                    left = line.left,
                                                    top = line.top,
                                                    right = line.right,
                                                    bottom = line.bottom
                                                )
                                            }
                                            .toList()
                                        val signature = detected.joinToString("|") { it.source }
                                        if (detected.isEmpty()) {
                                            stableTextGate.offer("")
                                            emptyFrameCount++
                                            if (emptyFrameCount >= 2 && lastSource.isNotEmpty()) {
                                                lastSource = ""
                                                lastTranslation = ""
                                                stableTextGate.reset()
                                                onCaption(emptyList())
                                                tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)
                                            }
                                        } else {
                                            emptyFrameCount = 0
                                            if (stableTextGate.offer(signature) != null &&
                                                signature != lastSource) {
                                                lastSource = signature
                                                lastTranslation = detected.joinToString("\n") { it.translated }
                                                onCaption(detected)
                                                tvClient?.sendCaptions(detected, samplePtsNs, sessionId)
                                            }
                                        }
                                    }
                                } else {
                                    val recognizer = activeRecognizer
                                        ?: error("OCR recognizer was not initialized")
                                    val translator = activeTranslator
                                        ?: error("Translation client was not initialized")
                                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                                    currentCoroutineContext().ensureActive()
                                    // Game subtitles normally occupy the lower portion of the streamed frame.
                                    // Keep OCR geometry so the Arabic replacement can cover the original line.
                                    val detected = result.textBlocks
                                        .flatMap { block -> block.lines }
                                        .mapNotNull { line ->
                                            val rect = line.boundingBox ?: return@mapNotNull null
                                            val sourceText = line.text.trim().replace(Regex("[\\t ]+"), " ")
                                            if (sourceText.length < 2 || sourceText.length > MAX_OCR_CHARS ||
                                                rect.centerY() < bitmap.height * 0.48f ||
                                                rect.width() < bitmap.width * 0.06f ||
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
                                        "${it.source}@${(it.left * 1000).toInt()},${(it.top * 1000).toInt()},${(it.right * 1000).toInt()},${(it.bottom * 1000).toInt()}"
                                    }
                                    if (detected.isEmpty()) {
                                        stableTextGate.offer("")
                                        emptyFrameCount++
                                        if (emptyFrameCount >= 2 && lastSource.isNotEmpty()) {
                                            lastSource = ""
                                            lastTranslation = ""
                                            stableTextGate.reset()
                                            onCaption(emptyList())
                                            tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)
                                        }
                                    } else {
                                        emptyFrameCount = 0
                                        if (stableTextGate.offer(signature) != null &&
                                            signature != lastSource) {
                                            val translatedLines = mutableListOf<String>()
                                            for (line in detected) {
                                                val translated = translator.translate(line.source).awaitResult().trim()
                                                currentCoroutineContext().ensureActive()
                                                translatedLines.add(translated)
                                            }
                                            val captions = detected.zip(translatedLines)
                                                .mapNotNull { (line, translated) ->
                                                    if (translated.isBlank()) null else line.copy(translated = translated)
                                                }
                                            lastSource = signature
                                            lastTranslation = captions.joinToString("\n") { it.translated }
                                            onCaption(captions)
                                            // The TV receives the exact OCR geometry so Arabic covers the
                                            // source dialogue in the streamed game image, not in a text panel.
                                            tvClient?.sendCaptions(captions, samplePtsNs, sessionId)
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
                    if (generation == loopGeneration) {
                        enabled = false
                        stableTextGate.reset()
                        tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)
                        onCaption(emptyList())
                        onStatus("توقفت الترجمة الحية: ${error.localizedMessage ?: "تعذر معالجة إطار الفيديو"}")
                    }
                } finally {
                    val cleanupError = withContext(NonCancellable + Dispatchers.IO) {
                        var failure: Exception? = null
                        try {
                            activeRecognizer?.close()
                        } catch (error: Exception) {
                            failure = error
                        }
                        try {
                            activeTranslator?.close()
                        } catch (error: Exception) {
                            if (failure == null) failure = error
                        }
                        try {
                            tessBaseApi?.end()
                        } catch (error: Exception) {
                            if (failure == null) failure = error
                        }
                        failure
                    }
                    if (cleanupError != null) {
                        onStatus("تعذر تحرير موارد OCR: ${cleanupError.localizedMessage ?: "خطأ غير معروف"}")
                    }
                }
            }
        }
    }

    private fun createRecognizer(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        Script.ARABIC -> error("Arabic OCR is handled by Tesseract")
    }

    private suspend fun captureFrame(): Bitmap? = withContext(Dispatchers.Main.immediate) {
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) return@withContext null
        val scale = minOf(1f, MAX_CAPTURE_WIDTH.toFloat() / surface.width.toFloat())
        val width = (surface.width * scale).toInt().coerceAtLeast(1)
        val height = (surface.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        suspendCancellableCoroutine { continuation ->
            val released = AtomicBoolean(false)
            fun releaseBitmap() {
                if (released.compareAndSet(false, true) && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            try {
                PixelCopy.request(surface, bitmap, { result ->
                    if (!continuation.isActive) {
                        releaseBitmap()
                    } else if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap) { _ -> releaseBitmap() }
                    } else {
                        releaseBitmap()
                        continuation.resume(null)
                    }
                }, mainHandler)
            } catch (_: Exception) {
                releaseBitmap()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }


    // PSLINK_ARABIC_OCR_V1 — Tesseract-based Arabic OCR
    private fun initTessBaseAPI(activity: Activity): Pair<TessBaseAPI?, String?> {
        var api: TessBaseAPI? = null
        try {
            val tessBaseAPI = TessBaseAPI()
            api = tessBaseAPI
            val assets = activity.assets
            val tessdataDir = File(activity.cacheDir, "tessdata")
            if (!tessdataDir.isDirectory && !tessdataDir.mkdirs()) {
                throw IOException("Unable to create tessdata directory")
            }
            val araData = File(tessdataDir, "ara.traineddata")
            fun digest(input: java.io.InputStream): Pair<Long, ByteArray> {
                val messageDigest = MessageDigest.getInstance("SHA-256")
                var byteCount = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    messageDigest.update(buffer, 0, bytesRead)
                    byteCount += bytesRead
                }
                return byteCount to messageDigest.digest()
            }

            val source = assets.open("tessdata/ara.traineddata").use { digest(it) }
            if (source.first == 0L) {
                throw IOException("Asset ara.traineddata is empty")
            }
            val cachedIsValid = araData.isFile && araData.length() == source.first &&
                araData.inputStream().use { MessageDigest.isEqual(source.second, digest(it).second) }
            if (!cachedIsValid) {
                val temporaryData = File.createTempFile("ara-", ".tmp", tessdataDir)
                var backupData: File? = null
                var backupContainsOriginal = false
                var operationError: Throwable? = null
                try {
                    var copiedBytes = 0L
                    val copiedDigest = MessageDigest.getInstance("SHA-256")
                    assets.open("tessdata/ara.traineddata").use { input ->
                        temporaryData.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead < 0) break
                                output.write(buffer, 0, bytesRead)
                                copiedDigest.update(buffer, 0, bytesRead)
                                copiedBytes += bytesRead
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    val staged = temporaryData.inputStream().use { digest(it) }
                    if (copiedBytes != source.first || temporaryData.length() != source.first ||
                        staged.first != source.first ||
                        !MessageDigest.isEqual(source.second, copiedDigest.digest()) ||
                        !MessageDigest.isEqual(source.second, staged.second)
                    ) {
                        throw IOException("Copied ara.traineddata failed integrity verification")
                    }

                    if (araData.exists()) {
                        backupData = File.createTempFile("ara-backup-", ".tmp", tessdataDir)
                        if (!backupData.delete()) {
                            throw IOException("Unable to prepare ara.traineddata backup")
                        }
                        if (!araData.renameTo(backupData)) {
                            throw IOException("Unable to preserve existing ara.traineddata")
                        }
                        backupContainsOriginal = true
                    }
                    if (!temporaryData.renameTo(araData)) {
                        val replaceError = IOException("Unable to replace ara.traineddata")
                        if (backupContainsOriginal) {
                            val backup = backupData!!
                            if (!backup.renameTo(araData)) {
                                replaceError.addSuppressed(
                                    IOException("Unable to restore previous ara.traineddata; preserved at ${backup.absolutePath}")
                                )
                            } else {
                                backupContainsOriginal = false
                            }
                        } else {
                            backupContainsOriginal = false
                        }
                        throw replaceError
                    }
                    backupContainsOriginal = false
                } catch (error: Throwable) {
                    operationError = error
                    throw error
                } finally {
                    var cleanupError: IOException? = null
                    fun removeTemporary(file: File?) {
                        if (file != null && file.exists() && !file.delete()) {
                            val error = IOException("Unable to remove temporary file: ${file.name}")
                            if (cleanupError == null) cleanupError = error else cleanupError!!.addSuppressed(error)
                        }
                    }
                    removeTemporary(temporaryData)
                    if (!backupContainsOriginal) {
                        removeTemporary(backupData)
                    }
                    val primaryError = operationError
                    val finalCleanupError = cleanupError
                    if (finalCleanupError != null) {
                        if (primaryError != null) primaryError.addSuppressed(finalCleanupError)
                        else throw finalCleanupError
                    }
                }
            }
            val initialized = tessBaseAPI.init(
                activity.cacheDir.absolutePath + File.separator,
                "ara",
                TessBaseAPI.OEM_DEFAULT
            )
            if (!initialized) {
                throw IllegalStateException("Tesseract rejected ara.traineddata")
            }
            return api to null
        } catch (error: Exception) {
            val cleanupError = try {
                api?.end()
                null
            } catch (closeError: Exception) {
                closeError.localizedMessage
            }
            val message = error.localizedMessage ?: "خطأ غير معروف"
            return null to if (cleanupError == null) message else "$message؛ تعذر تحرير المورد: $cleanupError"
        }
    }

    private fun ocrArabic(tessBaseApi: TessBaseAPI, bitmap: Bitmap): List<ArabicOcrLine>? {
        tessBaseApi.setPageSegMode(TESSERACT_PSM_SPARSE_TEXT)
        tessBaseApi.setImage(bitmap)
        val iterator: ResultIterator = tessBaseApi.getResultIterator() ?: return emptyList()
        val lines = ArrayList<ArabicOcrLine>()
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    ?.trim()
                    ?.replace(Regex("[\\t ]+"), " ")
                    ?.takeIf { it.length >= 2 }
                    ?: continue
                val bounds = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    ?: continue
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                lines.add(
                    ArabicOcrLine(
                        text = text,
                        left = (bounds.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                        top = (bounds.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                        right = (bounds.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                        bottom = (bounds.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                    )
                )
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
        } finally {
            iterator.delete()
        }
        return lines
    }
}

// Wait for SDK work to finish before the loop's finally block closes its recognizer.
private suspend fun <T> Task<T>.awaitResult(): T = suspendCoroutine { continuation ->
    addOnSuccessListener { value -> continuation.resume(value) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener {
        continuation.resumeWithException(CancellationException("On-device task was cancelled"))
    }
}
