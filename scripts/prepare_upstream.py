#!/usr/bin/env python3
"""Apply the PS Link branding bridge to the pinned, untouched Chiaki-NG checkout."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / "third_party" / "chiaki-ng"
APP = UPSTREAM / "android" / "app"

if not (UPSTREAM / "CMakeLists.txt").is_file():
    raise SystemExit("Chiaki-NG source is missing. Run scripts/bootstrap.sh first.")

# Keep upstream discovery, registration, controller and native streaming implementations intact.
layout_source = ROOT / "mobile/src/main/res/layout/pslink_main_layout.xml"
layout_target = APP / "src/main/res/layout/activity_main.xml"
layout_target.write_text(layout_source.read_text(encoding="utf-8"), encoding="utf-8")

colors = APP / "src/main/res/values/colors.xml"
colors.write_text("""<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <color name=\"primary\">#101827</color>
    <color name=\"primary_dark\">#070B14</color>
    <color name=\"accent\">#1677FF</color>
    <color name=\"stream_text\">#FFFFFFFF</color>
    <color name=\"stream_background\">#FF000000</color>
    <color name=\"stream_overlay_background\">#99030710</color>
    <color name=\"floating_action_button_speed_dial_background\">#152238</color>
    <color name=\"floating_action_button_speed_dial_tint\">#F4F7FC</color>
    <color name=\"control_primary\">#33FFFFFF</color>
    <color name=\"control_pressed\">#99FFFFFF</color>
    <color name=\"item_delete_background\">#B71C1C</color>
</resources>
""", encoding="utf-8")

strings = APP / "src/main/res/values/strings.xml"
text = strings.read_text(encoding="utf-8")
text = text.replace('<string name="app_name">Chiaki</string>', '<string name="app_name">PS Link</string>')
text = text.replace('<string name="title_main">Chiaki 千秋</string>', '<string name="title_main">PS Link</string>')
strings.write_text(text, encoding="utf-8")

styles = APP / "src/main/res/values/styles.xml"
text = styles.read_text(encoding="utf-8")
if '<item name="android:windowBackground">#070B14</item>' not in text:
    text = text.replace(
        '<item name="colorOnSecondary">#000000</item>',
        '<item name="colorOnSecondary">#FFFFFF</item>\n'
        '        <item name="colorSurface">#101827</item>\n'
        '        <item name="colorOnSurface">#F4F7FC</item>\n'
        '        <item name="android:windowBackground">#070B14</item>'
    , 1)
text = text.replace("Theme.MaterialComponents.NoActionBar", "Theme.MaterialComponents.DayNight.NoActionBar")
styles.write_text(text, encoding="utf-8")

# Keep the full upstream license text with the combined source checkout.
license_dir = ROOT / "LICENSES"
license_dir.mkdir(parents=True, exist_ok=True)
upstream_license = UPSTREAM / "COPYING"
if upstream_license.is_file():
    (license_dir / "AGPL-3.0.txt").write_text(upstream_license.read_text(encoding="utf-8"), encoding="utf-8")

main_activity = APP / "src/main/java/com/metallic/chiaki/main/MainActivity.kt"
text = main_activity.read_text(encoding="utf-8")
needle = '''\t\tbinding.floatingActionButton.setOnClickListener {
\t\t\texpandFloatingActionButton(!binding.floatingActionButton.isExpanded)
\t\t}
'''
addition = needle + '''\t\tbinding.translationDashboardButton.setOnClickListener {
\t\t\tstartActivity(Intent(this, com.pslink.mobile.TranslationActivity::class.java))
\t\t}
'''
if "binding.translationDashboardButton.setOnClickListener" not in text:
    if needle not in text:
        raise SystemExit("Could not locate the expected MainActivity insertion point; upstream source changed.")
    text = text.replace(needle, addition, 1)
main_activity.write_text(text, encoding="utf-8")

# Copy the upstream Gradle wrapper into the single-root project after source checkout.
wrapper_src = UPSTREAM / "android" / "gradle" / "wrapper"
wrapper_dst = ROOT / "gradle" / "wrapper"
wrapper_dst.mkdir(parents=True, exist_ok=True)
for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
    src = wrapper_src / name
    if not src.is_file():
        raise SystemExit(f"Required upstream Gradle wrapper file is missing: {src}")
    (wrapper_dst / name).write_bytes(src.read_bytes())
wrapper_script = UPSTREAM / "android" / "gradlew"
if not wrapper_script.is_file():
    raise SystemExit("Upstream gradlew script is missing.")
(ROOT / "gradlew").write_bytes(wrapper_script.read_bytes())
(ROOT / "gradlew").chmod(0o755)

print("Prepared Chiaki-NG mobile source, PS Link branding and Gradle wrapper.")
