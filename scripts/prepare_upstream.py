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

# --- PS Link PSN OAuth integration (idempotent overlay) ---
import xml.etree.ElementTree as ET

def patch_exactly_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"PSN OAuth overlay: expected one {label} anchor, found {count}")
    return source.replace(old, new, 1)

regist_activity = APP / "src/main/java/com/metallic/chiaki/regist/RegistActivity.kt"
regist_text = regist_activity.read_text(encoding="utf-8")
if "PsnAccountIdClient.accountIdFromRedirect" not in regist_text:
    imports = (
        "import androidx.lifecycle.lifecycleScope\n"
        "import com.pslink.mobile.PsnAccountIdClient\n"
        "import kotlinx.coroutines.Dispatchers\n"
        "import kotlinx.coroutines.launch\n"
        "import kotlinx.coroutines.withContext\n"
        "import android.widget.Toast\n"
    )
    regist_text = patch_exactly_once(
        regist_text, "import android.content.Intent\n",
        "import android.content.Intent\n" + imports,
        "RegistActivity imports"
    )
    regist_text = patch_exactly_once(
        regist_text,
        "\tprivate lateinit var binding: ActivityRegistBinding\n",
        "\tprivate lateinit var binding: ActivityRegistBinding\n"
        "\tprivate var psnAccountLookupRunning = false\n",
        "RegistActivity state"
    )
    regist_text = patch_exactly_once(
        regist_text,
        "\t\tbinding.registButton.setOnClickListener { doRegist() }\n",
        "\t\tbinding.registButton.setOnClickListener { doRegist() }\n"
        "\t\tbinding.psnLoginButton.setOnClickListener { openPsnLogin() }\n"
        "\t\tbinding.psnExtractAccountButton.setOnClickListener { extractPsnAccountId() }\n",
        "registration button listeners"
    )
    methods = '''\tprivate fun openPsnLogin()
\t{
\t\ttry
\t\t{
\t\t\tstartActivity(Intent(Intent.ACTION_VIEW, PsnAccountIdClient.loginUri()))
\t\t}
\t\tcatch (_: Exception)
\t\t{
\t\t\tToast.makeText(this, R.string.psn_oauth_browser_unavailable, Toast.LENGTH_LONG).show()
\t\t}
\t}

\tprivate fun extractPsnAccountId()
\t{
\t\tif(psnAccountLookupRunning)
\t\t\treturn
\t\tval redirectUrl = binding.psnRedirectUrlEditText.text?.toString()?.trim().orEmpty()
\t\tif(redirectUrl.isBlank())
\t\t{
\t\t\tbinding.psnRedirectUrlEditText.error = getString(R.string.psn_oauth_redirect_required)
\t\t\treturn
\t\t}

\t\tpsnAccountLookupRunning = true
\t\tbinding.psnExtractAccountButton.isEnabled = false
\t\tbinding.psnLoginButton.isEnabled = false
\t\tbinding.psnRedirectUrlEditText.error = null
\t\tlifecycleScope.launch {
\t\t\ttry
\t\t\t{
\t\t\t\tval accountId = withContext(Dispatchers.IO) {
\t\t\t\t\tPsnAccountIdClient.accountIdFromRedirect(redirectUrl)
\t\t\t\t}
\t\t\t\tbinding.psnIdEditText.setText(accountId)
\t\t\t\tbinding.psnRedirectUrlEditText.text?.clear()
\t\t\t\tToast.makeText(this@RegistActivity, R.string.psn_oauth_success, Toast.LENGTH_LONG).show()
\t\t\t}
\t\t\tcatch (error: PsnAccountIdClient.PsnAccountIdException)
\t\t\t{
\t\t\t\tbinding.psnRedirectUrlEditText.error = getString(when(error.failure)
\t\t\t\t{
\t\t\t\t\tPsnAccountIdClient.Failure.INVALID_REDIRECT -> R.string.psn_oauth_invalid_redirect
\t\t\t\t\tPsnAccountIdClient.Failure.AUTHORIZATION_DENIED -> R.string.psn_oauth_authorization_denied
\t\t\t\t\tPsnAccountIdClient.Failure.TOKEN_EXCHANGE,
\t\t\t\t\tPsnAccountIdClient.Failure.ACCOUNT_LOOKUP -> R.string.psn_oauth_http_error
\t\t\t\t\tPsnAccountIdClient.Failure.MISSING_ACCESS_TOKEN -> R.string.psn_oauth_missing_access_token
\t\t\t\t\tPsnAccountIdClient.Failure.MISSING_USER_ID -> R.string.psn_oauth_missing_user_id
\t\t\t\t\tPsnAccountIdClient.Failure.INVALID_RESPONSE -> R.string.psn_oauth_invalid_response
\t\t\t\t}, error.httpStatus ?: 0)
\t\t\t}
\t\t\tcatch (_: Exception)
\t\t\t{
\t\t\t\tbinding.psnRedirectUrlEditText.error = getString(R.string.psn_oauth_network_error)
\t\t\t}
\t\t\tfinally
\t\t\t{
\t\t\t\tpsnAccountLookupRunning = false
\t\t\t\tbinding.psnExtractAccountButton.isEnabled = true
\t\t\t\tbinding.psnLoginButton.isEnabled = true
\t\t\t}
\t\t}
\t}

'''
    regist_text = patch_exactly_once(
        regist_text, "\tprivate fun doRegist()\n", methods + "\tprivate fun doRegist()\n",
        "PSN OAuth activity methods"
    )
    regist_activity.write_text(regist_text, encoding="utf-8")

regist_layout = APP / "src/main/res/layout/activity_regist.xml"
layout_text = regist_layout.read_text(encoding="utf-8")
if "@+id/psnLoginButton" not in layout_text:
    controls = '''            <com.google.android.material.button.MaterialButton
                android:id="@+id/psnLoginButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/psn_oauth_login"
                style="@style/MageTheme.Button"
                android:layout_marginTop="12dp"
                app:layout_constraintTop_toBottomOf="@id/psnAccountIdHelpURLTextView" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/psnOAuthHelpTextView"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/psn_oauth_help"
                android:gravity="center"
                android:textAppearance="?attr/textAppearanceBody2"
                android:alpha="0.75"
                android:layout_marginTop="8dp"
                app:layout_constraintTop_toBottomOf="@id/psnLoginButton" />

            <com.google.android.material.textfield.TextInputLayout
                android:id="@+id/psnRedirectUrlTextInputLayout"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="@string/psn_oauth_redirect_hint"
                app:layout_constraintTop_toBottomOf="@id/psnOAuthHelpTextView"
                android:layout_marginTop="8dp">
                <com.google.android.material.textfield.TextInputEditText
                    android:id="@+id/psnRedirectUrlEditText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:inputType="textUri|textNoSuggestions"
                    android:saveEnabled="false"
                    android:importantForAutofill="no"
                    android:minLines="2"
                    android:maxLines="3" />
            </com.google.android.material.textfield.TextInputLayout>

            <com.google.android.material.button.MaterialButton
                android:id="@+id/psnExtractAccountButton"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/psn_oauth_extract"
                style="@style/MageTheme.Button"
                android:layout_marginTop="8dp"
                app:layout_constraintTop_toBottomOf="@id/psnRedirectUrlTextInputLayout" />

'''
    # Insert the controls immediately after the help Group, tolerating XML indentation changes.
    import re as _psn_re
    group_pattern = _psn_re.compile(
        r'(?m)^([ \t]*)app:constraint_referenced_ids="psnAccountIdHelpTextView,psnAccountIdHelpURLTextView"\s*/>[ \t]*$'
    )
    group_matches = list(group_pattern.finditer(layout_text))
    if len(group_matches) != 1:
        raise SystemExit("PSN OAuth overlay: could not uniquely locate the PSN help Group")
    group_match = group_matches[0]
    layout_text = layout_text[:group_match.end()] + "\n" + controls.rstrip() + layout_text[group_match.end():]
    group_ref = 'app:constraint_referenced_ids="psnAccountIdHelpTextView,psnAccountIdHelpURLTextView"'
    if layout_text.count(group_ref) != 1:
        raise SystemExit("PSN OAuth overlay: PSN help Group reference list is ambiguous")
    layout_text = layout_text.replace(
        group_ref,
        'app:constraint_referenced_ids="psnAccountIdHelpTextView,psnAccountIdHelpURLTextView,psnLoginButton,psnOAuthHelpTextView,psnRedirectUrlTextInputLayout,psnExtractAccountButton"',
        1
    )
    psn_id_layout = layout_text.find('android:id="@+id/psnIdTextInputLayout"')
    old_constraint = 'app:layout_constraintTop_toBottomOf="@id/psnAccountIdHelpURLTextView"'
    constraint_index = layout_text.find(old_constraint, psn_id_layout)
    if psn_id_layout < 0 or constraint_index < 0:
        raise SystemExit("PSN OAuth overlay: Account-ID input constraint is missing")
    layout_text = (
        layout_text[:constraint_index]
        + 'app:layout_constraintTop_toBottomOf="@id/psnExtractAccountButton"'
        + layout_text[constraint_index + len(old_constraint):]
    )
    regist_layout.write_text(layout_text, encoding="utf-8")

english_strings = APP / "src/main/res/values/strings.xml"
english_text = english_strings.read_text(encoding="utf-8")
if 'name="psn_oauth_login"' not in english_text:
    resources = '''
    <string name="psn_oauth_login">Sign in to PlayStation Network</string>
    <string name="psn_oauth_help">Sign in with the PSN account you want to use. On Sony's redirect page, copy the full browser address and paste it below.</string>
    <string name="psn_oauth_redirect_hint">Sony Remote Play redirect URL</string>
    <string name="psn_oauth_extract">Get PSN Account ID</string>
    <string name="psn_oauth_success">PSN Account ID extracted. Continue with the PS5 Link Device code.</string>
    <string name="psn_oauth_redirect_required">Paste the complete Sony redirect URL first.</string>
    <string name="psn_oauth_invalid_redirect">This is not a valid Sony Remote Play redirect URL with an authorization code.</string>
    <string name="psn_oauth_authorization_denied">Sony did not authorize this sign-in. Sign in again and copy the new redirect URL.</string>
    <string name="psn_oauth_http_error">Sony rejected the request (HTTP %1$d). Sign in again and retry.</string>
    <string name="psn_oauth_missing_access_token">Sony did not return an access token. Sign in again.</string>
    <string name="psn_oauth_missing_user_id">Sony did not return a valid PSN user ID.</string>
    <string name="psn_oauth_invalid_response">Sony returned an unexpected response. Try again later.</string>
    <string name="psn_oauth_network_error">Could not contact Sony. Check your internet connection and try again.</string>
    <string name="psn_oauth_browser_unavailable">No browser is available to open PlayStation sign-in.</string>
    '''
    if "</resources>" not in english_text:
        raise SystemExit("PSN OAuth overlay: malformed upstream English strings.xml")
    english_strings.write_text(english_text.replace("</resources>", resources + "\n</resources>", 1), encoding="utf-8")

# Keep the native screen XML and Android resources parseable before Gradle runs.
ET.parse(regist_layout)
ET.parse(english_strings)
print("Applied PSN browser sign-in and Account-ID extraction to the pinned Chiaki-NG Android registration screen.")

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

# Apply the additional PS Link live translation, media capture and controls overlay.
import runpy as _pslink_runpy
_pslink_feature_overlay = ROOT / "scripts" / "prepare_pslink_upstream.py"
if _pslink_feature_overlay.is_file():
    _pslink_runpy.run_path(str(_pslink_feature_overlay), run_name="__main__")
