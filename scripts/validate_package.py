#!/usr/bin/env python3
"""Validate PS Link source structure and known compile-time integration contracts."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "mobile/build.gradle.kts",
    "mobile/src/main/AndroidManifest.xml",
    "mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt",
    "mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt",
    "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt",
    "mobile/src/main/res/layout/activity_translation.xml",
    "mobile/src/main/res/layout/pslink_main_layout.xml",
    "mobile/src/main/res/values-ar/strings.xml",
    "core/shared/build.gradle.kts",
    "core/shared/src/main/java/com/pslink/core/PsLinkProtocol.kt",
    "core/shared/src/main/java/com/pslink/core/StableTextGate.kt",
    "core/shared/src/test/java/com/pslink/core/StableTextGateTest.kt",
    "tv/app/build.gradle.kts",
    "tv/app/src/main/AndroidManifest.xml",
    "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt",
    "tv/app/src/main/res/layout/activity_tv_receiver.xml",
    "scripts/bootstrap.sh",
    "scripts/prepare_upstream.py",
    "scripts/check-source.sh",
    "scripts/check-ps5-wiring.sh",
    ".github/workflows/android.yml",
    "README.md",
]
missing = [p for p in required if not (ROOT / p).is_file()]
if missing:
    print("Missing required files:", *missing, sep="\n  ", file=sys.stderr)
    raise SystemExit(1)

xml_files = list(ROOT.rglob("*.xml"))
parse_errors = []
for path in xml_files:
    # The pinned upstream checkout may contain build or vendor XML; parse all source XML.
    if ".gradle" in path.parts or "build" in path.parts:
        continue
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        parse_errors.append(f"{path.relative_to(ROOT)}: {exc}")
if parse_errors:
    print("Invalid XML:", *parse_errors, sep="\n  ", file=sys.stderr)
    raise SystemExit(2)

settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
for module in (':core:shared', ':mobile', ':tv:app'):
    if module not in settings:
        raise SystemExit(f"Gradle settings do not include {module}")

mobile_manifest = (ROOT / "mobile/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
tv_manifest = (ROOT / "tv/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
if 'android:name="com.metallic.chiaki.main.MainActivity"' not in mobile_manifest:
    raise SystemExit("Mobile manifest is missing the upstream Chiaki MainActivity")
if 'com.pslink.mobile.TranslationActivity' not in mobile_manifest:
    raise SystemExit("Mobile manifest is missing TranslationActivity")
if 'android:name=".TvReceiverActivity"' not in tv_manifest:
    raise SystemExit("TV manifest is missing TvReceiverActivity")
if 'android:allowBackup="false"' not in mobile_manifest:
    raise SystemExit("Mobile manifest must disable Auto Backup for Remote Play registration material")

bootstrap = (ROOT / "scripts/bootstrap.sh").read_text(encoding="utf-8")
if "43728af2e639fecb8a7af1cd68c29820a40de973" not in bootstrap:
    raise SystemExit("Chiaki-NG commit pin is missing or changed")

mobile_gradle = (ROOT / "mobile/build.gradle.kts").read_text(encoding="utf-8")
if 'applicationId = "com.pslink.app"' not in mobile_gradle:
    raise SystemExit("Unexpected mobile application ID")
tv_gradle = (ROOT / "tv/app/build.gradle.kts").read_text(encoding="utf-8")
if 'applicationId = "com.pslink.tv"' not in tv_gradle:
    raise SystemExit("Unexpected TV application ID")

translation = (ROOT / "mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt").read_text(encoding="utf-8")
if "import com.google.mlkit.common.model.DownloadConditions" not in translation:
    raise SystemExit("TranslationActivity must import DownloadConditions from com.google.mlkit.common.model")
if "import com.google.mlkit.nl.translate.DownloadConditions" in translation:
    raise SystemExit("Obsolete ML Kit DownloadConditions import remains")

main_layout = (ROOT / "mobile/src/main/res/layout/pslink_main_layout.xml").read_text(encoding="utf-8")
for binding_id in ("translationDashboardButton", "floatingActionButton", "floatingActionButtonDial", "hostsRecyclerView"):
    if f"@+id/{binding_id}" not in main_layout:
        raise SystemExit(f"Mobile home layout is missing required binding id: {binding_id}")
protocol = (ROOT / "core/shared/src/main/java/com/pslink/core/PsLinkProtocol.kt").read_text(encoding="utf-8")
if '.take(4000)' not in protocol:
    raise SystemExit("Subtitle packet inbound length guard is missing")

ar = (ROOT / "mobile/src/main/res/values-ar/strings.xml").read_text(encoding="utf-8")
for resource in ("regist_pin_instructions_ps5_navigation", "hint_regist_psn_account_id", "regist_info_success", "psn_oauth_login", "psn_oauth_help", "psn_oauth_redirect_hint", "psn_oauth_extract", "psn_oauth_success", "psn_oauth_redirect_required", "psn_oauth_invalid_redirect", "psn_oauth_authorization_denied", "psn_oauth_http_error", "psn_oauth_missing_access_token", "psn_oauth_missing_user_id", "psn_oauth_invalid_response", "psn_oauth_network_error", "psn_oauth_browser_unavailable"):
    if f'name="{resource}"' not in ar:
        raise SystemExit(f"Arabic PS5 registration resource is missing: {resource}")

print(f"Validated {len(required)} required files and {len(xml_files)} XML files.")
print("Application IDs: com.pslink.app / com.pslink.tv")
print("Pinned upstream Chiaki-NG commit is present.")


# lifecycleScope is used by the PSN registration workflow and must be explicit.
mobile_gradle = (ROOT / "mobile/build.gradle.kts").read_text(encoding="utf-8")
if "implementation(libs.lifecycle.runtime.ktx)" not in mobile_gradle:
    raise SystemExit("PSN registration requires the direct lifecycle-runtime-ktx dependency")

# PSN OAuth must remain browser-based; never collect or persist the PSN password/token.
psn_oauth = (ROOT / "mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt").read_text(encoding="utf-8")
for snippet in ("AUTHORIZATION_ENDPOINT", "TOKEN_ENDPOINT", "remoteplay.dl.playstation.net/remoteplay/redirect", "ByteOrder.LITTLE_ENDIAN", "Base64.NO_WRAP", "instanceFollowRedirects = false"):
    if snippet not in psn_oauth:
        raise SystemExit(f"PSN OAuth client is missing required secure-flow component: {snippet}")
for forbidden in ("android.webkit.WebView", "SharedPreferences", "putString(\"access_token\""):
    if forbidden in psn_oauth:
        raise SystemExit(f"PSN OAuth client must not embed a login WebView or persist tokens: {forbidden}")
prep = (ROOT / "scripts/prepare_upstream.py").read_text(encoding="utf-8")
if "PsnAccountIdClient.accountIdFromRedirect" not in prep or "psnLoginButton" not in prep:
    raise SystemExit("PSN OAuth native registration UI patch is missing from prepare_upstream.py")
print("PSN browser OAuth and Account-ID extraction checks passed.")
