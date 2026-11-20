#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
need() {
  local file="$1" marker="$2"
  [[ -f "$ROOT/$file" ]] || { echo "Missing feature file: $file" >&2; exit 1; }
  grep -Fq -- "$marker" "$ROOT/$file" || { echo "Feature marker missing in $file: $marker" >&2; exit 1; }
}
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'PixelCopy.request(surface, bitmap'
need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'newTranslator.translate(text).awaitResult()'
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
need scripts/prepare_pslink_upstream.py 'setControlCustomizationMode'
need scripts/prepare_pslink_upstream.py 'psLinkToolsBar.isGone = true'
need core/shared/src/main/java/com/pslink/core/PsLinkProtocol.kt 'const val KIND_ACK = "ack"'
need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt 'registerDiscovery()'
need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt 'pair_code'
need mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt 'discoverServices("_pslink._tcp."'
need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'pendingMessageId'
need mobile/src/main/AndroidManifest.xml 'android:foregroundServiceType="mediaProjection"'
need mobile/src/main/res/layout/activity_pslink_video_editor.xml '@+id/saveEditedVideoButton'
need scripts/verify-apk.sh 'APK_VALIDATION_OK'

bash -n "$ROOT/scripts/verify-apk.sh" "$ROOT/scripts/diagnose-apk-termux.sh"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
root = Path(sys.argv[1])
files = [
    "mobile/src/main/AndroidManifest.xml",
    "tv/app/src/main/AndroidManifest.xml",
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

echo "PS Link feature static checks passed. Gradle compilation and live hardware tests are still required."
