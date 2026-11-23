#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; OUT="build/audits/09-github-workflows-$STAMP.md"
mkdir -p "$(dirname "$OUT")"; PASS=0; WARN=0; BLOCK=0
{ echo '# PS Link — GitHub Actions and Artifact Audit'; echo; echo "- UTC: $STAMP"; echo '> Static inspection only; it does not prove an Actions run succeeds or its artifacts are installable.'; echo; } > "$OUT"
record(){ local s="$1" n="$2" d="$3"; printf -- '- **%s** — %s: %s\n' "$s" "$n" "$d" >> "$OUT"; case "$s" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; BLOCKER) BLOCK=$((BLOCK+1));; esac; }
WF=.github/workflows
if [[ ! -d "$WF" ]]; then record BLOCKER workflow-directory "$WF missing"; else
 mapfile -t LIST < <(find "$WF" -maxdepth 1 -type f \( -name '*.yml' -o -name '*.yaml' \) -print | sort)
 [[ ${#LIST[@]} -gt 0 ]] || record BLOCKER workflow-files 'no YAML workflow files found'
 for f in "${LIST[@]}"; do
  record PASS workflow-file "$f"; echo "## $f" >> "$OUT"
  grep -n -E '^(name:|on:|jobs:)|actions/checkout|setup-java|gradle|upload-artifact|download-artifact|\.apk|\.aab|\.wgt|\.ipk|roku|artifact' "$f" >> "$OUT" || true
  grep -q 'actions/upload-artifact' "$f" && record PASS artifact-upload "$f uses upload-artifact" || record WARN artifact-upload "$f has no visible upload-artifact step"
  grep -q 'workflow_dispatch' "$f" && record PASS manual-trigger "$f exposes workflow_dispatch" || record WARN manual-trigger "$f has no visible workflow_dispatch"
 done
fi
for f in android.yml tv-native-web-receivers.yml tv-web-receiver.yml; do [[ -f "$WF/$f" ]] && record PASS "expected:$f" 'found' || record WARN "expected:$f" 'not found'; done
if rg -n 'ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY' "$WF" >/dev/null 2>&1; then record BLOCKER obvious-secret-pattern 'possible token/private key pattern found; inspect immediately'; else record PASS obvious-secret-pattern 'no obvious token/private-key pattern found; not a full secret scan'; fi
{ echo; echo '## Reminder'; echo '- Verify actual workflow run status, uploaded APK/package files, retention and downloadable artifact links after push.'; echo '- Do not expose tokens in workflow logs or committed files.'; echo; echo '## Summary'; echo "- PASS: $PASS"; echo "- WARN: $WARN"; echo "- BLOCKER: $BLOCK"; } >> "$OUT"
echo "REPORT=$OUT"; echo "PASS=$PASS WARN=$WARN BLOCKER=$BLOCK"
[[ "$BLOCK" -eq 0 ]] || exit 2
