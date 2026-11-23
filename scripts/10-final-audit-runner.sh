#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="build/audits/final-$STAMP"
mkdir -p "$DIR"
REPORT="$DIR/FINAL-REPORT.md"
FAILED=0; RAN=0; SKIPPED=0; MISSING=0
TOTAL_PASS=0; TOTAL_WARN=0; TOTAL_BLOCKER=0
OPTIONAL=(scripts/01-master-audit.sh scripts/02-shared-media-audit.sh scripts/03-audio-pipeline-audit.sh scripts-04-media-callpath-audit.sh)
REQUIRED=(scripts/05-platform-readiness-audit.sh scripts/06-protocol-captions-audit.sh scripts/07-roku-package-audit.sh scripts/08-hls-audio-pipeline-audit.sh scripts/09-github-workflow-audit.sh)
{
  echo '# PS Link — Final Consolidated Audit'
  echo
  echo "- UTC: $STAMP"
  echo "- Branch: $(git branch --show-current 2>/dev/null || echo unknown)"
  echo
  echo '> A script execution is not a pass: summaries and BLOCKER counts are aggregated below.'
  echo '> Device validation and real media playback remain separate evidence.'
  echo
  echo '## Results'
} > "$REPORT"

for s in "${OPTIONAL[@]}" "${REQUIRED[@]}"; do
  is_required=0
  for required_script in "${REQUIRED[@]}"; do
    [[ "$s" == "$required_script" ]] && is_required=1
  done
  if [[ ! -f "$s" ]]; then
    if [[ $is_required -eq 1 ]]; then
      printf -- '- **MISSING REQUIRED** `%s`\n' "$s" | tee -a "$REPORT"
      MISSING=$((MISSING + 1)); FAILED=$((FAILED + 1))
    else
      printf -- '- **SKIPPED OPTIONAL** `%s` — not present in this checkout/branch\n' "$s" | tee -a "$REPORT"
      SKIPPED=$((SKIPPED + 1))
    fi
    continue
  fi

  LOG="$DIR/$(basename "$s").log"
  echo "RUN: $s"
  set +e
  bash "$s" > "$LOG" 2>&1
  RC=$?
  set -e
  RAN=$((RAN + 1))

  SUMMARY="$(grep -Eo 'PASS=[0-9]+ WARN=[0-9]+ BLOCKER=[0-9]+' "$LOG" | tail -n 1 || true)"
  SCRIPT_BLOCKERS=0
  if [[ -n "$SUMMARY" ]]; then
    read -r P W B < <(sed -E 's/PASS=([0-9]+) WARN=([0-9]+) BLOCKER=([0-9]+)/\1 \2 \3/' <<< "$SUMMARY")
    TOTAL_PASS=$((TOTAL_PASS + P))
    TOTAL_WARN=$((TOTAL_WARN + W))
    TOTAL_BLOCKER=$((TOTAL_BLOCKER + B))
    SCRIPT_BLOCKERS=$B
  elif [[ $is_required -eq 1 ]]; then
    echo "NO STANDARD SUMMARY: $s" >> "$LOG"
    SCRIPT_BLOCKERS=1
  fi

  if [[ $RC -ne 0 || $SCRIPT_BLOCKERS -gt 0 ]]; then
    printf -- '- **FAILED** `%s` — exit=%s, BLOCKER=%s; log: `%s`\n' "$s" "$RC" "$SCRIPT_BLOCKERS" "$LOG" | tee -a "$REPORT"
    FAILED=$((FAILED + 1))
  else
    printf -- '- **EXECUTED** `%s` — PASS/WARN/BLOCKER summary: `%s`; log: `%s`\n' "$s" "${SUMMARY:-not reported by optional audit}" "$LOG" | tee -a "$REPORT"
  fi
done

{
  echo
  echo '## Aggregated totals'
  echo "- Audit scripts executed: $RAN"
  echo "- Optional scripts skipped: $SKIPPED"
  echo "- Required scripts missing: $MISSING"
  echo "- PASS checks: $TOTAL_PASS"
  echo "- WARN checks: $TOTAL_WARN"
  echo "- BLOCKER checks: $TOTAL_BLOCKER"
  echo "- Failed scripts: $FAILED"
  echo
  echo '## Working tree'
  echo '```text'
  git status --short 2>&1 || true
  echo '```'
  echo
  echo 'A zero-failure audit does not prove hardware compatibility, signed platform packages, AAC HLS, or physical-device operation.'
} >> "$REPORT"

echo "FINAL_REPORT=$REPORT"
echo "EXECUTED=$RAN SKIPPED_OPTIONAL=$SKIPPED MISSING_REQUIRED=$MISSING PASS=$TOTAL_PASS WARN=$TOTAL_WARN BLOCKER=$TOTAL_BLOCKER FAILED=$FAILED"
[[ $FAILED -eq 0 && $MISSING -eq 0 && $TOTAL_BLOCKER -eq 0 ]]
