#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

EXPECTED_BRANCH="feature/platform-completion-pack"
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: Not a Git repository"
    exit 1
}
cd "$ROOT"

BRANCH="$(git branch --show-current)"
if [[ "$BRANCH" != "$EXPECTED_BRANCH" ]]; then
    echo "ERROR: Wrong branch: $BRANCH"
    exit 1
fi

HEAD_BEFORE="$(git rev-parse HEAD)"
STATUS_BEFORE="$(git status --short)"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="build/shared-media-audit-$STAMP"
mkdir -p "$OUT"

python3 - "$ROOT" "$OUT" "$STAMP" <<'PY'
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
out = Path(sys.argv[2])
stamp = sys.argv[3]

patterns = {
    "audio": re.compile(
        r"audio|AudioTrack|PCM|AAC|Opus|sampleRate|channels|"
        r"AUDIO|audioQueue|AudioBlock", re.I
    ),
    "video": re.compile(
        r"MediaCodec|H264|H\.264|AVC|videoQueue|VideoFrame|"
        r"IDR|keyFrame|VIDEO|SurfaceView", re.I
    ),
    "transport": re.compile(
        r"WebSocket|webSocket|HLS|mpegts|MPEG.?TS|"
        r"stream_url|streamAvailable|publishSubtitle|"
        r"SubtitlePoll|fetch|XMLHttpRequest|"
        r"PMT|PAT|ADTS|PES|segment", re.I
    ),
    "timing": re.compile(
        r"pts|timestamp|nanoTime|monotonic|"
        r"presentationTime|sync|synchron|senderOriginNs|"
        r"localRenderOriginNs", re.I
    ),
    "lifecycle": re.compile(
        r"reconnect|disconnect|resetStream|stop|start|"
        r"timeout|heartbeat|retry|ping|close", re.I
    ),
    "platform_media": re.compile(
        r"AVPlay|HTMLMediaElement|video\.play|"
        r"Video node|roVideoScreen|MediaPlayer|"
        r"WSS|HTTPS|Caddy", re.I
    ),
    "protocol": re.compile(
        r"pairing|auth|protocolVersion|PSLM|"
        r"subtitle|history|cursor|ack|sequence|"
        r"packet|magic|overflow", re.I
    ),
    "known_gaps": re.compile(
        r"TODO|FIXME|unsupported|not implemented|"
        r"pending|video.only|audio.*missing", re.I
    ),
}

roots = [
    "mobile/src/main",
    "tv/app/src/main",
    "core/shared/src/main",
    "receivers",
    "scripts",
]

extensions = {
    ".kt", ".java", ".js", ".brs", ".xml", ".json", ".md"
}

files = []
for prefix in roots:
    base = root / prefix
    if base.exists():
        files.extend(
            p for p in base.rglob("*")
            if p.is_file() and p.suffix.lower() in extensions
            and ".git" not in p.parts
        )

files = sorted(set(files))
results = []
counts = {key: 0 for key in patterns}

for path in files:
    try:
        lines = path.read_text(
            encoding="utf-8", errors="replace"
        ).splitlines()
    except OSError:
        continue

    matches = {}
    for category, pattern in patterns.items():
        found = []
        for number, line in enumerate(lines, 1):
            if pattern.search(line):
                found.append({
                    "line": number,
                    "text": line.strip()[:240]
                })
        if found:
            matches[category] = found
            counts[category] += len(found)

    if matches:
        results.append({
            "file": str(path.relative_to(root)),
            "matches": matches
        })

report = [
    "# PS Link — Shared Media Architecture Audit",
    "",
    f"- Generated UTC: {stamp}",
    f"- Files inspected: {len(files)}",
    "- Method: read-only static source inspection",
    "",
    "## 1. Keyword inventory",
    "",
]

for category, count in counts.items():
    report.append(f"- {category}: {count} matching lines")

report += ["", "## 2. Source findings", ""]

for item in results:
    report.append(f"### `{item['file']}`")
    for category, entries in item["matches"].items():
        report.append(f"#### {category}")
        for entry in entries[:100]:
            text = entry["text"].replace("|", "\\|")
            report.append(f"- Line {entry['line']}: `{text}`")
        if len(entries) > 100:
            report.append(
                f"- Additional matches omitted: {len(entries)-100}"
            )
    report.append("")

report += [
    "## 3. Confirmed by source inspection",
    "",
    "The entries above are source-code matches only. "
    "A match does not prove that a feature works at runtime.",
    "",
    "## 4. Questions requiring code-path verification",
    "",
    "- Is captured audio encoded and forwarded to the TV?",
    "- Does the generated MPEG-TS contain an audio stream?",
    "- Are audio and video timestamps based on one clock?",
    "- Do Tizen, webOS, Roku and Xbox receive audio?",
    "- Are reconnect and stream-reset paths implemented?",
    "- Are codecs supported by each target device?",
    "",
    "## 5. Recommended architecture for evaluation",
    "",
    "- Define one shared transport contract.",
    "- Negotiate receiver capabilities and codecs.",
    "- Standardize timestamps, sequence numbers and stream metadata.",
    "- Implement a platform-specific media adapter per receiver.",
    "- Compare muxed HLS audio/video against separate low-latency "
    "transport before selecting the final design.",
    "",
    "## 6. Limitations",
    "",
    "- No source files were modified.",
    "- No builds or physical-device tests were performed.",
    "- Static keyword matches are not runtime verification.",
    "- No final transport architecture has been selected.",
]

(out / "SHARED-MEDIA-AUDIT.md").write_text(
    "\n".join(report) + "\n", encoding="utf-8"
)

payload = {
    "generated_utc": stamp,
    "files_inspected": len(files),
    "category_match_counts": counts,
    "findings": results,
    "audit_type": "read-only static source inspection",
}

(out / "SHARED-MEDIA-AUDIT.json").write_text(
    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8"
)

print("AUDIT COMPLETE")
print("Files inspected:", len(files))
print("Markdown:", out / "SHARED-MEDIA-AUDIT.md")
print("JSON:", out / "SHARED-MEDIA-AUDIT.json")
print("Category matches:")
for category, count in counts.items():
    print(f"  {category}: {count}")
PY

HEAD_AFTER="$(git rev-parse HEAD)"
STATUS_AFTER="$(git status --short)"

echo
echo "===== SAFETY VERIFICATION ====="
echo "HEAD before: $HEAD_BEFORE"
echo "HEAD after:  $HEAD_AFTER"

if [[ "$HEAD_BEFORE" != "$HEAD_AFTER" ]]; then
    echo "ERROR: Git HEAD changed unexpectedly"
    exit 1
fi

if [[ "$STATUS_BEFORE" != "$STATUS_AFTER" ]]; then
    echo "NOTE: Git status changed. Review the changes below."
    git status --short
fi

echo
echo "SHARED MEDIA AUDIT FINISHED"
echo "Report directory: $OUT"
