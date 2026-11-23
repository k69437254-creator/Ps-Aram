#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; DIR="build/audits/final-$STAMP"; mkdir -p "$DIR"; REPORT="$DIR/FINAL-REPORT.md"
FAILED=0; RAN=0; MISSING=0
SCRIPTS=(scripts/01-master-audit.sh scripts/02-shared-media-audit.sh scripts/03-audio-pipeline-audit.sh scripts-04-media-callpath-audit.sh scripts/05-platform-readiness-audit.sh scripts/06-protocol-captions-audit.sh scripts/07-roku-package-audit.sh scripts/08-hls-audio-pipeline-audit.sh scripts/09-github-workflow-audit.sh)
{ echo '# PS Link — Final Consolidated Audit'; echo; echo "- UTC: $STAMP"; echo "- Branch: $(git branch --show-current 2>/dev/null || echo unknown)"; echo; echo '> EXECUTED means script exit 0 only; it does not imply all checks passed or a platform is complete.'; echo; echo '## Results'; } > "$REPORT"
for s in "${SCRIPTS[@]}"; do
 if [[ ! -f "$s" ]]; then printf -- '- **MISSING** `%s`\n' "$s" | tee -a "$REPORT"; MISSING=$((MISSING+1)); FAILED=$((FAILED+1)); continue; fi
 LOG="$DIR/$(basename "$s").log"; echo "RUN: $s"; set +e; bash "$s" > "$LOG" 2>&1; RC=$?; set -e; RAN=$((RAN+1))
 if [[ $RC -eq 0 ]]; then printf -- '- **EXECUTED** `%s` — exit 0; log: `%s`\n' "$s" "$LOG" | tee -a "$REPORT"; else printf -- '- **FAILED** `%s` — exit %s; log: `%s`\n' "$s" "$RC" "$LOG" | tee -a "$REPORT"; FAILED=$((FAILED+1)); fi
done
{ echo; echo '## Totals'; echo "- Executed: $RAN"; echo "- Missing: $MISSING"; echo "- Failed executions: $FAILED"; echo; echo '## Working tree'; echo '```text'; git status --short 2>&1 || true; echo '```'; echo; echo 'Check individual reports for WARN/BLOCKER results. Device validation and real A/V tests remain separate evidence.'; } >> "$REPORT"
echo "FINAL_REPORT=$REPORT"; echo "EXECUTED=$RAN MISSING=$MISSING FAILED=$FAILED"; [[ $FAILED -eq 0 ]]
