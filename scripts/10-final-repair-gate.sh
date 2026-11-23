#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

REPORT_DIR="mobile/build/repair-audit"
REPORT="$REPORT_DIR/final-source-audit.txt"

mkdir -p "$REPORT_DIR"
: > "$REPORT"

exec > >(tee "$REPORT") 2>&1

echo "===== PS LINK FINAL SOURCE AUDIT ====="
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Branch: $(git branch --show-current)"
echo "Commit: $(git rev-parse HEAD)"
echo

ERRORS=0
CHECKED=0

echo "===== SHELL SCRIPT SYNTAX ====="

while IFS= read -r -d '' file; do
    CHECKED=$((CHECKED + 1))

    if bash -n "$file"; then
        echo "PASS: $file"
    else
        echo "FAIL: $file"
        ERRORS=$((ERRORS + 1))
    fi
done < <(
    find . \
        -type d \( -name .git -o -name build -o -name .gradle \) -prune \
        -o -type f -name '*.sh' -print0
)

echo
echo "Shell scripts checked: $CHECKED"

if [[ "$CHECKED" -eq 0 ]]; then
    echo "FAIL: No shell scripts were discovered."
    ERRORS=$((ERRORS + 1))
fi

echo
echo "===== REQUIRED ANDROID SOURCE FILES ====="

REQUIRED_FILES=(
    "mobile/src/main/AndroidManifest.xml"
    "mobile/build.gradle.kts"
    "mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt"
    "mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt"
    "mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt"
    "mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt"
    "mobile/src/test/java/com/pslink/mobile/capture/HlsTransportStreamPublisherTest.kt"
    ".github/workflows/android.yml"
    "scripts/verify-hls-ts.sh"
)

for file in "${REQUIRED_FILES[@]}"; do
    if [[ -s "$file" ]]; then
        echo "PRESENT: $file"
    else
        echo "MISSING: $file"
        ERRORS=$((ERRORS + 1))
    fi
done

echo
echo "===== HLS/AAC TEST COVERAGE MARKERS ====="

TEST_FILE="mobile/src/test/java/com/pslink/mobile/capture/HlsTransportStreamPublisherTest.kt"

for marker in \
    "writesAacAudioPesAndDeclaresAacInPmt" \
    "checksContinuityCountersForEveryTransportStreamPid" \
    "writesRealAacFixtureAndH264IntoTransportStream"
do
    if grep -Fq "$marker" "$TEST_FILE"; then
        echo "PRESENT: $marker"
    else
        echo "MISSING: $marker"
        ERRORS=$((ERRORS + 1))
    fi
done

echo
echo "===== FINAL RESULT ====="
echo "Shell scripts checked: $CHECKED"
echo "Errors: $ERRORS"
echo "Report: $REPORT"

if [[ "$ERRORS" -ne 0 ]]; then
    echo "FAIL: Final source audit found errors."
    exit 1
fi

echo "PASS: Final source audit completed."
echo "NOTE: This is source-level validation, not device validation."
