// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tvfire

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pslink.core.PsLinkProtocol
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

/**
 * Fire TV receiver activity.
 *
 * Fire TV specifics vs Android TV:
 * - No Leanback browse fragments needed; simple full-screen SurfaceView.
 * - Remote control D-pad handled via onKeyEvent for navigation and media keys.
 * - Fire OS lifecycle: onStop may kill background processes; keep server running briefly.
 * - Audio focus handled by AudioTrack in TvMediaPlayer.
 */
class TvReceiverActivity : AppCompatActivity() {
    private lateinit var receiver: TvSubtitleServer
    private lateinit var mediaPlayer: TvMediaPlayer
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_receiver)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val surfaceView = findViewById<android.view.SurfaceView>(R.id.gameSurfaceView)
        val captionOverlay = findViewById<TvCaptionOverlayView>(R.id.captionOverlay)

        mediaPlayer = TvMediaPlayer(
            surfaceView,
            { status -> runOnUiThread { findViewById<android.widget.TextView>(R.id.serverStatusText).text = status } },
            { lines -> runOnUiThread { captionOverlay.setCaptions(lines) } }
        )

        val preferences = getSharedPreferences("pslink_tv_fire_receiver", MODE_PRIVATE)
        val code = preferences.getString("pair_code", null) ?:
            SecureRandom().nextInt(1_000_000).toString().padStart(6, '0').also {
                preferences.edit().putString("pair_code", it).commit()
            }
        findViewById<android.widget.TextView>(R.id.pairCodeText).text = code.chunked(3).joinToString(" ")
        findViewById<android.widget.TextView>(R.id.ipAddressText).text = localIpv4Addresses()

        receiver = TvSubtitleServer(
            code,
            onMediaConfig = { config ->
                mediaPlayer.configure(config)
                runOnUiThread {
                    findViewById<View>(R.id.pairingPanel).visibility = View.GONE
                    surfaceView.visibility = View.VISIBLE
                    captionOverlay.visibility = View.VISIBLE
                    findViewById<android.widget.TextView>(R.id.serverStatusText).text = "استُقبل بث اللعبة؛ جارٍ عرض الصورة والترجمة"
                }
            },
            onMediaPacket = mediaPlayer::submit,
            onMediaStop = {
                mediaPlayer.resetStream()
                runOnUiThread {
                    captionOverlay.setCaptions(emptyList())
                    surfaceView.visibility = View.GONE
                    findViewById<View>(R.id.pairingPanel).visibility = View.VISIBLE
                    findViewById<android.widget.TextView>(R.id.serverStatusText).text = "انقطع اتصال الهاتف؛ استخدم الرمز نفسه لإعادة الاقتران"
                }
            }
        )
        runCatching { receiver.start() }
            .onSuccess {
                findViewById<android.widget.TextView>(R.id.serverStatusText).text = "خادم PS Link جاهز على المنفذ ${PsLinkProtocol.PORT}"
                runCatching { registerDiscovery() }
                    .onFailure { findViewById<android.widget.TextView>(R.id.serverStatusText).text = "الخادم يعمل؛ تعذر إعلان التلفاز تلقائيًا. استخدم عنوان IP ورمز الاقتران الظاهرين." }
            }
            .onFailure { error -> findViewById<android.widget.TextView>(R.id.serverStatusText).text = "تعذر تشغيل خادم الاستقبال: ${error.localizedMessage ?: "خطأ شبكة"}" }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    receiver.latestCaptions.collect { frame ->
                        if (frame != null) mediaPlayer.scheduleCaptions(frame)
                    }
                }
                launch {
                    receiver.latestPacket.collect { packet ->
                        if (packet != null) findViewById<android.widget.TextView>(R.id.subtitleText).text = packet.translatedText
                    }
                }
            }
        }
    }

    // Fire TV remote: handle D-pad and media keys for navigation
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_BUTTON_A,
            android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                // Accept/dismiss pairing dialog if shown
                return true
            }
            android.view.KeyEvent.KEYCODE_BACK -> {
                // Let system handle back on Fire TV
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun registerDiscovery() {
        val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        multicastLock = wifi.createMulticastLock("PSLinkFireTVDiscovery").apply { setReferenceCounted(false); acquire() }
        val manager = getSystemService(android.content.Context.NSD_SERVICE) as? NsdManager
            ?: throw IllegalStateException("Android NSD service is unavailable")
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = "PS-Link-FireTV-${android.os.Build.MODEL.take(12)}"
            serviceType = "_pslink._tcp."
            port = PsLinkProtocol.PORT
            setAttribute("v", PsLinkProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                runOnUiThread { findViewById<android.widget.TextView>(R.id.serverStatusText).text = "التلفاز ظاهر تلقائيًا للهاتف على الشبكة المحلية" }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { findViewById<android.widget.TextView>(R.id.serverStatusText).text = "الخادم يعمل؛ تعذر إعلان التلفاز تلقائيًا ($errorCode). استخدم IP الظاهر." }
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
        if (::mediaPlayer.isInitialized) mediaPlayer.close()
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
