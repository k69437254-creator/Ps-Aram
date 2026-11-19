// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pslink.core.SubtitlePacket
import com.metallic.chiaki.databinding.ActivityTranslationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Practical first translation workflow:
 * - OCR from a user-selected game screenshot using bundled on-device recognizers.
 * - On-device translation using downloadable ML Kit language packs.
 * - Local-network subtitle delivery to the paired Android TV app.
 *
 * Live frame extraction from the Chiaki renderer is intentionally not faked here.
 * It requires a native video-frame callback in Chiaki's rendering path; the upstream
 * Android UI currently renders to a Surface and does not expose each decoded frame to Kotlin.
 */
class TranslationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTranslationBinding
    private var translator: Translator? = null
    private var translatorSourceCode: String? = null
    private var tvClient: TvSubtitleClient? = null
    private var lastTranslation: String = ""
    private var lastSource: String = ""

    private val openScreenshot = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runOcr(uri)
    }

    private val sourceLanguages = listOf(
        SourceLanguage("اليابانية", TranslateLanguage.JAPANESE, OcrScript.JAPANESE),
        SourceLanguage("الإنجليزية", TranslateLanguage.ENGLISH, OcrScript.LATIN),
        SourceLanguage("الصينية", TranslateLanguage.CHINESE, OcrScript.CHINESE),
        SourceLanguage("الكورية", TranslateLanguage.KOREAN, OcrScript.KOREAN)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.translationToolbar.setNavigationOnClickListener { finish() }
        binding.sourceLanguageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sourceLanguages.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val preferences = getSharedPreferences("pslink_tv", MODE_PRIVATE)
        binding.tvHostInput.setText(preferences.getString("host", ""))
        binding.tvPairCodeInput.setText(preferences.getString("code", ""))

        binding.ocrImageButton.setOnClickListener { openScreenshot.launch(arrayOf("image/*")) }
        binding.downloadModelButton.setOnClickListener { downloadTranslationModel() }
        binding.translateButton.setOnClickListener { translateText() }
        binding.connectTvButton.setOnClickListener { connectTv() }
        binding.sendToTvButton.setOnClickListener { sendToTv() }

        tvClient = TvSubtitleClient { status, connected ->
            runOnUiThread {
                binding.tvConnectionStatus.text = status
                binding.tvConnectionStatus.setTextColor(
                    getColor(if (connected) com.metallic.chiaki.R.color.pslink_blue else com.metallic.chiaki.R.color.pslink_muted)
                )
            }
        }
        rebuildTranslator()
        binding.sourceLanguageSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                rebuildTranslator()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
    }

    private fun selectedLanguage(): SourceLanguage = sourceLanguages[
        binding.sourceLanguageSpinner.selectedItemPosition.coerceIn(sourceLanguages.indices)
    ]

    private fun rebuildTranslator() {
        val sourceCode = selectedLanguage().translationCode
        if (translatorSourceCode == sourceCode && translator != null) return
        translator?.close()
        translatorSourceCode = sourceCode
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
        translator = Translation.getClient(options)
        binding.translationStatusText.text = "الترجمة على الجهاز: ${selectedLanguage().label} ← العربية. نزّل الحزمة مرة واحدة قبل الاستخدام دون اتصال."
    }

    private fun downloadTranslationModel() {
        val activeTranslator = translator ?: return
        binding.downloadModelButton.isEnabled = false
        binding.translationStatusText.text = "جارٍ تنزيل حزمة اللغة عبر Wi‑Fi…"
        val conditions = DownloadConditions.Builder().requireWifi().build()
        activeTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                binding.translationStatusText.text = "اكتمل تنزيل الحزمة. يمكن استخدام الترجمة دون اتصال بالإنترنت."
                binding.downloadModelButton.isEnabled = true
                toast("حزمة اللغة جاهزة")
            }
            .addOnFailureListener { error ->
                binding.translationStatusText.text = "تعذر تنزيل الحزمة: ${error.localizedMessage ?: "تحقق من اتصال Wi‑Fi"}"
                binding.downloadModelButton.isEnabled = true
            }
    }

    private fun translateText() {
        val text = binding.sourceTextInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            toast("أدخل نصًا أو اقرأ لقطة شاشة أولًا")
            return
        }
        lastSource = text
        binding.translateButton.isEnabled = false
        binding.translationStatusText.text = "جارٍ تنفيذ الترجمة على الجهاز…"
        val activeTranslator = translator ?: run {
            binding.translateButton.isEnabled = true
            return
        }
        activeTranslator.translate(text)
            .addOnSuccessListener { translated ->
                lastTranslation = translated
                binding.translatedTextView.text = translated
                binding.translationStatusText.text = "اكتملت الترجمة على الجهاز. لا يُرسل النص إلى خادم ترجمة سحابي."
                binding.translateButton.isEnabled = true
            }
            .addOnFailureListener { error ->
                binding.translationStatusText.text = "لم تنجح الترجمة. نزّل حزمة اللغة أولًا، ثم أعد المحاولة. (${error.localizedMessage ?: "خطأ غير معروف"})"
                binding.translateButton.isEnabled = true
            }
    }

    private fun runOcr(uri: Uri) {
        binding.translationStatusText.text = "جارٍ قراءة النص من الصورة…"
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(this@TranslationActivity, uri) }
                val recognizer = createRecognizer(selectedLanguage().ocrScript)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val detected = result.text.trim()
                        if (detected.isBlank()) {
                            binding.translationStatusText.text = "لم يُعثر على نص واضح في الصورة. جرّب لقطة أوضح."
                        } else {
                            binding.sourceTextInput.setText(detected)
                            binding.translationStatusText.text = "تم استخراج النص محليًا من الصورة. راجع النص قبل الترجمة."
                        }
                        recognizer.close()
                    }
                    .addOnFailureListener { error ->
                        binding.translationStatusText.text = "تعذرت قراءة الصورة: ${error.localizedMessage ?: "خطأ OCR"}"
                        recognizer.close()
                    }
            } catch (error: Exception) {
                binding.translationStatusText.text = "تعذر فتح الصورة: ${error.localizedMessage ?: "ملف غير صالح"}"
            }
        }
    }

    private fun createRecognizer(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private fun connectTv() {
        val host = binding.tvHostInput.text?.toString()?.trim().orEmpty()
        val code = binding.tvPairCodeInput.text?.toString()?.trim().orEmpty()
        getSharedPreferences("pslink_tv", MODE_PRIVATE).edit()
            .putString("host", host)
            .putString("code", code)
            .apply()
        tvClient?.connect(host, code)
    }

    private fun sendToTv() {
        if (lastTranslation.isBlank()) {
            toast("ترجم النص أولًا")
            return
        }
        val packet = SubtitlePacket(
            game = "PS Link",
            sourceText = lastSource,
            translatedText = lastTranslation
        )
        if (tvClient?.send(packet) == true) toast("أُرسلت الترجمة إلى التلفاز")
    }

    override fun onDestroy() {
        translator?.close()
        tvClient?.close()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class SourceLanguage(
        val label: String,
        val translationCode: String,
        val ocrScript: OcrScript
    )

    private enum class OcrScript { LATIN, JAPANESE, CHINESE, KOREAN }
}
