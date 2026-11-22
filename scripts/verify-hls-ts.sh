#!/usr/bin/env bash
set -Eeuo pipefail
OUT="mobile/build/hls-integration-test"
PLAYLIST="$OUT/index.m3u8"
command -v ffprobe >/dev/null 2>&1 || { echo "ffprobe is required" >&2; exit 1; }
command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg is required" >&2; exit 1; }
[[ -s "$PLAYLIST" ]] || { echo "HLS integration playlist missing: $PLAYLIST" >&2; exit 1; }
mapfile -t SEGMENTS < <(grep -E '^pslink-segment-[0-9]{6}\.ts$' "$PLAYLIST")
[[ ${#SEGMENTS[@]} -gt 0 ]] || { echo "No MPEG-TS segments referenced by playlist" >&2; exit 1; }
for segment in "${SEGMENTS[@]}"; do
  FILE="$OUT/$segment"
  [[ -s "$FILE" ]] || { echo "Missing HLS segment: $FILE" >&2; exit 1; }
  DETAILS="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height -of csv=p=0 "$FILE")"
  [[ "$DETAILS" == "h264,64,64" ]] || { echo "Unexpected decoded stream metadata in $FILE: $DETAILS" >&2; exit 1; }
done
ffmpeg -hide_banner -loglevel error -i "$PLAYLIST" -frames:v 1 -f null -
echo "HLS MPEG-TS verification passed: ffprobe found H.264 64x64 and ffmpeg decoded a frame."
