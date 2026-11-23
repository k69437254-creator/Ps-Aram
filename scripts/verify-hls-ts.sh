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
echo "NOTE: AAC transport and decoding are checked by the separate real-AAC integration fixture; device A/V sync remains unverified."

echo
echo "===== REAL AAC + H.264 MPEG-TS VERIFICATION ====="

AAC_OUT="mobile/build/hls-aac-integration-test"
AAC_PLAYLIST="$AAC_OUT/index.m3u8"

[[ -s "$AAC_PLAYLIST" ]] || {
    echo "ERROR: Real AAC HLS playlist missing: $AAC_PLAYLIST" >&2
    exit 1
}

mapfile -t AAC_SEGMENTS < <(
    grep -E '^pslink-segment-[0-9]{6}\.ts$' "$AAC_PLAYLIST"
)

[[ ${#AAC_SEGMENTS[@]} -gt 0 ]] || {
    echo "ERROR: AAC playlist references no TS segments" >&2
    exit 1
}

for segment in "${AAC_SEGMENTS[@]}"; do
    FILE="$AAC_OUT/$segment"

    [[ -s "$FILE" ]] || {
        echo "ERROR: Missing AAC integration segment: $FILE" >&2
        exit 1
    }

    CODECS="$(ffprobe -v error \
        -show_entries stream=codec_name \
        -of csv=p=0 "$FILE" | sort -u)"

    echo "Detected codecs in $FILE:"
    printf '%s\n' "$CODECS"

    grep -qx "h264" <<< "$CODECS" || {
        echo "ERROR: H.264 stream missing from $FILE" >&2
        exit 1
    }

    grep -qx "aac" <<< "$CODECS" || {
        echo "ERROR: AAC stream missing from $FILE" >&2
        exit 1
    }

    echo "PASS: H.264 and AAC streams detected."
done

echo
echo "===== AAC DECODE TEST ====="

ffmpeg \
    -hide_banner \
    -loglevel error \
    -xerror \
    -i "$AAC_PLAYLIST" \
    -map 0:a:0 \
    -frames:a 1 \
    -f null -

echo "PASS: FFmpeg decoded an AAC frame from HLS."
echo "PS LINK HLS MPEG-TS VIDEO + AAC VERIFICATION PASSED."
