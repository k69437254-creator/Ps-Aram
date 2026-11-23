#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail


ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: Not inside a Git repository"
  exit 1
}

cd "$ROOT"

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: Python 3 is required"
  exit 1
}

BRANCH="$(git branch --show-current)"
HEAD="$(git rev-parse HEAD)"


STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="build/audio-pipeline-audit-$STAMP"

mkdir -p "$OUT"

export AUDIT_ROOT="$ROOT"
export AUDIT_OUT="$OUT"
export AUDIT_BRANCH="$BRANCH"
export AUDIT_HEAD="$HEAD"

echo "========================================"
echo " PS LINK — AUDIO PIPELINE AUDIT 03"
echo "========================================"
echo "Branch: $BRANCH"
echo "HEAD:   $HEAD"
echo "Output: $OUT"
echo

python3 <<'PY'
import os
import re
import json
import subprocess
from pathlib import Path
from datetime import datetime, timezone

root = Path(os.environ["AUDIT_ROOT"])
out = Path(os.environ["AUDIT_OUT"])
branch = os.environ["AUDIT_BRANCH"]
head = os.environ["AUDIT_HEAD"]

patterns = {
    "audio_capture": [
        r"AudioRecord", r"MediaRecorder", r"AudioPlaybackCapture",
        r"VOICE_COMMUNICATION", r"MIC", r"RECORD_AUDIO",
        r"captureAudio", r"audioCapture", r"AudioSource"
    ],
    "audio_encoding": [
        r"MediaCodec", r"AAC", r"aac", r"ADTS", r"Opus",
        r"PCM_16BIT", r"encode", r"encoder", r"sampleRate",
        r"channelCount", r"audio/.*"
    ],
    "audio_transport": [
        r"audio", r"AUDIO", r"audioTrack", r"AudioBlock",
        r"onMediaPacket", r"sendBinary", r"send\(.*data",
        r"WebSocket", r"Frame.Binary", r"ByteBuffer",
        r"PSLM", r"media_config"
    ],
    "mpeg_ts": [
        r"MPEG.?TS", r"TransportStream", r"PMT", r"PAT",
        r"PID", r"PES", r"ADTS", r"index\.m3u8",
        r"\.ts\b", r"HLS", r"segment"
    ],
    "audio_playback": [
        r"AudioTrack", r"HTMLMediaElement", r"<audio",
        r"audioElement", r"audio\.play", r"AVPlay",
        r"setStreamingProperty", r"Roku.*Video",
        r"videoNode", r"MediaSource"
    ],
    "timing": [
        r"timestampNs", r"ptsNs", r"presentationTimeUs",
        r"System\.nanoTime", r"senderOriginNs",
        r"localRenderOriginNs", r"timestamp", r"PTS"
    ],
    "audio_gaps": [
        r"TODO", r"FIXME", r"unsupported", r"not supported",
        r"video-only", r"audio.*missing", r"no audio"
    ]
}

targets = [
    "mobile/src/main/java/com/pslink/mobile/capture",
    "mobile/src/main/java/com/pslink/mobile",
    "tv/app/src/main/java/com/pslink/tv",
    "core/shared/src/main",
    "receivers/tizen",
    "receivers/webos",
    "receivers/roku",
    "receivers/xbox-edge",
    "receivers/platform-tests",
    "scripts",
    ".github/workflows"
]

extensions = {
    ".kt", ".java", ".js", ".ts", ".brs", ".xml",
    ".py", ".md", ".gradle", ".kts", ".json", ".yml", ".yaml"
}

files = set()

for item in targets:
    base = root / item
    if base.is_file():
        files.add(base)
    elif base.is_dir():
        for p in base.rglob("*"):
            if p.is_file() and p.suffix.lower() in extensions:
                if ".git" not in p.parts and "build" not in p.parts:
                    files.add(p)

results = []
counts = {key: 0 for key in patterns}
evidence = {key: [] for key in patterns}
source_lines = []

for path in sorted(files):
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception as exc:
        source_lines.append(f"READ ERROR {path.relative_to(root)}: {exc}")
        continue

    relative = str(path.relative_to(root))
    matches_for_file = []

    for category, expressions in patterns.items():
        found = []
        for number, line in enumerate(text.splitlines(), 1):
            for expression in expressions:
                if re.search(expression, line, re.IGNORECASE):
                    found.append({
                        "line": number,
                        "pattern": expression,
                        "text": line.strip()[:350]
                    })
                    break

        if found:
            counts[category] += len(found)
            evidence[category].append({
                "file": relative,
                "matches": found[:100]
            })
            matches_for_file.extend(
                f"{category} L{m['line']}: {m['text']}"
                for m in found
            )

    if matches_for_file:
        source_lines.append(f"\n===== {relative} =====")
        source_lines.extend(matches_for_file)

git_status_before = subprocess.run(
    ["git", "status", "--short"],
    cwd=root, capture_output=True, text=True
).stdout.strip()

tracked = subprocess.run(
    ["git", "ls-files"],
    cwd=root, capture_output=True, text=True
).stdout.splitlines()

source = {
    "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    "branch": branch,
    "head": head,
    "tracked_file_count": len(tracked),
    "scanned_file_count": len(files),
    "category_match_counts": counts,
    "evidence": evidence,
    "working_tree_before": git_status_before.splitlines()
}

(out / "AUDIO-SOURCE-SCAN.txt").write_text(
    "\n".join(source_lines) + "\n",
    encoding="utf-8"
)

(out / "AUDIO-PIPELINE-AUDIT.json").write_text(
    json.dumps(source, ensure_ascii=False, indent=2),
    encoding="utf-8"
)

report = [
    "# PS Link — Audio Pipeline Audit",
    "",
    f"- Branch: `{branch}`",
    f"- HEAD: `{head}`",
    f"- Tracked files: {len(tracked)}",
    f"- Scanned files: {len(files)}",
    "",
    "## 1. Search results",
    "",
    "| Category | Source matches |",
    "|---|---:|"
]

for key, count in counts.items():
    report.append(f"| {key} | {count} |")

report += [
    "",
    "## 2. Important interpretation",
    "",
    "These are static source-code matches. They do not prove that audio",
    "is captured, encoded, transmitted, decoded, or played successfully.",
    "",
    "## 3. Required end-to-end verification",
    "",
    "- Identify the actual phone audio capture entry point.",
    "- Follow captured samples into the encoder.",
    "- Verify encoded audio packets leave the phone.",
    "- Inspect MPEG-TS PMT/PES construction for an audio elementary stream.",
    "- Verify the Android TV packet format and audio decoder assumptions.",
    "- Verify audio playback implementations on Tizen, webOS, Roku and Xbox.",
    "- Compare audio and video timestamps and clock domains.",
    "",
    "## 4. Confirmed versus unknown",
    "",
    "This report records source matches only. A feature is not classified",
    "as operational unless its complete call path and runtime tests prove it.",
    "Missing matches are not proof that a feature does not exist.",
    "",
    "## 5. Recommended next step",
    "",
    "Inspect the exact call paths identified in AUDIO-SOURCE-SCAN.txt,",
    "then design a shared media contract with platform-specific adapters.",
    "Do not implement a transport change from keyword matches alone.",
    "",
    "## 6. Safety",
    "",
    "- No tracked source files were modified.",
    "- No files were staged or committed.",
    "- No network access was used."
]

(out / "AUDIO-PIPELINE-AUDIT.md").write_text(
    "\n".join(report) + "\n",
    encoding="utf-8"
)

git_status_after = subprocess.run(
    ["git", "status", "--short"],
    cwd=root, capture_output=True, text=True
).stdout.strip()

if git_status_before != git_status_after:
    print("WARNING: Git status changed during audit.")
else:
    print("Git status unchanged.")

print("Scanned files:", len(files))
print("Tracked files:", len(tracked))
print("")

for key, count in counts.items():
    print(f"{key}: {count} matches")

print("")
print("Reports created:")
for name in [
    "AUDIO-PIPELINE-AUDIT.md",
    "AUDIO-PIPELINE-AUDIT.json",
    "AUDIO-SOURCE-SCAN.txt"
]:
    print(out / name)

PY

echo
echo "========================================"
echo " AUDIT 03 COMPLETED"
echo "========================================"
echo "Read-only source inspection finished."
echo "No commit or push was performed."
