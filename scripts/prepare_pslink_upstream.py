#!/usr/bin/env python3
"""Reapply PS Link stream tools and touch-control customization over pinned Chiaki-NG."""
from pathlib import Path
import shutil
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UP = ROOT / "third_party/chiaki-ng/android/app/src/main"
if not (UP / "java/com/metallic/chiaki/stream/StreamActivity.kt").is_file():
    raise SystemExit("PS Link stream overlay: pinned Chiaki-NG Android source is missing")

def patch(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    # PS_LINK_NATIVE_CHIAKI_RECORDER_GUARD
    if path.name == "StreamActivity.kt" and label in (
        "stream imports",
        "stream capture state",
        "recording receiver lifecycle",
        "stream feature methods",
    ) and "private var psLinkRecordingDescriptor" in text:
        print(f"Skipped obsolete MediaProjection stream patch: {label}")
        return
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"PS Link overlay anchor mismatch ({label}): expected 1, found {count} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")
    print(f"Applied PS Link overlay: {label}")

# Stream Activity: PixelCopy live OCR, screenshot, MediaProjection recording, TV delivery and layout editor.
stream = UP / "java/com/metallic/chiaki/stream/StreamActivity.kt"
patch(stream, "import android.app.AlertDialog\n", '''import android.app.AlertDialog
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pslink.mobile.LiveGameTranslator
import com.pslink.mobile.PsLinkScreenshot
import com.pslink.mobile.TvSubtitleClient
import com.pslink.mobile.capture.ScreenRecordingService
import com.pslink.mobile.capture.VideoEditorActivity
''', "stream imports")
patch(stream, "\tprivate lateinit var binding: ActivityStreamBinding\n", '''	private lateinit var binding: ActivityStreamBinding
	private var psLinkTranslator: LiveGameTranslator? = null
	private var psLinkTvClient: TvSubtitleClient? = null
	private var psLinkRecordingActive = false
	private var psLinkControlEditMode = false
	private var psLinkRecordingReceiverRegistered = false

	private val psLinkNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if(!granted) Toast.makeText(this, "اسمح بإشعارات PS Link حتى يظهر زر إيقاف التسجيل أثناء اللعب", Toast.LENGTH_LONG).show()
	}

	private val psLinkProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val data = result.data
		if(result.resultCode == Activity.RESULT_OK && data != null)
		{
			val service = Intent(this, ScreenRecordingService::class.java).apply {
				action = ScreenRecordingService.ACTION_START
				putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, result.resultCode)
				putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
			}
			try {
				ContextCompat.startForegroundService(this, service)
				psLinkRecordingActive = true
				binding.psLinkToolsBar.isGone = true
				binding.psLinkRecordButton.text = "إيقاف التسجيل"
			} catch(error: Exception) {
				psLinkRecordingActive = false
				Toast.makeText(this, "تعذر بدء التسجيل: ${error.localizedMessage ?: "خدمة التسجيل"}", Toast.LENGTH_LONG).show()
			}
		}
		else Toast.makeText(this, "ألغيت موافقة تسجيل الشاشة", Toast.LENGTH_SHORT).show()
	}

	private val psLinkRecordingReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			psLinkRecordingActive = false
			binding.psLinkRecordButton.text = "تسجيل فيديو"
			binding.psLinkToolsBar.isGone = false
			val pending = getSharedPreferences("pslink_capture_pending", MODE_PRIVATE)
			val raw = intent?.getStringExtra(ScreenRecordingService.EXTRA_MEDIA_URI).orEmpty()
			val reason = intent?.getStringExtra("error").orEmpty()
			pending.edit().remove("uri").remove("error").apply()
			if(raw.isBlank()) {
				Toast.makeText(this@StreamActivity, reason.ifBlank { "لم ينتج تسجيل صالح. جرّب مدة أطول." }, Toast.LENGTH_LONG).show()
			} else {
				startActivity(Intent(this@StreamActivity, VideoEditorActivity::class.java)
					.putExtra(ScreenRecordingService.EXTRA_MEDIA_URI, raw))
			}
		}
	}
''', "stream capture state")
patch(stream, "\t\tsetContentView(binding.root)\n", "\t\tsetContentView(binding.root)\n\t\tsetupPsLinkFeatures()\n", "initialize PS Link stream tools")
patch(stream, "\toverride fun onResume()\n\t{\n\t\tsuper.onResume()\n", "\toverride fun onStart()\n\t{\n\t\tsuper.onStart()\n\t\tContextCompat.registerReceiver(this, psLinkRecordingReceiver, IntentFilter(ScreenRecordingService.ACTION_SAVED), ContextCompat.RECEIVER_NOT_EXPORTED)\n\t\tpsLinkRecordingReceiverRegistered = true\n\t}\n\n\toverride fun onStop()\n\t{\n\t\tif(psLinkRecordingReceiverRegistered) { unregisterReceiver(psLinkRecordingReceiver); psLinkRecordingReceiverRegistered = false }\n\t\tsuper.onStop()\n\t}\n\n\toverride fun onResume()\n\t{\n\t\tsuper.onResume()\n", "recording receiver lifecycle")
patch(stream, "\t\tviewModel.session.resume()\n\t}\n\n\toverride fun onPause()", "\t\tviewModel.session.resume()\n\t\tpsLinkTranslator?.resume()\n\t}\n\n\toverride fun onPause()", "resume live OCR with stream")
patch(stream, "\t\tviewModel.session.pause()\n\t}\n\n\toverride fun onDestroy()", "\t\tpsLinkTranslator?.pause()\n\t\tviewModel.session.pause()\n\t}\n\n\toverride fun onDestroy()", "pause live OCR with stream")
patch(stream, "\t\tsuper.onDestroy()\n\t\tcontrolsJob?.cancel()\n", "\t\tpsLinkTranslator?.close()\n\t\tpsLinkTvClient?.close()\n\t\tsuper.onDestroy()\n\t\tcontrolsJob?.cancel()\n", "release PS Link stream tools")
stream_methods = r'''
	private fun setupPsLinkFeatures()
	{
		psLinkTvClient = TvSubtitleClient { status, connected ->
			runOnUiThread {
				if(!connected && !psLinkTranslator?.isEnabled.orFalse()) binding.psLinkCaptionText.text = status
			}
		}
		val tvPrefs = getSharedPreferences("pslink_tv", MODE_PRIVATE)
		val savedHost = tvPrefs.getString("host", "").orEmpty()
		val savedCode = tvPrefs.getString("code", "").orEmpty()
		if(savedHost.isNotBlank() && savedCode.matches(Regex("\\d{6}"))) psLinkTvClient?.connect(savedHost, savedCode)

		val livePrefs = getSharedPreferences("pslink_live", MODE_PRIVATE)
		psLinkTranslator = LiveGameTranslator(this, binding.surfaceView, psLinkTvClient,
			{ captions -> runOnUiThread {
                binding.psLinkCaptionOverlay.setCaptions(binding.surfaceView, captions)
                binding.psLinkCaptionText.text = if(captions.isEmpty()) "" else "الترجمة العربية فوق النص الأصلي"
            } },
			{ status -> runOnUiThread {
				binding.psLinkCaptionText.text = status
				if(!psLinkTranslator?.isEnabled.orFalse()) {
					binding.psLinkLiveOcrButton.text = "ترجمة حية"
					livePrefs.edit().putBoolean("enabled", false).apply()
				}
			} })

		binding.psLinkLiveOcrButton.setOnClickListener {
			val engine = psLinkTranslator ?: return@setOnClickListener
			if(engine.isEnabled) {
				engine.stop()
				livePrefs.edit().putBoolean("enabled", false).apply()
				binding.psLinkLiveOcrButton.text = "ترجمة حية"
				binding.psLinkCaptionText.text = "الترجمة الحية متوقفة"
			} else {
				startPsLinkLiveTranslation(livePrefs.getString("language", com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH).orEmpty())
			}
		}
		binding.psLinkLiveOcrButton.setOnLongClickListener {
			val languages = LiveGameTranslator.languages
			AlertDialog.Builder(this).setTitle("لغة نص اللعبة")
				.setItems(languages.map { it.label }.toTypedArray()) { _, index ->
					livePrefs.edit().putString("language", languages[index].code).putBoolean("enabled", true).apply()
					startPsLinkLiveTranslation(languages[index].code)
				}.show()
			true
		}
		binding.psLinkScreenshotButton.setOnClickListener {
			PsLinkScreenshot.capture(this, binding.surfaceView) { success, message ->
				Toast.makeText(this, message, Toast.LENGTH_LONG).show()
			}
		}
		binding.psLinkRecordButton.setOnClickListener {
			if(psLinkRecordingActive) {
				startService(Intent(this, ScreenRecordingService::class.java).setAction(ScreenRecordingService.ACTION_STOP))
				binding.psLinkRecordButton.text = "جارٍ إنهاء التسجيل…"
			} else {
				if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
					psLinkNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
					return@setOnClickListener
				}
				val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
				psLinkProjectionLauncher.launch(manager.createScreenCaptureIntent())
			}
		}
		binding.psLinkControlsButton.setOnClickListener { togglePsLinkControlEditor() }
		if(livePrefs.getBoolean("enabled", false)) {
			startPsLinkLiveTranslation(livePrefs.getString("language", com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE).orEmpty())
		}
	}

	private fun startPsLinkLiveTranslation(language: String)
	{
		psLinkTranslator?.start(language)
		binding.psLinkLiveOcrButton.text = "إيقاف الترجمة"
		getSharedPreferences("pslink_live", MODE_PRIVATE).edit().putBoolean("enabled", true).putString("language", language).apply()
		binding.psLinkCaptionText.text = "جارٍ تجهيز الترجمة الحية…"
	}

	private fun togglePsLinkControlEditor()
	{
		psLinkControlEditMode = !psLinkControlEditMode
		listOf(R.id.controlsFragment, R.id.touchpadOnlyFragment).forEach { id ->
			(supportFragmentManager.findFragmentById(id) as? TouchControlsFragment)?.setControlCustomizationMode(psLinkControlEditMode)
		}
		binding.psLinkControlsButton.text = if(psLinkControlEditMode) "إنهاء التخصيص" else "تخصيص الأزرار"
	}

	private fun Boolean?.orFalse() = this ?: false

'''
patch(stream, "\tprivate fun stateChanged(state: StreamState)\n", stream_methods + "\tprivate fun stateChanged(state: StreamState)\n", "stream feature methods")

# Add controls as an overlay on top of the streamed SurfaceView.
layout = UP / "res/layout/activity_stream.xml"
stream_tools_xml = '''
    <LinearLayout
        android:id="@+id/psLinkToolsBar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|center_horizontal"
        android:layout_marginTop="8dp"
        android:background="#CC08111F"
        android:elevation="24dp"
        android:gravity="center"
        android:orientation="horizontal"
        android:padding="4dp">
        <com.google.android.material.button.MaterialButton
            android:id="@+id/psLinkLiveOcrButton"
            android:layout_width="wrap_content"
            android:layout_height="42dp"
            android:minWidth="0dp"
            android:text="ترجمة حية"
            android:textAllCaps="false"
            android:textSize="10sp"
            app:cornerRadius="10dp" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/psLinkScreenshotButton"
            android:layout_width="wrap_content"
            android:layout_height="42dp"
            android:minWidth="0dp"
            android:text="لقطة"
            android:textAllCaps="false"
            android:textSize="10sp"
            app:cornerRadius="10dp" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/psLinkRecordButton"
            android:layout_width="wrap_content"
            android:layout_height="42dp"
            android:minWidth="0dp"
            android:text="تسجيل"
            android:textAllCaps="false"
            android:textSize="10sp"
            app:cornerRadius="10dp" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/psLinkControlsButton"
            android:layout_width="wrap_content"
            android:layout_height="42dp"
            android:minWidth="0dp"
            android:text="تخصيص"
            android:textAllCaps="false"
            android:textSize="10sp"
            app:cornerRadius="10dp" />
    </LinearLayout>
    <com.pslink.mobile.LiveCaptionOverlayView
        android:id="@+id/psLinkCaptionOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clickable="false"
        android:focusable="false"
        android:importantForAccessibility="no" />
    <TextView
        android:id="@+id/psLinkCaptionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="18dp"
        android:background="#CC08111F"
        android:gravity="center"
        android:paddingHorizontal="12dp"
        android:paddingVertical="6dp"
        android:text=""
        android:textColor="#DCE8F8"
        android:textSize="11sp" />
'''
text = layout.read_text(encoding="utf-8")
if "@+id/psLinkToolsBar" not in text:
    if text.count("</FrameLayout>") != 1:
        raise SystemExit("PS Link overlay could not locate the root FrameLayout closing tag")
    text = text.replace("</FrameLayout>", stream_tools_xml + "</FrameLayout>", 1)
    layout.write_text(text, encoding="utf-8", newline="\n")
else:
    print("PS Link stream toolbar already present")
ET.parse(layout)

# Touch controls: draggable/resizable frames + persistent remapping for face/shoulder/system buttons and D-pad directions.
controls = UP / "java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
patch(controls, "\tvar onScreenControlsEnabled: LiveData<Boolean>? = null\n", '''\tvar onScreenControlsEnabled: LiveData<Boolean>? = null
	private var controlLayoutEditor: ControlLayoutEditor? = null
	private var controlCustomizationEnabled = false

	fun setControlCustomizationMode(enabled: Boolean)
	{
		controlCustomizationEnabled = enabled
		controlLayoutEditor?.setEnabled(enabled)
	}

	protected fun bindControlLayoutEditor(root: View)
	{
		controlLayoutEditor = ControlLayoutEditor(requireContext(), root)
		controlLayoutEditor?.setEnabled(controlCustomizationEnabled)
	}
''', "control editor state")
patch(controls, "\t\tsuper.onViewCreated(view, savedInstanceState)\n\t\tbinding.dpadView.stateChangeCallback", "\t\tsuper.onViewCreated(view, savedInstanceState)\n\t\tbindControlLayoutEditor(view)\n\t\tbinding.dpadView.stateChangeCallback", "attach default controls editor")
# Convert hard-coded button callbacks to dynamic stored mappings.
button_pairs = {
    "crossButtonView": "BUTTON_CROSS", "moonButtonView": "BUTTON_MOON", "pyramidButtonView": "BUTTON_PYRAMID",
    "boxButtonView": "BUTTON_BOX", "l1ButtonView": "BUTTON_L1", "r1ButtonView": "BUTTON_R1",
    "l3ButtonView": "BUTTON_L3", "r3ButtonView": "BUTTON_R3", "optionsButtonView": "BUTTON_OPTIONS",
    "shareButtonView": "BUTTON_SHARE", "psButtonView": "BUTTON_PS"
}
ct = controls.read_text(encoding="utf-8")
for view_name, mask in button_pairs.items():
    old = f"buttonPressedCallback = buttonStateChanged(ControllerState.{mask})"
    new = f"buttonPressedCallback = mappedButtonStateChanged(binding.{view_name}, ControllerState.{mask})"
    if old in ct:
        ct = ct.replace(old, new, 1)
    elif new not in ct:
        raise SystemExit(f"Missing touch button callback anchor for {view_name}")
# Replace D-pad direction function so all four actions can be remapped.
old_dpad = '''\tprivate fun dpadStateChanged(direction: DPadView.Direction?)
\t{
\t\townControllerState = ownControllerState.copy().apply {
\t\t\tbuttons = ((buttons
\t\t\t\t\t\tand ControllerState.BUTTON_DPAD_LEFT.inv()
\t\t\t\t\t\tand ControllerState.BUTTON_DPAD_RIGHT.inv()
\t\t\t\t\t\tand ControllerState.BUTTON_DPAD_UP.inv()
\t\t\t\t\t\tand ControllerState.BUTTON_DPAD_DOWN.inv())
\t\t\t\t\tor when(direction)
\t\t\t\t\t{
\t\t\t\t\t\tDPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
\t\t\t\t\t\tDPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
\t\t\t\t\t\tDPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
\t\t\t\t\t\tDPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
\t\t\t\t\t\tDPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
\t\t\t\t\t\tDPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
\t\t\t\t\t\tDPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
\t\t\t\t\t\tDPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
\t\t\t\t\t\tnull -> 0U
\t\t\t\t\t})
\t\t}
\t}
'''
new_dpad = '''\tprivate fun dpadStateChanged(direction: DPadView.Direction?)
\t{
\t\tval context = binding.dpadView.context
\t\tval up = ControlLayoutEditor.mappedDpadMask(context, "up", ControllerState.BUTTON_DPAD_UP)
\t\tval down = ControlLayoutEditor.mappedDpadMask(context, "down", ControllerState.BUTTON_DPAD_DOWN)
\t\tval left = ControlLayoutEditor.mappedDpadMask(context, "left", ControllerState.BUTTON_DPAD_LEFT)
\t\tval right = ControlLayoutEditor.mappedDpadMask(context, "right", ControllerState.BUTTON_DPAD_RIGHT)
\t\tval clearMask = up or down or left or right
\t\townControllerState = ownControllerState.copy().apply {
\t\t\tbuttons = (buttons and clearMask.inv()) or when(direction)
\t\t\t{
\t\t\t\tDPadView.Direction.UP -> up
\t\t\t\tDPadView.Direction.DOWN -> down
\t\t\t\tDPadView.Direction.LEFT -> left
\t\t\t\tDPadView.Direction.RIGHT -> right
\t\t\t\tDPadView.Direction.LEFT_UP -> left or up
\t\t\t\tDPadView.Direction.LEFT_DOWN -> left or down
\t\t\t\tDPadView.Direction.RIGHT_UP -> right or up
\t\t\t\tDPadView.Direction.RIGHT_DOWN -> right or down
\t\t\t\tnull -> 0U
\t\t\t}
\t\t}
\t}
'''
if old_dpad in ct:
    ct = ct.replace(old_dpad, new_dpad, 1)
elif new_dpad not in ct:
    raise SystemExit("Could not locate D-pad mapping method")
callback_pattern = re.compile(r"(?ms)^\tprivate fun buttonStateChanged\(buttonMask: UInt\) = \{ pressed: Boolean ->\n.*?^\t\}\n")
new_callback = "\tprivate fun mappedButtonStateChanged(view: View, defaultMask: UInt) = { pressed: Boolean ->\n\t\tval mask = ControlLayoutEditor.mappedMask(view.context, view.id, defaultMask)\n\t\townControllerState = ownControllerState.copy().apply {\n\t\t\tbuttons = if(pressed) buttons or mask else buttons and mask.inv()\n\t\t}\n\t}\n"
ct, callback_count = callback_pattern.subn(lambda _: new_callback, ct, count=1)
if callback_count == 0 and new_callback not in ct:
    raise SystemExit("Could not replace touch button callback implementation")
controls.write_text(ct, encoding="utf-8", newline="\n")

# The touchpad-only screen also supports position/size editing.
touchpad_fragment = UP / "java/com/metallic/chiaki/touchcontrols/TouchpadOnlyFragment.kt"
patch(touchpad_fragment, "\t\tsuper.onViewCreated(view, savedInstanceState)\n\t\ttouchpadOnlyEnabled?", "\t\tsuper.onViewCreated(view, savedInstanceState)\n\t\tbindControlLayoutEditor(view)\n\t\ttouchpadOnlyEnabled?", "attach touchpad-only layout editor")

print("PS Link Chiaki live stream and control customization overlays are installed.")

# PSLINK_DECODER_OVERLAY_AUTORUN_V1
# Reapply decoder lifecycle patches whenever the pinned Chiaki source is bootstrapped.
import pathlib as _pslink_decoder_pathlib
import subprocess as _pslink_decoder_subprocess
import sys as _pslink_decoder_sys

_pslink_decoder_scripts = _pslink_decoder_pathlib.Path(__file__).resolve().parent
for _pslink_decoder_name in (
    "apply_decoder_lifecycle_fix.py",
    "fix_decoder_shutdown_order.py",
):
    _pslink_decoder_script = _pslink_decoder_scripts / _pslink_decoder_name
    if not _pslink_decoder_script.is_file():
        raise SystemExit(f"Missing required decoder patch: {_pslink_decoder_script}")
    _pslink_decoder_subprocess.run(
        [_pslink_decoder_sys.executable, str(_pslink_decoder_script)],
        check=True,
    )

# PSLINK_STREAM_RECORDING_OVERLAY_AUTORUN_V1
import pathlib as _pslink_stream_recording_pathlib
import subprocess as _pslink_stream_recording_subprocess
import sys as _pslink_stream_recording_sys
_pslink_stream_recording_script = _pslink_stream_recording_pathlib.Path(__file__).resolve().parent / "prepare_pslink_stream_recording.py"
if not _pslink_stream_recording_script.is_file():
    raise SystemExit(f"Missing PS Link stream recorder overlay: {_pslink_stream_recording_script}")
_pslink_stream_recording_subprocess.run(
    [_pslink_stream_recording_sys.executable, str(_pslink_stream_recording_script)],
    check=True,
)

# PSLINK_LIVE_TV_STREAMING_V1
# This must run after the existing native stream/audio recorder overlays: the live
# callback bridge shares the decoded PCM callback installed by that single pipeline.
import pathlib as _pslink_live_tv_pathlib
import runpy as _pslink_live_tv_runpy
_pslink_live_tv_script = _pslink_live_tv_pathlib.Path(__file__).resolve().parent / "prepare_pslink_live_tv_streaming.py"
if not _pslink_live_tv_script.is_file():
    raise SystemExit(f"Missing PS Link live TV streaming overlay: {_pslink_live_tv_script}")
_pslink_live_tv_runpy.run_path(str(_pslink_live_tv_script), run_name="__main__")
