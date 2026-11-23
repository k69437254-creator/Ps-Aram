#!/usr/bin/env bash
set -Eeuo pipefail

# PSLINK_HLS_VERIFY_JSON_V2

OUT="mobile/build/hls-integration-test"
PLAYLIST="$OUT/index.m3u8"

command -v ffprobe >/dev/null 2>&1 || {
    echo "ERROR: ffprobe is required" >&2
    exit 1
}

command -v ffmpeg >/dev/null 2>&1 || {
    echo "ERROR: ffmpeg is required" >&2
    exit 1
}

command -v python3 >/dev/null 2>&1 || {
    echo "ERROR: python3 is required" >&2
    exit 1
}

[[ -s "$PLAYLIST" ]] || {
    echo "ERROR: HLS integration playlist missing: $PLAYLIST" >&2
    exit 1
}

mapfile -t SEGMENTS < <(
    grep -E '^pslink-segment-[0-9]{6}\.ts$' "$PLAYLIST"
)

[[ ${#SEGMENTS[@]} -gt 0 ]] || {
    echo "ERROR: No MPEG-TS segments referenced by playlist" >&2
    exit 1
}

for segment in "${SEGMENTS[@]}"; do
    FILE="$OUT/$segment"

    [[ -s "$FILE" ]] || {
        echo "ERROR: Missing HLS segment: $FILE" >&2
        exit 1
    }

    echo "Inspecting: $FILE"

    if ! DETAILS="$(
        ffprobe \
            -v error \
            -select_streams v \
            -show_entries stream=codec_name,width,height \
            -of json \
            "$FILE"
    )"; then
        echo "ERROR: ffprobe could not inspect $FILE" >&2
        ffprobe -hide_banner -show_programs -show_streams "$FILE" || true
        exit 1
    fi

    if ! DETAILS_JSON="$DETAILS" python3 - "$FILE" <<'PY'
import json
import os
import sys

filename = sys.argv[1]

try:
    document = json.loads(os.environ["DETAILS_JSON"])
except Exception as exc:
    print(f"ERROR: Invalid ffprobe JSON for {filename}: {exc}", file=sys.stderr)
    sys.exit(1)

streams = document.get("streams", [])

if not streams:
    print(f"ERROR: No video streams found in {filename}", file=sys.stderr)
    sys.exit(1)

for index, stream in enumerate(streams):
    codec = stream.get("codec_name")
    width = stream.get("width")
    height = stream.get("height")

    if codec != "h264" or width != 64 or height != 64:
        print(
            f"ERROR: Unexpected video stream {index}: "
            f"codec={codec}, width={width}, height={height}",
            file=sys.stderr
        )
        sys.exit(1)

print(f"PASS: {len(streams)} H.264 video stream record(s), 64x64.")
PY
    then
        echo "Full ffprobe diagnostics for $FILE:"
        ffprobe -hide_banner -show_programs -show_streams "$FILE" || true
        exit 1
    fi
done

echo
echo "===== FFMPEG DECODE TEST ====="

ffmpeg \
    -hide_banner \
    -loglevel error \
    -xerror \
    -i "$PLAYLIST" \
    -map 0:v:0 \
    -frames:v 1 \
    -f null -

echo
echo "PASS: FFmpeg decoded an H.264 video frame."
echo "PS LINK HLS MPEG-TS VERIFICATION PASSED."
