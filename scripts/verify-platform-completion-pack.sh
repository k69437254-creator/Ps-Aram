#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "Checking platform target JSON..."
python3 -m json.tool platforms/targets.json >/dev/null

echo "Checking required receiver files..."
FILES=(
  receivers/roku/manifest
  receivers/roku/source/main.brs
  receivers/roku/source/MainScene.xml
  receivers/roku/source/MainScene.brs
  receivers/roku/source/components/PairingTask.xml
  receivers/roku/source/components/PairingTask.brs
  receivers/xbox-edge/index.html
  receivers/xbox-edge/app.js
  receivers/xbox-edge/app.css
  receivers/xbox-edge/pslink-client.js
  mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt
)

for f in "${FILES[@]}"; do
  test -s "$f" || {
    echo "MISSING OR EMPTY: $f" >&2
    exit 1
  }
done

echo "Checking Roku XML..."
python3 - <<'PY'
import xml.etree.ElementTree as ET
for f in [
    "receivers/roku/source/MainScene.xml",
    "receivers/roku/source/components/PairingTask.xml"
]:
    ET.parse(f)
    print("XML OK:", f)
PY

echo "Checking Kotlin endpoint and imports..."
grep -q 'import io.ktor.server.application.call' \
  mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt
grep -q 'post("/receiver/roku/stream-url")' \
  mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt

echo "Checking Xbox receiver transport..."
grep -q 'new window.PSLinkReceiver' receivers/xbox-edge/app.js
grep -q 'onStreamUrl' receivers/xbox-edge/app.js

echo "Platform scaffold checks passed."
echo "These checks do not replace Gradle builds or physical-device testing."
