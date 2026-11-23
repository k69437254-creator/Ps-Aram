#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; OUT="build/audits/05-platform-readiness-$STAMP.md"
mkdir -p "$(dirname "$OUT")"; PASS=0; WARN=0; BLOCK=0
{
 echo '# PS Link — Platform Readiness Audit'; echo; echo "- UTC: $STAMP"; echo "- Branch: $(git branch --show-current 2>/dev/null || echo unknown)"; echo
 echo '> Static inventory only. File presence and syntax do not prove runtime or device compatibility.'; echo; echo '## Required paths'
} > "$OUT"
record(){ local s="$1" n="$2" d="$3"; printf -- '- **%s** — `%s`: %s\n' "$s" "$n" "$d" >> "$OUT"; case "$s" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; BLOCKER) BLOCK=$((BLOCK+1));; esac; }
check(){ [[ -e "$2" ]] && record PASS "$1" "found: $2" || record BLOCKER "$1" "missing: $2"; }
check Android-mobile mobile/build.gradle.kts
check Android-TV tv/app/build.gradle.kts
check Shared-core core/shared/build.gradle.kts
check Gradle-settings settings.gradle.kts
check Tizen-app receivers/tizen/app.js
check Tizen-HTML receivers/tizen/index.html
check Tizen-client receivers/tizen/pslink-client.js
check webOS-app receivers/webos/app.js
check webOS-HTML receivers/webos/index.html
check webOS-client receivers/webos/pslink-client.js
check Roku-source receivers/roku/source
check Xbox-Edge-client receivers/xbox-edge/pslink-client.js
check Web-receiver receivers/web
check Platform-targets platforms/targets.json
{
 echo; echo '## Target declarations'
} >> "$OUT"
if [[ -f platforms/targets.json ]] && command -v python3 >/dev/null 2>&1; then
 python3 - "$OUT" <<'PY'
import json,sys
from pathlib import Path
p=Path('platforms/targets.json'); out=Path(sys.argv[1])
try:
 d=json.loads(p.read_text()); t=d.get('targets',d) if isinstance(d,dict) else d
 rows=list(t.items()) if isinstance(t,dict) else [(str(x.get('id',x.get('name',i))),x) for i,x in enumerate(t) if isinstance(x,dict)] if isinstance(t,list) else []
 with out.open('a') as f:
  for n,x in rows:
   s=x.get('status',x.get('state','unspecified')) if isinstance(x,dict) else x
   f.write(f'- `{n}`: `{s}`\n')
  if not rows: f.write('- WARN: no recognized target entries\n')
except Exception as e:
 with out.open('a') as f: f.write(f'- BLOCKER: invalid targets.json: {e}\n')
PY
else record WARN targets-json-parse 'python3 or targets.json unavailable'; fi
{
 echo; echo '## JavaScript syntax'
} >> "$OUT"
if command -v node >/dev/null 2>&1; then
 while IFS= read -r -d '' f; do node --check "$f" >/dev/null 2>&1 && record PASS node-check "$f" || record BLOCKER node-check "syntax error: $f"; done < <(find receivers/tizen receivers/webos receivers/xbox-edge receivers/web -type f -name '*.js' -print0 2>/dev/null || true)
else record WARN node-check 'Node.js unavailable'; fi
{
 echo; echo '## Tool availability'
} >> "$OUT"
for t in java gradle ffmpeg ffprobe node python3; do command -v "$t" >/dev/null 2>&1 && record PASS "tool:$t" "$(command -v "$t")" || record WARN "tool:$t" 'not installed'; done
[[ -x ./gradlew ]] && record PASS gradle-wrapper executable || { [[ -f ./gradlew ]] && record WARN gradle-wrapper 'exists but is not executable' || record WARN gradle-wrapper 'not found'; }
{ echo; echo '## Summary'; echo "- PASS: $PASS"; echo "- WARN: $WARN"; echo "- BLOCKER: $BLOCK"; } >> "$OUT"
echo "REPORT=$OUT"; echo "PASS=$PASS WARN=$WARN BLOCKER=$BLOCK"
