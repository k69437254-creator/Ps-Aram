// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pslink.core.PsLinkProtocol
import com.pslink.tv.databinding.ActivityTvReceiverBinding
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class TvReceiverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvReceiverBinding
    private lateinit var receiver: TvSubtitleServer
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val preferences = getSharedPreferences("pslink_tv_receiver", MODE_PRIVATE)
        val code = preferences.getString("pair_code", null) ?:
            SecureRandom().nextInt(1_000_000).toString().padStart(6, '0').also {
                preferences.edit().putString("pair_code", it).commit()
            }
        binding.pairCodeText.text = code.chunked(3).joinToString(" ")
        binding.ipAddressText.text = localIpv4Addresses()

        receiver = TvSubtitleServer(code)
        runCatching { receiver.start() }
            .onSuccess {
                binding.serverStatusText.text = "خادم الترجمة يعمل على المنفذ ${PsLinkProtocol.PORT}"
                runCatching { registerDiscovery() }
                    .onFailure { binding.serverStatusText.text = "الخادم يعمل؛ تعذر الاكتشاف التلقائي. استخدم عنوان IP ورمز الاقتران." }
            }
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

    private fun registerDiscovery() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        multicastLock = wifi.createMulticastLock("PSLinkNsdDiscovery").apply { setReferenceCounted(false); acquire() }
        val manager = getSystemService(NSD_SERVICE) as? NsdManager
            ?: throw IllegalStateException("Android NSD service is unavailable")
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = "PS-Link-TV-${android.os.Build.MODEL.take(12)}"
            serviceType = "_pslink._tcp."
            port = PsLinkProtocol.PORT
            setAttribute("v", PsLinkProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                runOnUiThread { binding.serverStatusText.text = "التلفاز ظاهر تلقائيًا للهاتف على الشبكة المحلية" }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { binding.serverStatusText.text = "الخادم يعمل؛ تعذر إعلان التلفاز تلقائيًا ($errorCode). استخدم IP الظاهر." }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterDiscovery() {
        registration?.let { listener -> runCatching { nsdManager?.unregisterService(listener) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    override fun onDestroy() {
        unregisterDiscovery()
        if (::receiver.isInitialized) receiver.stop()
        super.onDestroy()
    }

    private fun localIpv4Addresses(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .joinToString("   •   ")
            .ifBlank { "لم يُعثر على عنوان IPv4 للشبكة" }
    }.getOrElse { "تعذر قراءة عنوان الشبكة" }
}
