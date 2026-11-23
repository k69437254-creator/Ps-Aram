#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"; cd "$ROOT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; OUT="build/audits/08-hls-audio-$STAMP.md"
mkdir -p "$(dirname "$OUT")"; PASS=0; WARN=0; BLOCK=0
{ echo '# PS Link — HLS Video/Audio Pipeline Audit'; echo; echo "- UTC: $STAMP"; echo '> Static matches are not proof of A/V sync, H.264/AAC delivery, PCR/PTS correctness, or playback.'; echo; } > "$OUT"
record(){ local s="$1" n="$2" d="$3"; printf -- '- **%s** — %s: %s\n' "$s" "$n" "$d" >> "$OUT"; case "$s" in PASS) PASS=$((PASS+1));; WARN) WARN=$((WARN+1));; BLOCKER) BLOCK=$((BLOCK+1));; esac; }
if rg -n -i 'mpeg.?ts|#EXTM3U|application/vnd\.apple\.mpegurl|video/MP2T|MediaCodec|AAC|audio/aac|audio.*encode|audio.*capture' mobile tv core --glob '!**/build/**' >> "$OUT" 2>/dev/null; then record PASS media-source-evidence 'potential HLS/audio source matches found; see excerpts'; else record WARN media-source-evidence 'no expected HLS/audio keywords found'; fi
INPUT="${1:-${HLS_INPUT:-}}"
if [[ -z "$INPUT" ]]; then INPUT="$(find build -type f -name '*.m3u8' -print -quit 2>/dev/null || true)"; [[ -n "$INPUT" ]] || INPUT="$(find . -path './.git' -prune -o -type f -name '*.m3u8' -print -quit 2>/dev/null || true)"; fi
if [[ -z "$INPUT" ]]; then record WARN hls-media-validation 'no playlist supplied/found; runtime media validation skipped'
elif ! command -v ffprobe >/dev/null 2>&1; then record WARN hls-media-validation "playlist exists ($INPUT), but ffprobe is unavailable"
else
 if [[ -d "$INPUT" ]]; then mapfile -t PLAYLISTS < <(find "$INPUT" -type f -name '*.m3u8' -print | sort); else PLAYLISTS=("$INPUT"); fi
 if [[ ${#PLAYLISTS[@]} -eq 0 ]]; then record WARN hls-media-validation "no playlists in $INPUT"; fi
 for p in "${PLAYLISTS[@]}"; do
  [[ -f "$p" ]] || { record BLOCKER hls-playlist "missing: $p"; continue; }
  META="$(ffprobe -v error -show_entries stream=codec_type,codec_name -of csv=p=0 "$p" 2>&1 || true)"
  printf '\n### %s\n```text\n%s\n```\n' "$p" "$META" >> "$OUT"
  grep -Eqi 'video,h264|h264,video' <<< "$META" && record PASS hls-h264 "$p reports H.264 video" || record BLOCKER hls-h264 "$p has no detected H.264 video stream"
  grep -Eqi 'audio,aac|aac,audio' <<< "$META" && record PASS hls-aac "$p reports AAC audio" || record BLOCKER hls-aac "$p has no detected AAC audio stream"
  grep -q '^#EXTINF:' "$p" && grep -q '^#EXT-X-TARGETDURATION:' "$p" && record PASS playlist-tags "$p has EXTINF and TARGETDURATION" || record WARN playlist-tags "$p missing expected tags"
 done
fi
[[ -f scripts/verify-hls-ts.sh ]] && record PASS existing-verifier 'scripts/verify-hls-ts.sh exists; not invoked because its CLI contract has not been inspected' || record WARN existing-verifier 'scripts/verify-hls-ts.sh not found'
{ echo; echo '## Not established'; echo '- Continuous A/V sync under load'; echo '- PTS monotonicity and PCR accuracy across segment boundaries'; echo '- Backpressure and long-duration playback'; echo '- Audio output on physical receiver devices'; echo; echo '## Summary'; echo "- PASS: $PASS"; echo "- WARN: $WARN"; echo "- BLOCKER: $BLOCK"; } >> "$OUT"
echo "REPORT=$OUT"; echo "PASS=$PASS WARN=$WARN BLOCKER=$BLOCK"
