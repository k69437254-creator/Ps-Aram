#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; OUT="build/audits/07-roku-package-$STAMP.md"
mkdir -p "$(dirname "$OUT")"; PASS=0; WARN=0; BLOCK=0
{ echo '# PS Link — Roku Package Readiness Audit'; echo; echo "- UTC: $STAMP"; echo '> Does not create/sign a production package or validate installation on Roku hardware.'; echo; } > "$OUT"
record(){ local s="$1" n="$2" d="$3"; printf -- '- **%s** — %s: %s\n' "$s" "$n" "$d" >> "$OUT"; case "$s" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; BLOCKER) BLOCK=$((BLOCK+1));; esac; }
R=receivers/roku
[[ -d "$R/source" ]] && record PASS roku-source "$R/source exists" || record BLOCKER roku-source "$R/source missing"
[[ -d "$R/tests" ]] && record PASS roku-tests "$R/tests exists" || record WARN roku-tests "$R/tests missing"
M="$(find "$R" -type f -iname manifest -print -quit 2>/dev/null || true)"
[[ -n "$M" ]] && record PASS roku-manifest "$M" || record BLOCKER roku-manifest 'no manifest found under receivers/roku'
if find "$R" -type f \( -name '*.brs' -o -name '*.bs' \) -print -quit 2>/dev/null | grep -q .; then
 command -v bsc >/dev/null 2>&1 && record PASS brightscript-toolchain 'bsc found' || { command -v brs >/dev/null 2>&1 && record PASS brightscript-toolchain 'brs found' || record WARN brightscript-toolchain 'BrightScript source exists but bsc/brs is unavailable'; }
else record WARN brightscript-source 'no .brs or .bs files found; inspect Roku source layout'; fi
if command -v node >/dev/null 2>&1; then while IFS= read -r -d '' f; do node --check "$f" >/dev/null 2>&1 && record PASS roku-js-syntax "$f" || record BLOCKER roku-js-syntax "syntax error: $f"; done < <(find "$R" -type f -name '*.js' -print0 2>/dev/null || true); else record WARN roku-js-syntax 'Node.js unavailable'; fi
{
 echo; echo '## Not verified'; echo '- Channel packaging/signing: NOT VERIFIED'; echo '- Developer-mode install and physical playback: NOT VERIFIED'; echo '- H.264 + AAC HLS playback: see script 08'; echo '- Remote control, reconnect and subtitle rendering on Roku: NOT VERIFIED'; echo; echo '## File inventory'; find "$R" -maxdepth 4 -type f -print 2>/dev/null | sort | sed 's#^#- `#;s#$#`#' || true; echo; echo '## Summary'; echo "- PASS: $PASS"; echo "- WARN: $WARN"; echo "- BLOCKER: $BLOCK";
} >> "$OUT"
echo "REPORT=$OUT"; echo "PASS=$PASS WARN=$WARN BLOCKER=$BLOCK"
[[ "$BLOCK" -eq 0 ]] || exit 2
