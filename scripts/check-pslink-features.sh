#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
need() {
  local file="$1" marker="$2"
  [[ -f "$ROOT/$file" ]] || { echo "Missing feature file: $file" >&2; exit 1; }
  grep -Fq -- "$marker" "$ROOT/$file" || { echo "Feature marker missing in $file: $marker" >&2; exit 1; }
}
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'PixelCopy.request(surface, bitmap'
need mobile/src/main/java/com/pslink/mobile/LiveCaptionOverlayView.kt 'TextDirectionHeuristics.RTL'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'translator.translate(line.source).awaitResult()'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'activeTranslator = if (arabicOcr) null else Translation.getClient('
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'assets.open("tessdata/ara.traineddata")'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'activity.cacheDir.absolutePath + File.separator'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'TessBaseAPI.OEM_DEFAULT'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'StableTextGate(requiredMatches = 2)'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'stableTextGate.offer(signature)'
need mobile/src/main/java/com/pslink/mobile/PsLinkScreenshot.kt 'MediaStore.Images.Media.RELATIVE_PATH'
need mobile/src/main/java/com/pslink/mobile/capture/ScreenRecordingService.kt 'MediaProjectionManager'
need mobile/src/main/java/com/pslink/mobile/capture/ScreenRecordingService.kt 'MediaRecorder.VideoEncoder.H264'
need scripts/prepare_pslink_upstream.py 'psLinkNotificationPermissionLauncher'
need mobile/src/main/java/com/pslink/mobile/capture/VideoEditorActivity.kt 'MediaMuxer'
need mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt 'mappedDpadMask'
need mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt 'ACTION_MOVE'
need mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt 'R.id.crossButtonView'
need mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt 'R.id.pyramidButtonView'
need scripts/prepare_pslink_upstream.py 'setupPsLinkFeatures()'
need scripts/prepare_pslink_audio_recording.py 'PSLINK_NATIVE_AUDIO_RECORDER_JNI_V1'
need scripts/prepare_pslink_audio_recording.py 'pslink_annexb_to_length_prefixed'
need scripts/prepare_pslink_upstream.py 'setControlCustomizationMode'
need scripts/prepare_pslink_upstream.py 'psLinkToolsBar.isGone = true'
need core/shared/src/main/java/com/pslink/core/PsLinkProtocol.kt 'const val KIND_ACK = "ack"'
need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt 'registerDiscovery()'
need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt 'pair_code'
need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'MediaCodec.createDecoderByType'
need tv/app/src/main/java/com/pslink/tv/TvCaptionOverlayView.kt 'TextDirectionHeuristics.RTL'
need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'sendVideoSample'
need mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt 'discoverServices("_pslink._tcp."'
need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'pendingMessageIds'
need mobile/src/main/AndroidManifest.xml 'android:foregroundServiceType="mediaProjection"'
need mobile/src/main/res/layout/activity_pslink_video_editor.xml '@+id/saveEditedVideoButton'
need scripts/verify-apk.sh 'APK_VALIDATION_OK'

bash -n "$ROOT/scripts/verify-apk.sh"
if [[ -f "$ROOT/scripts/diagnose-apk-termux.sh" ]]; then bash -n "$ROOT/scripts/diagnose-apk-termux.sh"; fi
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
files = [
    "mobile/src/main/AndroidManifest.xml",
    "tv/app/src/main/AndroidManifest.xml",
    "tv/app/src/main/res/layout/activity_tv_receiver.xml",
    "mobile/src/main/res/layout/activity_translation.xml",
    "mobile/src/main/res/layout/activity_pslink_video_editor.xml",
]
for rel in files:
    ET.parse(root / rel)
print("Android XML parsing passed for manifests and edited layouts.")
PY
if [[ -f "$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt" ]]; then
  grep -Fq 'PixelCopy' "$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt" && {
    echo "Unexpected direct PixelCopy use in Activity; helper owns frame sampling."
    exit 1
  } || true
  grep -Fq 'psLinkLiveOcrButton' "$ROOT/third_party/chiaki-ng/android/app/src/main/res/layout/activity_stream.xml"
  grep -Fq 'mappedButtonStateChanged' "$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
  grep -Fq 'setControlCustomizationMode' "$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
  echo "Pinned Chiaki stream UI and touch-control overlays passed source wiring checks."
else
  echo "Chiaki overlay checks deferred; run scripts/bootstrap.sh first."
fi

need tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt 'Frame.Binary'
need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'AudioTrack.Builder'
need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'MEDIA_AUDIO_PCM16'
need scripts/prepare_pslink_live_tv_streaming.py 'PSLINK_LIVE_TV_STREAMING_V1'
need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'captionGeneration'
need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'VIDEO_QUEUE_CAPACITY = 96'
need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'AudioTrack.Builder'
need mobile/src/main/res/layout/pslink_main_layout.xml 'app:layout_behavior="com.metallic.chiaki.main.FloatingActionButtonBackgroundBehavior"'
need mobile/src/main/res/layout/pslink_main_layout.xml 'app:layout_behavior="com.metallic.chiaki.main.FloatingActionButtonSpeedDialBehavior"'
echo "PS Link feature static checks passed. Gradle compilation and live PS5/TV hardware tests are still required."

# PSLINK_COMPLETE_RUNTIME_FIX_V1 — regressions that previously escaped the static checks.
if [[ ! -d "$ROOT/third_party/chiaki-ng" ]]; then
  echo "Chiaki complete-fix checks deferred; run scripts/bootstrap.sh first."
else
  CHI_STREAM="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
  CHI_CONTROLS="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
  CHI_STATE="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt"
  CHI_DECODER="$ROOT/third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.c"
  [[ -f "$CHI_STREAM" && -f "$CHI_CONTROLS" && -f "$CHI_STATE" && -f "$CHI_DECODER" ]] || {
    echo "PS Link complete-fix source files are missing after bootstrap." >&2
    exit 1
  }
  grep -Fq 'controlsSources' "$CHI_STREAM"
  grep -Fq 'mergeControllerStates(controlsSources.toList())' "$CHI_STREAM"
  grep -Fq 'import com.metallic.chiaki.touchcontrols.mergeControllerStates' "$CHI_STREAM"
  grep -Fq 'fun mergeControllerStates(sources: List<Flow<ControllerState>>)' "$CHI_CONTROLS"
  grep -Fq 'touches = touches.map { it.copy() }.toTypedArray()' "$CHI_STATE"
  grep -Fq 'psLinkMenuButton' "$CHI_STREAM"
  grep -Fq 'PSLINK_CLEAN_STREAM_OVERLAY_V1' "$CHI_STREAM"
  grep -Fq 'private var psLinkRecordingActive' "$CHI_STREAM"
  grep -Fq 'psLinkStartGameRecording()' "$CHI_STREAM"
  grep -Fq 'startVideoRecording(openedDescriptor.fd)' "$CHI_STREAM"
  grep -Fq 'stopVideoRecording()' "$CHI_STREAM"
  grep -Fq 'PSLINK_NATIVE_STREAM_RECORDER_V1' "$CHI_DECODER"
  grep -Fq 'PSLINK_NATIVE_AUDIO_RECORDER_JNI_V1' "$ROOT/third_party/chiaki-ng/android/app/src/main/cpp/chiaki-jni.c"
  grep -Fq 'PSLINK_NATIVE_AUDIO_CALLBACK_BOUNDS_V1' "$ROOT/scripts/prepare_pslink_live_tv_streaming.py"
  grep -Fq 'ChiakiPcmHlsBridge.publish(data, sampleRate, channels, ptsNs)' "$CHI_STREAM"
  if grep -Eq 'MediaProjectionManager|ScreenRecordingService' "$CHI_STREAM"; then
    echo "Active stream screen still references the phone-display recorder; expected native Chiaki game-stream recording." >&2
    exit 1
  fi
fi
grep -Fq 'تعديل الشفافية' "$ROOT/mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt"
grep -Fq 'consecutiveCaptureFailures' "$ROOT/mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
grep -Fq 'val sourceTracks = (0 until extractor.trackCount)' "$ROOT/mobile/src/main/java/com/pslink/mobile/capture/VideoEditorActivity.kt"
grep -Fq 'class ControllerStateMergeTest' "$ROOT/mobile/src/test/java/com/metallic/chiaki/touchcontrols/ControllerStateMergeTest.kt"
echo "PS Link translation, game-stream recording, audio-preserving trim and controller regression checks passed."

# PSLINK_PROFESSIONAL_UPGRADE_GUARDS_V1
LIVE_TRANSLATOR="$ROOT/mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
TRANSLATION_ACTIVITY="$ROOT/mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt"
SCREENSHOT="$ROOT/mobile/src/main/java/com/pslink/mobile/PsLinkScreenshot.kt"
[[ "$(grep -Fc 'tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)' "$LIVE_TRANSLATOR")" -eq 3 ]] || {
  echo "Expected one empty-caption clear packet per Arabic-OCR, Arabic-error and translated-OCR path." >&2
  exit 1
}
grep -Fq 'requestGeneration != translatorGeneration' "$TRANSLATION_ACTIVITY"
grep -Fq 'requestGeneration != ocrGeneration' "$TRANSLATION_ACTIVITY"
grep -Fq 'surface.isAttachedToWindow' "$SCREENSHOT"
grep -Fq 'surface.holder.surface.isValid' "$SCREENSHOT"
grep -Fq 'activity.isDestroyed' "$SCREENSHOT"
grep -Fq 'overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)' "$TRANSLATION_ACTIVITY"
grep -Fq 'PhoneReceiverServer.publishCaptionsToReceivers(' "$LIVE_TRANSLATOR"
echo "PS Link stale-callback, screenshot lifecycle, navigation transition and caption-clear regression guards passed."
