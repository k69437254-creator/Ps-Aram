// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pslink.tv.databinding.ActivityTvReceiverBinding
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class TvReceiverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvReceiverBinding
    private lateinit var receiver: TvSubtitleServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        binding.pairCodeText.text = code.chunked(3).joinToString(" ")
        binding.ipAddressText.text = localIpv4Addresses()

        receiver = TvSubtitleServer(code)
        runCatching { receiver.start() }
            .onSuccess { binding.serverStatusText.text = "خادم الترجمة يعمل على المنفذ ${com.pslink.core.PsLinkProtocol.PORT}" }
            .onFailure { error -> binding.serverStatusText.text = "تعذر تشغيل خادم الاستقبال: ${error.localizedMessage ?: "خطأ شبكة"}" }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                receiver.latestPacket.collect { packet ->
                    if (packet != null) {
                        binding.subtitleText.text = packet.translatedText
                        binding.sourceText.text = packet.sourceText
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::receiver.isInitialized) receiver.stop()
        super.onDestroy()
    }

    private fun localIpv4Addresses(): String {
        return runCatching {
            val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .map { it.hostAddress }
                .distinct()
            if (addresses.isEmpty()) "لم يُعثر على عنوان IPv4 للشبكة" else addresses.joinToString("   •   ")
        }.getOrElse { "تعذر قراءة عنوان الشبكة" }
    }
}
