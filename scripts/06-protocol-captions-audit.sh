#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; OUT="build/audits/06-protocol-captions-$STAMP.md"
mkdir -p "$(dirname "$OUT")"; PASS=0; WARN=0; BLOCK=0
{ echo '# PS Link — Protocol and Spatial Captions Audit'; echo; echo "- UTC: $STAMP"; echo '> Static keyword evidence only; it does not prove message delivery, decoding, acknowledgement, or rendering.'; echo; } > "$OUT"
record(){ local s="$1" n="$2" d="$3"; printf -- '- **%s** — %s: %s\n' "$s" "$n" "$d" >> "$OUT"; case "$s" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; BLOCKER) BLOCK=$((BLOCK+1));; esac; }
for term in 'protocolVersion|protocol_version' 'capabilit' 'pair|auth|token' 'session' 'ack|accepted|rendered|decoded' 'reconnect|retry|heartbeat' 'mediaConfig|media_config|codec' 'subtitle|caption'; do
 label="${term%%|*}"; if rg -n -i "$term" receivers core mobile tv --glob '!**/build/**' >> "$OUT" 2>/dev/null; then record PASS "source:$label" 'matching source evidence found'; else record WARN "source:$label" 'no match found in searched paths'; fi
done
{
 echo; echo '## Spatial-caption files and integration'
} >> "$OUT"
[[ -f receivers/shared/pslink-spatial-captions.js ]] && record PASS shared-renderer 'found' || record BLOCKER shared-renderer 'missing'
for p in tizen webos; do
 for f in "receivers/$p/app.js" "receivers/$p/index.html" "receivers/$p/app.css"; do
  if [[ -f "$f" ]] && grep -Eqi 'spatial-captions|PSLINK_SPATIAL_CAPTIONS|video-stage|game-video' "$f"; then record PASS "$p-integration" "$f has an expected marker"; else record WARN "$p-integration" "$f missing or marker not found"; fi
 done
done
if [[ -f receivers/shared/tests/spatial-captions.test.js ]]; then
 if command -v node >/dev/null 2>&1; then node receivers/shared/tests/spatial-captions.test.js >> "$OUT" 2>&1 && record PASS spatial-caption-tests 'test command passed' || record BLOCKER spatial-caption-tests 'test command failed; see report'; else record WARN spatial-caption-tests 'Node.js unavailable'; fi
else record WARN spatial-caption-tests 'test file missing'; fi
if [[ -f receivers/tizen/tests/receiver.test.js && -f receivers/webos/tests/receiver.test.js ]] && cmp -s receivers/tizen/tests/receiver.test.js receivers/webos/tests/receiver.test.js; then record WARN independent-platform-tests 'Tizen and webOS receiver tests are identical; copied tests are not independent validation'; fi
{ echo; echo '## Summary'; echo "- PASS: $PASS"; echo "- WARN: $WARN"; echo "- BLOCKER: $BLOCK"; } >> "$OUT"
echo "REPORT=$OUT"; echo "PASS=$PASS WARN=$WARN BLOCKER=$BLOCK"
