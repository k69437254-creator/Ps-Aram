#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash scripts/bootstrap.sh

if [[ -x ./gradlew ]]; then
  GRADLE=(bash ./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "ERROR: Gradle not found. Run bash scripts/bootstrap.sh, then install Gradle or use GitHub Actions." >&2
  exit 2
fi

"${GRADLE[@]}" --no-daemon --stacktrace :mobile:assembleDebug :tv:app:assembleDebug

echo
echo "Build complete. APK files:"
find mobile/build/outputs/apk tv/app/build/outputs/apk -type f -name '*.apk' -print 2>/dev/null || true
