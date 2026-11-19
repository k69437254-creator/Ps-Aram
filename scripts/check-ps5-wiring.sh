#!/usr/bin/env bash
# Static integration guard for the pinned Chiaki-NG PS5 Remote Play path.
# This validates source wiring only; it does not replace a real PS5 test.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$ROOT/third_party/chiaki-ng"
PIN="43728af2e639fecb8a7af1cd68c29820a40de973"

fail() { echo "PS5 WIRING ERROR: $*" >&2; exit 1; }
need_file() { [[ -f "$1" ]] || fail "Missing required file: ${1#"$ROOT"/}"; }
need_text() {
  local file="$1" text="$2" label="$3"
  grep -Fq -- "$text" "$file" || fail "$label (${file#"$ROOT"/})"
}

git -C "$UPSTREAM" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Chiaki-NG checkout missing. Run: bash scripts/bootstrap.sh"
ACTUAL_PIN="$(git -C "$UPSTREAM" rev-parse HEAD)"
[[ "$ACTUAL_PIN" == "$PIN" ]] || fail "Chiaki-NG commit mismatch: expected $PIN, got $ACTUAL_PIN"

FILES=(
  "$ROOT/mobile/build.gradle.kts"
  "$ROOT/mobile/src/main/AndroidManifest.xml"
  "$ROOT/mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt"
  "$ROOT/mobile/src/main/res/layout/pslink_main_layout.xml"
  "$ROOT/mobile/src/main/res/values-ar/strings.xml"
  "$ROOT/scripts/prepare_upstream.py"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/main/MainActivity.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/discovery/DiscoveryManager.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/regist/RegistActivity.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/regist/RegistExecuteViewModel.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt"
  "$UPSTREAM/android/app/src/main/java/com/metallic/chiaki/session/StreamInput.kt"
)
for f in "${FILES[@]}"; do need_file "$f"; done

python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
up = root / "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki"

def require(path: Path, snippet: str, reason: str) -> None:
    text = path.read_text(encoding="utf-8")
    if snippet not in text:
        raise SystemExit(f"PS5 WIRING ERROR: {reason}: {path}")

# Native Android source, JNI/CMake and upstream app sources must be connected to :mobile.
gradle = root / "mobile/build.gradle.kts"
require(gradle, 'java.srcDir(chiakiAndroidApp.dir("src/main/java"))', "Upstream Kotlin sources are not included")
require(gradle, 'res.srcDir(chiakiAndroidApp.dir("src/main/res"))', "Upstream Android resources are not included")
require(gradle, 'path = file("../third_party/chiaki-ng/CMakeLists.txt")', "Chiaki native CMake project is not linked")
require(gradle, '"-DCHIAKI_ENABLE_ANDROID=ON"', "Chiaki Android native target is disabled")

# App launcher must retain all upstream Remote Play screens and the PS Link translation screen.
manifest = (root / "mobile/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for name in (
    "com.metallic.chiaki.main.MainActivity",
    "com.metallic.chiaki.stream.StreamActivity",
    "com.metallic.chiaki.regist.RegistActivity",
    "com.metallic.chiaki.regist.RegistExecuteActivity",
    "com.metallic.chiaki.settings.SettingsActivity",
    "com.metallic.chiaki.manualconsole.EditManualConsoleActivity",
    "com.pslink.mobile.TranslationActivity",
):
    if name not in manifest:
        raise SystemExit(f"PS5 WIRING ERROR: Activity is absent from mobile manifest: {name}")
for permission in ("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"):
    if permission not in manifest:
        raise SystemExit(f"PS5 WIRING ERROR: Missing permission {permission}")

# Home dashboard button must enter the PS Link translation module without replacing Chiaki's host list.
main = up / "main/MainActivity.kt"
require(main, "binding.translationDashboardButton.setOnClickListener", "Translation dashboard button is not wired")
require(main, "RegistActivity::class.java", "PS5 registration screen is not wired")
require(main, "StreamActivity::class.java", "Remote Play stream screen is not wired")
require(main, "ConnectInfo(host.isPS5", "Saved host credentials are not passed into the stream session")

# Discovery, PS5 registration and credential persistence.
discovery = up / "discovery/DiscoveryManager.kt"
require(discovery, 'InetSocketAddress("255.255.255.255", PORT)', "Local PlayStation discovery broadcast is missing")
require(discovery, "DiscoveryService.wakeup", "PS5 wake-up path is missing")
registration = up / "regist/RegistActivity.kt"
require(registration, "private const val PIN_LENGTH = 8", "PS5 8-digit Link Device code validation is missing")
require(registration, "ConsoleVersion.PS5", "PS5 registration option/default is missing")
require(registration, "Base64.decode(psnId, Base64.DEFAULT)", "PSN Account-ID decoding is missing")
require(registration, "RegistInfo.ACCOUNT_ID_SIZE", "PSN Account-ID size validation is missing")
require(registration, "Target.PS5_1", "PS5 registration target is missing")
registration_vm = up / "regist/RegistExecuteViewModel.kt"
require(registration_vm, "Regist(info, log.log, this::registEvent)", "Native Chiaki registration call is missing")
require(registration_vm, "RegisteredHost(host)", "Registration credentials are not persisted")

# Remote Play session and input path must reach Chiaki's native session API.
stream = up / "stream/StreamActivity.kt"
require(stream, "dispatchKeyEvent(event)", "Hardware keyboard/controller key events are not routed")
require(stream, "onGenericMotionEvent(event)", "Bluetooth gamepad analog events are not routed")
session = up / "session/StreamSession.kt"
require(session, "Session(connectInfo", "Native Chiaki session creation is missing")
require(session, "session.start()", "Native Chiaki session start is missing")
require(session, "session.setSurface(surface)", "Decoded stream surface is not attached")
require(session, "session?.setControllerState(it)", "Controller state is not passed to Chiaki")
inputs = up / "session/StreamInput.kt"
require(inputs, "KeyEvent.KEYCODE_BUTTON_A", "Gamepad button mapping is missing")
require(inputs, "MotionEvent.AXIS_X", "Analog stick mapping is missing")

# Build must use the corrected ML Kit type; this caught the last CI failure.
translation = (root / "mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt").read_text(encoding="utf-8")
if "import com.google.mlkit.common.model.DownloadConditions" not in translation:
    raise SystemExit("PS5 WIRING ERROR: DownloadConditions import is not the ML Kit common.model type")
if "import com.google.mlkit.nl.translate.DownloadConditions" in translation:
    raise SystemExit("PS5 WIRING ERROR: obsolete DownloadConditions import remains")

# Prevent Android Auto Backup from copying registered Remote Play keys to a cloud backup.
if 'android:allowBackup="false"' not in manifest:
    raise SystemExit("PS5 WIRING ERROR: registered Remote Play credentials should not be included in Auto Backup")

print("PS5 Remote Play static wiring checks passed:")
print("- pinned Chiaki-NG native engine and Android sources")
print("- local discovery and wake-up path")
print("- PS5 8-digit registration and PSN Account-ID validation")
print("- persisted registration credentials and native stream session")
print("- gamepad input path and corrected ML Kit import")
print("NOTE: this is a source-level check, not a live PS5 connection test.")
PY
