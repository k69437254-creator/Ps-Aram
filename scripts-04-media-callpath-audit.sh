#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

EXPECTED_BRANCH="feature/platform-completion-pack"

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: هذا المسار ليس داخل Git repository."
  exit 1
}

cd "$ROOT"

CURRENT_BRANCH="$(git branch --show-current)"
if [ "$CURRENT_BRANCH" != "$EXPECTED_BRANCH" ]; then
  echo "ERROR: الفرع الحالي غير المتوقع."
  echo "Expected: $EXPECTED_BRANCH"
  echo "Current:  $CURRENT_BRANCH"
  exit 1
fi

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 غير متوفر."
  exit 1
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="build/media-callpath-audit-$STAMP"
mkdir -p "$OUT"

echo "=============================================="
echo " PS LINK — MEDIA CALL-PATH AUDIT 04"
echo "=============================================="
echo "Branch: $CURRENT_BRANCH"
echo "Output: $OUT"
echo
echo "Read-only audit. No source files will be changed."
echo

python3 - "$ROOT" "$OUT" "$CURRENT_BRANCH" <<'PY'
import sys
import re
import json
import subprocess
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(sys.argv[1]).resolve()
OUT = (ROOT / sys.argv[2]).resolve()
BRANCH = sys.argv[3]

def git(*args):
    p = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE
    )
    return p.returncode, p.stdout, p.stderr

def status_snapshot():
    _, out, _ = git("status", "--porcelain", "--untracked-files=all")
    return out

def source_diff_clean():
    a = subprocess.run(
        ["git", "diff", "--quiet", "HEAD", "--"],
        cwd=ROOT
    ).returncode == 0
    b = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=ROOT
    ).returncode == 0
    return a and b

status_before = status_snapshot()
diff_before = source_diff_clean()

_, head, _ = git("rev-parse", "HEAD")
head = head.strip()

_, tracked, _ = git("ls-files")
tracked_files = [x for x in tracked.splitlines() if x.strip()]

exact_paths = [
    "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt",
    "mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt",
    "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt",
    "receivers/tizen/app.js",
    "receivers/tizen/pslink-client.js",
    "receivers/tizen/media-player.js",
    "receivers/webos/app.js",
    "receivers/webos/pslink-client.js",
    "receivers/webos/media-player.js",
]

# Add all tracked Roku and Xbox receiver source files.
for p in tracked_files:
    if p.startswith("receivers/roku/") or p.startswith("receivers/xbox-edge/"):
        if p not in exact_paths:
            exact_paths.append(p)

generator_paths = [
    p for p in tracked_files
    if p.endswith("prepare_pslink_live_tv_streaming.py")
]

symbols = [
    r"decodedAudioSampleCallback",
    r"audioSampleCallback",
    r"streamAudioSample",
    r"sendAudioSample",
    r"MEDIA_AUDIO_PCM16",
    r"mediaPacket",
    r"sendVideoSample",
    r"encodedVideoSampleCallback",
    r"AudioRecord",
    r"AudioPlaybackCapture",
    r"AudioTrack",
    r"audioLoop",
    r"Frame\.Binary",
    r"onMediaPacket",
    r"mediaPlayer\s*::\s*submit",
    r"media_config",
    r"media_config_ack",
    r"PSLM",
    r"pmtSection",
    r"patSection",
    r"PMT",
    r"PCR",
    r"writeAccessUnit",
    r"writePes",
    r"writeTsPacket",
    r"writePsi",
    r"mpeg.?ts",
    r"EXTINF",
    r"m3u8",
    r"AVPlay",
    r"HTMLVideoElement",
    r"<video",
    r"<audio",
    r"Video\s*\(",
    r"roAudioResource",
    r"AudioResource",
    r"audio\s*:",
    r"stream_url",
    r"WebSocket",
    r"subtitle",
]

compiled = [(s, re.compile(s, re.IGNORECASE)) for s in symbols]

files = []
for rel in exact_paths:
    path = ROOT / rel
    if path.is_file():
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except Exception as e:
            content = ""
            read_error = str(e)
        else:
            read_error = None
        files.append({
            "path": rel,
            "content": content,
            "kind": "SOURCE",
            "read_error": read_error
        })

for rel in generator_paths:
    path = ROOT / rel
    if path.is_file():
        content = path.read_text(encoding="utf-8", errors="replace")
        files.append({
            "path": rel,
            "content": content,
            "kind": "GENERATOR_ONLY",
            "read_error": None
        })

def classify(rel):
    if rel.startswith("mobile/"):
        return "Android mobile sender"
    if rel.startswith("tv/"):
        return "Android TV receiver"
    if rel.startswith("receivers/tizen/"):
        return "Samsung Tizen"
    if rel.startswith("receivers/webos/"):
        return "LG webOS"
    if rel.startswith("receivers/roku/"):
        return "Roku"
    if rel.startswith("receivers/xbox-edge/"):
        return "Xbox Edge"
    return "Generator / patch script"

def excerpts(content, limit=12, radius=3):
    lines = content.splitlines()
    hits = []
    for i, line in enumerate(lines):
        matched = [name for name, rx in compiled if rx.search(line)]
        if matched:
            hits.append((i, matched))

    selected = []
    occupied = set()
    for i, matched in hits:
        start = max(0, i - radius)
        end = min(len(lines), i + radius + 1)
        indexes = set(range(start, end))
        if occupied.intersection(indexes):
            continue
        selected.append((start, end, i, matched))
        occupied.update(indexes)
        if len(selected) >= limit:
            break

    result = []
    for start, end, i, matched in selected:
        block = []
        for n in range(start, end):
            block.append(f"{n+1:5}: {lines[n]}")
        result.append({
            "line": i + 1,
            "symbols": matched,
            "text": "\n".join(block)
        })
    return len(hits), result

file_results = []
evidence_parts = []

for item in files:
    count, blocks = excerpts(item["content"])
    record = {
        "path": item["path"],
        "kind": item["kind"],
        "platform": classify(item["path"]),
        "exists": True,
        "match_count": count,
        "excerpts": blocks,
        "read_error": item["read_error"]
    }
    file_results.append(record)

    evidence_parts.append(
        f"\n{'='*88}\n"
        f"FILE: {item['path']}\n"
        f"TYPE: {item['kind']}\n"
        f"PLATFORM: {record['platform']}\n"
        f"TOTAL MATCHING LINES: {count}\n"
        f"{'='*88}\n"
    )

    if item["read_error"]:
        evidence_parts.append("READ ERROR: " + item["read_error"] + "\n")
    elif not blocks:
        evidence_parts.append("No matching symbols found in this file.\n")
    else:
        for block in blocks:
            evidence_parts.append(
                f"\n--- Line {block['line']} | "
                f"Matches: {', '.join(block['symbols'])} ---\n"
                f"{block['text']}\n"
            )

existing = {x["path"] for x in files}
missing = [p for p in exact_paths if p not in existing]

# Per-platform evidence summary based on actual source files, not generator files.
platforms = {
    "Android mobile sender": [],
    "Android TV receiver": [],
    "Samsung Tizen": [],
    "LG webOS": [],
    "Roku": [],
    "Xbox Edge": [],
}

for rec in file_results:
    if rec["kind"] == "SOURCE" and rec["platform"] in platforms:
        platforms[rec["platform"]].append(rec)

def contains_any(records, patterns):
    for rec in records:
        p = ROOT / rec["path"]
        try:
            data = p.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for pattern in patterns:
            if re.search(pattern, data, re.IGNORECASE):
                return True
    return False

summary = {}
for platform, records in platforms.items():
    all_paths = [r["path"] for r in records]
    has_audio_send = contains_any(records, [
        r"sendAudioSample", r"MEDIA_AUDIO_PCM16", r"AudioTrack",
        r"audioLoop", r"roAudioResource"
    ])
    has_video = contains_any(records, [
        r"AVPlay", r"<video", r"HTMLVideoElement",
        r"Video\s*\(", r"stream_url", r"m3u8"
    ])
    has_pcm = contains_any(records, [
        r"MEDIA_AUDIO_PCM16", r"AudioTrack", r"roAudioResource",
        r"audioLoop"
    ])
    summary[platform] = {
        "inspected_source_files": all_paths,
        "audio_related_source_symbols_found": has_audio_send,
        "video_or_stream_source_symbols_found": has_video,
        "pcm_or_audio_playback_symbols_found": has_pcm,
        "runtime_verified": False
    }

generator_warning = (
    "The generator/patch script is inspected separately. "
    "Its contents are not proof that generated code is present in current source files."
)

md = []
md.append("# PS Link — Media Call-Path Audit 04")
md.append("")
md.append(f"- Date (UTC): {datetime.now(timezone.utc).isoformat()}")
md.append(f"- Branch: `{BRANCH}`")
md.append(f"- HEAD: `{head}`")
md.append(f"- Tracked files in repository: {len(tracked_files)}")
md.append(f"- Inspected source/generator files: {len(files)}")
md.append(f"- Missing expected paths: {len(missing)}")
md.append("")
md.append("## Audit scope")
md.append("")
md.append("- Trace audio/video symbols across sender, transport, receiver and playback code.")
md.append("- Inspect current tracked source files separately from generator/patch scripts.")
md.append("- This is a source inspection only. It does not build, install, stream, or test physical devices.")
md.append("")
md.append("## Platform inventory")
md.append("")
md.append("| Platform | Inspected source files | Audio-related symbols | Video/stream symbols | PCM/playback symbols | Runtime verified |")
md.append("|---|---:|---|---|---|---|")

for platform, data in summary.items():
    md.append(
        f"| {platform} | {len(data['inspected_source_files'])} | "
        f"{'Found' if data['audio_related_source_symbols_found'] else 'Not found'} | "
        f"{'Found' if data['video_or_stream_source_symbols_found'] else 'Not found'} | "
        f"{'Found' if data['pcm_or_audio_playback_symbols_found'] else 'Not found'} | No |"
    )

md.append("")
md.append("**Interpretation warning:** 'Found' means a matching implementation symbol exists in the inspected source. It does not prove the full call path works or that the device plays media.")
md.append("")
md.append("## Important distinctions")
md.append("")
md.append("1. A decoded-audio callback is not proof of microphone/system-audio capture. Check whether the source is Chiaki's decoded game audio or an Android capture API.")
md.append("2. A WebSocket parser recognizing an audio packet is not proof of audio playback.")
md.append("3. An HLS player receiving a stream URL is not proof that the MPEG-TS contains an audio elementary stream.")
md.append("4. A generator or patch script containing code is not proof that the generated source currently contains that code.")
md.append("5. Source-level evidence is not physical-device compatibility evidence.")
md.append("")
md.append("## Expected paths not found")
md.append("")
if missing:
    for p in missing:
        md.append(f"- `{p}`")
else:
    md.append("- None")
md.append("")
md.append("## Generator-only evidence")
md.append("")
if generator_paths:
    for p in generator_paths:
        md.append(f"- `{p}` — **generator/patch script only; not proof of current generated source.**")
else:
    md.append("- No matching generator script found in tracked files.")
md.append("")
md.append("## Source files inspected")
md.append("")
for rec in file_results:
    kind = "GENERATOR/PATCH ONLY" if rec["kind"] == "GENERATOR_ONLY" else "SOURCE"
    md.append(f"- `{rec['path']}` — {kind}; {rec['match_count']} matching lines")
md.append("")
md.append("## What this audit cannot establish")
md.append("")
md.append("- Successful Android builds or TV builds.")
md.append("- Successful pairing or real-time media delivery.")
md.append("- Audio/video synchronization on actual devices.")
md.append("- Tizen, webOS, Roku or Xbox hardware compatibility.")
md.append("- Actual codec support, playback stability, latency or audio output.")
md.append("")
md.append("## Integrity")
md.append("")
md.append("- Read-only audit: yes.")
md.append("- Source files intentionally modified: no.")
md.append("- Commit/push performed: no.")
md.append("- Git tracked diff before audit was clean: " + ("yes" if diff_before else "no"))
md.append("")
md.append("## Recommended next step")
md.append("")
md.append("Review `MEDIA-CALLPATH-EVIDENCE.txt` for the exact source excerpts. Use the evidence to prepare a platform-by-platform implementation plan before changing source code.")

(OUT / "MEDIA-CALLPATH-AUDIT.md").write_text("\n".join(md) + "\n", encoding="utf-8")
(OUT / "MEDIA-CALLPATH-EVIDENCE.txt").write_text(
    "\n".join(evidence_parts) + "\n\n" + generator_warning + "\n",
    encoding="utf-8"
)

status_after = status_snapshot()
diff_after = source_diff_clean()

# Exclude this audit output directory from status comparison.
out_rel = str(OUT.relative_to(ROOT)).replace("\\", "/")
def filtered_status(value):
    kept = []
    for line in value.splitlines():
        if out_rel in line:
            continue
        kept.append(line)
    return "\n".join(kept)

status_unchanged = filtered_status(status_before) == filtered_status(status_after)
tracked_diff_unchanged = (diff_before == diff_after)

result = {
    "branch": BRANCH,
    "head": head,
    "timestamp_utc": datetime.now(timezone.utc).isoformat(),
    "tracked_file_count": len(tracked_files),
    "inspected_file_count": len(files),
    "missing_expected_paths": missing,
    "platform_summary": summary,
    "generator_scripts": generator_paths,
    "read_only": True,
    "tracked_diff_clean_before": diff_before,
    "tracked_diff_clean_after": diff_after,
    "tracked_diff_state_unchanged": tracked_diff_unchanged,
    "git_status_unchanged_excluding_audit_output": status_unchanged
}

(OUT / "MEDIA-CALLPATH-AUDIT.json").write_text(
    json.dumps(result, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8"
)

print("===== AUDIT SUMMARY =====")
print("Branch:", BRANCH)
print("HEAD:", head)
print("Tracked files:", len(tracked_files))
print("Inspected files:", len(files))
print("Missing expected paths:", len(missing))
print()

for platform, data in summary.items():
    print(platform)
    print("  Source files:", len(data["inspected_source_files"]))
    print("  Audio symbols:", data["audio_related_source_symbols_found"])
    print("  Video/stream symbols:", data["video_or_stream_source_symbols_found"])
    print("  PCM/playback symbols:", data["pcm_or_audio_playback_symbols_found"])
    print("  Runtime verified: NO")
    print()

print("Tracked diff state unchanged:", tracked_diff_unchanged)
print("Git status unchanged (excluding audit output):", status_unchanged)

if not tracked_diff_unchanged:
    print("WARNING: Tracked diff state changed during audit.")
if not status_unchanged:
    print("WARNING: Git status changed outside the audit output directory.")

print()
print("REPORTS:")
print(OUT / "MEDIA-CALLPATH-AUDIT.md")
print(OUT / "MEDIA-CALLPATH-AUDIT.json")
print(OUT / "MEDIA-CALLPATH-EVIDENCE.txt")
PY

echo
echo "=============================================="
echo " AUDIT 04 FINISHED"
echo "=============================================="
echo "Output directory: $OUT"
echo
