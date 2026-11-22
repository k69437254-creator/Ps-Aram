// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
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
import com.pslink.core.PairingCode
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
    private var nsdManager: NsdManager? = null
    private var tvDiscoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var tvAutoConnectStarted = false
    private val tvReconnectHandler = Handler(Looper.getMainLooper())
    private var tvReconnectPending = false
    private var lastTranslation: String = ""
    private var lastSource: String = ""
    // Ignore ML Kit callbacks belonging to an old language or destroyed screen.
    private var translatorGeneration = 0
    private var ocrGeneration = 0

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

        binding.translationToolbar.setNavigationOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
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
        binding.tvDiscoveryButton.setOnClickListener { discoverTv() }
        binding.sendToTvButton.setOnClickListener { sendToTv() }

        tvClient = TvSubtitleClient { status, connected ->
            runOnUiThread {
                binding.tvConnectionStatus.text = status
                binding.tvConnectionStatus.setTextColor(
                    getColor(if (connected) com.metallic.chiaki.R.color.pslink_blue else com.metallic.chiaki.R.color.pslink_muted)
                )
                if (!connected && status.startsWith("رفض التلفاز الاقتران:")) {
                    // The discovered receiver rejected the previously saved code.
                    // Remove that stale credential and wait for manual re-pairing.
                    getSharedPreferences("pslink_tv", MODE_PRIVATE)
                        .edit()
                        .remove("code")
                        .apply()
                    // Remove the rejected credential from the visible form as well.
                    binding.tvPairCodeInput.text?.clear()

                    tvReconnectPending = false
                    tvReconnectHandler.removeCallbacksAndMessages(null)
                    tvAutoConnectStarted = false
                    // Restart discovery so a fresh code can be entered without
                    // leaving and reopening this screen.
                    tvReconnectHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) discoverTv()
                    }, 1_000L)
                }

                if (connected) {
                    tvReconnectPending = false
                    tvReconnectHandler.removeCallbacksAndMessages(null)
                    if (status.startsWith("تم اقتران التلفاز بنجاح")) {
                        val host = binding.tvHostInput.text?.toString()?.trim().orEmpty()
                        val code = PairingCode.normalize(binding.tvPairCodeInput.text?.toString().orEmpty())
                        if (host.isNotBlank() && code != null) {
                            getSharedPreferences("pslink_tv", MODE_PRIVATE).edit()
                                .putString("host", host).putString("code", code).apply()
                        }
                    }
                } else if (!tvReconnectPending && (status.startsWith("فشل الاتصال") || status == "انقطع اتصال التلفاز")) {
                    tvReconnectPending = true
                    tvReconnectHandler.postDelayed({
                        tvReconnectPending = false
                        if (!isFinishing && !isDestroyed) discoverTv()
                    }, 3_000L)
                }
            }
        }
        rebuildTranslator()
        discoverTv()
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
        translatorGeneration++
        ocrGeneration++
        binding.downloadModelButton.isEnabled = true
        binding.translateButton.isEnabled = true
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
        val requestGeneration = translatorGeneration
        binding.downloadModelButton.isEnabled = false
        binding.translationStatusText.text = "جارٍ تنزيل حزمة اللغة عبر Wi‑Fi…"
        val conditions = DownloadConditions.Builder().requireWifi().build()
        activeTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                if (requestGeneration != translatorGeneration || isFinishing || isDestroyed) return@addOnSuccessListener
                binding.translationStatusText.text = "اكتمل تنزيل الحزمة. يمكن استخدام الترجمة دون اتصال بالإنترنت."
                binding.downloadModelButton.isEnabled = true
                toast("حزمة اللغة جاهزة")
            }
            .addOnFailureListener { error ->
                if (requestGeneration != translatorGeneration || isFinishing || isDestroyed) return@addOnFailureListener
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
        val requestGeneration = translatorGeneration
        activeTranslator.translate(text)
            .addOnSuccessListener { translated ->
                if (requestGeneration != translatorGeneration || isFinishing || isDestroyed) return@addOnSuccessListener
                lastTranslation = translated
                binding.translatedTextView.text = translated
                binding.translationStatusText.text = "اكتملت الترجمة على الجهاز. لا يُرسل النص إلى خادم ترجمة سحابي."
                binding.translateButton.isEnabled = true
            }
            .addOnFailureListener { error ->
                if (requestGeneration != translatorGeneration || isFinishing || isDestroyed) return@addOnFailureListener
                binding.translationStatusText.text = "لم تنجح الترجمة. نزّل حزمة اللغة أولًا، ثم أعد المحاولة. (${error.localizedMessage ?: "خطأ غير معروف"})"
                binding.translateButton.isEnabled = true
            }
    }

    private fun runOcr(uri: Uri) {
        val requestGeneration = ++ocrGeneration
        val ocrScript = selectedLanguage().ocrScript
        binding.translationStatusText.text = "جارٍ قراءة النص من الصورة…"
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(this@TranslationActivity, uri) }
                val recognizer = createRecognizer(ocrScript)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        if (requestGeneration != ocrGeneration || isFinishing || isDestroyed) {
                            recognizer.close()
                            return@addOnSuccessListener
                        }
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
                        if (requestGeneration != ocrGeneration || isFinishing || isDestroyed) {
                            recognizer.close()
                            return@addOnFailureListener
                        }
                        binding.translationStatusText.text = "تعذرت قراءة الصورة: ${error.localizedMessage ?: "خطأ OCR"}"
                        recognizer.close()
                    }
            } catch (error: Exception) {
                if (requestGeneration != ocrGeneration || isFinishing || isDestroyed) return@launch
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

    private fun discoverTv() {
        stopTvDiscovery()
        tvAutoConnectStarted = false
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager
        binding.tvConnectionStatus.text = "جارٍ البحث عن تلفاز PS Link على الشبكة المحلية…"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                binding.tvConnectionStatus.text = "تعذر بدء اكتشاف التلفاز ($errorCode). أدخل عنوان IP يدويًا."
                stopTvDiscovery()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopTvDiscovery() }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { tvAutoConnectStarted = false }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_pslink._tcp") || tvAutoConnectStarted) return
                tvAutoConnectStarted = true
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        tvAutoConnectStarted = false
                        runOnUiThread { binding.tvConnectionStatus.text = "عُثر على التلفاز لكن تعذر معرفة عنوانه ($errorCode)." }
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (host.isNullOrBlank()) {
                                tvAutoConnectStarted = false
                                binding.tvConnectionStatus.text = "عُثر على التلفاز دون عنوان شبكة صالح."
                                return@runOnUiThread
                            }
                            binding.tvHostInput.setText(host)
                            val savedCode = getSharedPreferences("pslink_tv", MODE_PRIVATE).getString("code", "").orEmpty()
                            val normalizedSavedCode = PairingCode.normalize(savedCode)
                            if (normalizedSavedCode != null) {
                                binding.tvPairCodeInput.setText(normalizedSavedCode)
                                binding.tvConnectionStatus.text =
                                    "عُثر على ${resolved.serviceName}؛ جارٍ إعادة الاتصال بالرمز المحفوظ…"
                                connectTv(host, normalizedSavedCode)
                            } else {
                                binding.tvConnectionStatus.text = "عُثر على ${resolved.serviceName}. أدخل رمز الاقتران الظاهر على التلفاز ثم اضغط ربط التلفاز."
                            }
                        }
                    }
                })
            }
        }
        tvDiscoveryListener = listener
        runCatching { manager.discoverServices("_pslink._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { binding.tvConnectionStatus.text = "تعذر اكتشاف التلفاز؛ أدخل عنوان IP يدويًا." }
    }

    private fun stopTvDiscovery() {
        val listener = tvDiscoveryListener ?: return
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
        tvDiscoveryListener = null
    }

    private fun connectTv() {
        val host = binding.tvHostInput.text?.toString()?.trim().orEmpty()
        val code = binding.tvPairCodeInput.text?.toString()?.trim().orEmpty()
        connectTv(host, code)
    }

    private fun connectTv(host: String, code: String) {
        // Prevent NSD discovery from starting a competing automatic connection.
        stopTvDiscovery()
        tvAutoConnectStarted = true
        binding.tvHostInput.setText(host)
        binding.tvPairCodeInput.setText(code)
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
        translatorGeneration++
        ocrGeneration++
        tvReconnectHandler.removeCallbacksAndMessages(null)
        stopTvDiscovery()
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
