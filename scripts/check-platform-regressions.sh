#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Independent static gate for the Tizen/webOS receiver layer.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

echo "===== PS LINK CROSS-PLATFORM REGRESSION GATE ====="
echo "Repository: $ROOT"
echo

command -v node >/dev/null 2>&1 || fail "Node.js is required."
command -v python3 >/dev/null 2>&1 || fail "Python 3 is required."

[[ -s receivers/platform-tests/platform-app.test.js ]] || \
  fail "Missing receivers/platform-tests/platform-app.test.js"

echo "===== JAVASCRIPT SYNTAX ====="
JS_COUNT=0
while IFS= read -r -d '' file; do
  node --check "$file"
  JS_COUNT=$((JS_COUNT + 1))
done < <(find receivers -type f -name '*.js' -not -path '*/node_modules/*' -print0)

[[ "$JS_COUNT" -gt 0 ]] || fail "No receiver JavaScript files were discovered."
echo "PASS: parsed $JS_COUNT receiver JavaScript files."
echo

echo "===== RECEIVER BEHAVIOR REGRESSION TESTS ====="
node receivers/platform-tests/platform-app.test.js
node receivers/web/tests/protocol.test.js
node receivers/web/tests/receiver.test.js
node receivers/web/tests/hls-integration.test.js
node receivers/tizen/tests/receiver.test.js
node receivers/webos/tests/receiver.test.js
node receivers/xbox-edge/tests/receiver.test.js
node receivers/xbox-edge/tests/lifecycle.test.js
python3 receivers/roku/tests/receiver_contract.test.py
echo

echo "===== PLATFORM JSON AND PACKAGE STRUCTURE ====="
python3 - <<'PY'
from pathlib import Path
import json
import sys

root = Path(".")
files = sorted(
    p for folder in ("platforms", "receivers")
    for p in (root / folder).rglob("*.json")
    if "node_modules" not in p.parts and "build" not in p.parts
)
if not files:
    raise SystemExit("FAIL: no platform/receiver JSON manifests found")

for path in files:
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"FAIL: invalid JSON {path}: {exc}")

targets_path = root / "platforms/targets.json"
if not targets_path.is_file():
    raise SystemExit("FAIL: platforms/targets.json is missing")

targets = json.loads(targets_path.read_text(encoding="utf-8"))
items = targets.get("targets")
if not isinstance(items, list) or not items:
    raise SystemExit("FAIL: platforms/targets.json has no target list")

ids = [item.get("id") for item in items]
if len(ids) != len(set(ids)):
    raise SystemExit("FAIL: duplicate platform target IDs")

required = {"android-mobile", "android-tv", "google-tv", "fire-tv",
            "tizen", "webos", "roku", "web", "xbox-edge", "playstation"}
missing = sorted(required - set(ids))
if missing:
    raise SystemExit("FAIL: target matrix is missing: " + ", ".join(missing))

print(f"PASS: {len(files)} JSON manifests parsed; {len(items)} platform targets found.")
PY
echo

echo "===== SHELL SCRIPT SYNTAX ====="
SH_COUNT=0
while IFS= read -r -d '' file; do
  bash -n "$file"
  SH_COUNT=$((SH_COUNT + 1))
done < <(find scripts -type f -name '*.sh' -print0)
echo "PASS: parsed $SH_COUNT shell scripts."
echo

echo "===== EXISTING SOURCE VALIDATOR ====="
python3 scripts/validate_package.py
echo

echo "===== RESULT ====="
echo "PLATFORM_REGRESSION_GATE=PASS"
echo "NOTE: This validates source, protocol behavior, manifests and script syntax."
echo "It does not certify vendor packaging, physical-device playback, A/V sync or long-session stability."
