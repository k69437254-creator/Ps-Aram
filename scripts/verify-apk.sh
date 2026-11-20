#!/usr/bin/env bash
set -Eeuo pipefail
APK="${1:?Usage: verify-apk.sh <apk-path> <application-id>}"
EXPECTED="${2:?Usage: verify-apk.sh <apk-path> <application-id>}"
[[ -s "$APK" ]] || { echo "APK missing or empty" >&2; exit 1; }
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
for tool in "$AAPT" "$APKSIGNER" "$ZIPALIGN"; do [[ -x "$tool" ]] || { echo "Missing Android tool: $tool" >&2; exit 1; }; done
unzip -tqq "$APK"
BADGING="$("$AAPT" dump badging "$APK")"
grep -F "package: name='$EXPECTED'" <<<"$BADGING" >/dev/null || { echo "Wrong application ID; expected $EXPECTED" >&2; grep '^package:' <<<"$BADGING" >&2 || true; exit 1; }
"$ZIPALIGN" -c -v 4 "$APK"
"$APKSIGNER" verify --verbose "$APK"
APK_ENTRIES="$(unzip -Z1 "$APK")"
ABIS="$(awk -F/ '$1 == "lib" && NF >= 3 {print $2}' <<<"$APK_ENTRIES" | sort -u | paste -sd, -)"
if [[ "$EXPECTED" == "com.pslink.app" ]]; then
  grep -Eq '^lib/[^/]+/libchiaki-jni\.so$' <<<"$APK_ENTRIES" || { echo "Chiaki JNI library is missing from the Mobile APK." >&2; exit 1; }
  [[ -n "$ABIS" ]] || { echo "Mobile APK contains no native ABI libraries." >&2; exit 1; }
else
  ABIS="${ABIS:-none (TV app is JVM-only)}"
fi
echo "APK_VALIDATION_OK package=$EXPECTED abis=$ABIS bytes=$(wc -c < "$APK" | tr -d ' ')"
