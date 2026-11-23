#!/usr/bin/env bash
# PS LINK — MASTER PLATFORM AUDIT
# Read-only source audit. Reports are written under build/.
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERROR: Not inside the PS Link Git repository" >&2
  exit 1
}
cd "$ROOT"

BRANCH="$(git branch --show-current)"

[[ -s platforms/targets.json ]] || {
  echo "ERROR: platforms/targets.json is missing"
  exit 1
}

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 is required"
  exit 1
}

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="build/platform-audit-$STAMP"
mkdir -p "$OUT"

COMMIT="$(git rev-parse HEAD)"
git status --short > "$OUT/worktree-status.txt"
git ls-files > "$OUT/tracked-files.txt"

python3 - "$ROOT" "$OUT" "$BRANCH" "$COMMIT" "$STAMP" <<'PY'
import json
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
out = Path(sys.argv[2])
branch, commit, stamp = sys.argv[3:6]

targets_file = root / "platforms/targets.json"
targets_data = json.loads(targets_file.read_text(encoding="utf-8"))
targets = targets_data.get("targets", [])

if not isinstance(targets, list) or not targets:
    raise SystemExit("ERROR: Invalid or empty platform target matrix")

tracked = subprocess.check_output(
    ["git", "ls-files"], cwd=root, text=True
).splitlines()

status = subprocess.check_output(
    ["git", "status", "--short"], cwd=root, text=True
)

groups = {
    "android-tv": ("mobile/", "tv/", "core/"),
    "google-tv": ("mobile/", "tv/", "core/"),
    "fire-tv": ("mobile/", "tv/", "core/"),
    "tizen": ("receivers/tizen/",),
    "webos": ("receivers/webos/",),
    "roku": ("receivers/roku/",),
    "xbox-edge": ("receivers/xbox-edge/",),
}

def files_for(prefixes):
    return [p for p in tracked if p.startswith(prefixes)]

platform_inventory = {}
for platform, prefixes in groups.items():
    platform_inventory[platform] = files_for(prefixes)

workflow_files = [
    p for p in tracked
    if p.startswith(".github/workflows/")
]

test_files = [
    p for p in tracked
    if any(token in p.lower() for token in
           ("test", "spec", "verify", "validate", "check"))
    and (p.startswith("scripts/") or
         p.startswith("receivers/") or
         p.startswith("mobile/") or
         p.startswith("tv/") or
         p.startswith("core/") or
         p.startswith(".github/"))
]

checklist_path = root / "receivers/DEVICE-VALIDATION.md"
checklist = ""
if checklist_path.is_file():
    checklist = checklist_path.read_text(encoding="utf-8")

unchecked = len(re.findall(r"^\s*-\s*\[\s\]", checklist, re.M))
checked = len(re.findall(r"^\s*-\s*\[[xX]\]", checklist, re.M))

todo = subprocess.run(
    ["git", "grep", "-n", "-I", "-E",
     "TODO|FIXME|NOT_IMPLEMENTED|IMPLEMENT_ME", "--",
     ":!*.lock", ":!**/build/**"],
    cwd=root, text=True, capture_output=True
).stdout.splitlines()

tools = {}
for name in ("java", "javac", "adb", "sdkmanager",
             "gradle", "node", "npm", "shellcheck"):
    result = subprocess.run(
        ["bash", "-lc", f"command -v {name}"],
        text=True, capture_output=True
    )
    tools[name] = result.stdout.strip() or "unavailable"

lines = [
    "# PS Link — Master Platform Audit",
    "",
    f"- Generated UTC: {stamp}",
    f"- Branch: `{branch}`",
    f"- Commit: `{commit}`",
    f"- Tracked files: {len(tracked)}",
    f"- Worktree: {'HAS LOCAL CHANGES' if status.strip() else 'CLEAN'}",
    "",
    "> This is a source inventory. It does not prove successful builds,",
    "> real media playback, or physical-device compatibility.",
    "",
    "## 1. Platform target matrix",
    "",
    "| ID | Platform | Technology | State | Remaining validation |",
    "|---|---|---|---|---|",
]

for item in targets:
    values = [
        item.get("id", ""),
        item.get("name", ""),
        item.get("technology", ""),
        item.get("state", ""),
        item.get("validation", ""),
    ]
    values = [str(v).replace("|", "\\|").replace("\n", " ")
              for v in values]
    lines.append("| " + " | ".join(values) + " |")

lines += ["", "## 2. Platform source inventory", ""]
for platform, files in platform_inventory.items():
    lines.append(f"### {platform} — {len(files)} tracked files")
    for name in files[:35]:
        lines.append(f"- `{name}`")
    if len(files) > 35:
        lines.append(f"- ... and {len(files)-35} more files")
    lines.append("")

lines += ["## 3. Build entry points", ""]
required = [
    "gradlew",
    "settings.gradle.kts",
    "build.gradle.kts",
    "scripts/bootstrap.sh",
    "scripts/check-source.sh",
    "scripts/verify-hls-ts.sh",
    "scripts/verify-platform-completion-pack.sh",
    ".github/workflows/android.yml",
    ".github/workflows/tv-web-receiver.yml",
    ".github/workflows/tv-native-web-receivers.yml",
]
for name in required:
    state = "PRESENT" if (root / name).is_file() else "MISSING"
    lines.append(f"- {state}: `{name}`")

lines += ["", "## 4. CI workflows", ""]
lines += [f"- `{p}`" for p in workflow_files]
lines += ["", "## 5. Test and validation files", ""]
lines += [f"- `{p}`" for p in test_files[:250]]
if len(test_files) > 250:
    lines.append(f"- ... and {len(test_files)-250} more")

lines += [
    "",
    "## 6. Physical-device checklist",
    "",
    f"- Unchecked items: {unchecked}",
    f"- Checked items: {checked}",
    "",
]

if not checklist:
    lines.append("- DEVICE-VALIDATION.md is missing")
else:
    for line in checklist.splitlines():
        if line.startswith("### ") or re.match(
            r"^\s*-\s*\[[ xX]\]", line
        ):
            lines.append(line)

lines += ["", "## 7. TODO / FIXME markers", ""]
lines += [f"- `{line}`" for line in todo[:200]]
if not todo:
    lines.append("- No matching tracked markers found.")

lines += ["", "## 8. Local tool availability", ""]
for name, value in tools.items():
    lines.append(f"- {name}: `{value}`")

lines += [
    "",
    "## 9. Local worktree changes",
    "",
    "```text",
    status.rstrip() or "No local changes",
    "```",
    "",
    "## 10. Audit boundaries",
    "",
    "- Android TV, Google TV and Fire TV share Android code,",
    "  but each target needs separate compatibility validation.",
    "- Tizen and webOS need packaging, signing and native playback checks.",
    "- Roku needs SceneGraph/BrightScript package and device validation.",
    "- Xbox Edge is a browser receiver, not a native Xbox app.",
    "- CI results are not physical-device test results.",
    "- This audit does not modify tracked source files or target states.",
    "",
    "## 11. Recommended next step",
    "",
    "Build isolated work queues from these findings. Keep shared protocol",
    "contracts centralized and implement platform adapters independently.",
    "Do not allow concurrent writes to the same branch.",
]

(out / "MASTER-AUDIT.md").write_text(
    "\n".join(lines) + "\n", encoding="utf-8"
)

payload = {
    "generated_utc": stamp,
    "branch": branch,
    "head": commit,
    "target_count": len(targets),
    "targets": targets,
    "tracked_file_count": len(tracked),
    "worktree_dirty": bool(status.strip()),
    "worktree_status": status.splitlines(),
    "platform_file_counts": {
        key: len(value) for key, value in platform_inventory.items()
    },
    "workflow_files": workflow_files,
    "test_files": test_files,
    "device_checklist": {
        "unchecked": unchecked,
        "checked": checked,
    },
    "available_tools": tools,
    "todo_markers": todo[:200],
    "audit_scope": "read-only source inventory",
}

(out / "MASTER-AUDIT.json").write_text(
    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8"
)

print("MASTER AUDIT REPORT CREATED")
print(out / "MASTER-AUDIT.md")
print(out / "MASTER-AUDIT.json")
print()
print("Platform inventory:")
for name, files in platform_inventory.items():
    print(f"  {name}: {len(files)} tracked files")
print()
print(f"Device checklist: {unchecked} unchecked / {checked} checked")
print(f"Tracked files: {len(tracked)}")
PY

echo
echo "========================================"
echo " PS LINK — MASTER AUDIT COMPLETE"
echo "========================================"
echo "Branch: $BRANCH"
echo "Commit: ${COMMIT:0:12}"
echo "Output: $OUT"
echo
echo "No tracked source files were modified."
