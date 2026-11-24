#!/usr/bin/env bash
# Also works in GitHub Actions / Linux bash.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PIN="43728af2e639fecb8a7af1cd68c29820a40de973"
URL="https://github.com/chiaki-ng/chiaki-ng.git"
DEST="$ROOT/third_party/chiaki-ng"

if ! command -v git >/dev/null 2>&1; then
  echo "ERROR: git is required. Install it first (Termux: pkg install git)." >&2
  exit 2
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required. Install it first (Termux: pkg install python)." >&2
  exit 3
fi

mkdir -p "$ROOT/third_party"
if [[ ! -d "$DEST/.git" ]]; then
  if [[ -e "$DEST" ]]; then
    echo "ERROR: $DEST exists but is not a Git checkout. Move it aside; refusing to overwrite." >&2
    exit 4
  fi
  echo "Fetching official Chiaki-NG source..."
  git -C "$ROOT" init -q "$DEST"
  git -C "$DEST" remote add origin "$URL"
fi

cd "$DEST"

# Ensure the pinned commit is present locally. Try shallow fetch first,
# then fall back to a full fetch if the pinned commit is not a shallow object.
if ! git cat-file -e "$PIN^{commit}" 2>/dev/null; then
  echo "Pinned commit $PIN not present locally. Fetching..."
  if ! git fetch --depth=1 origin "$PIN" 2>/dev/null; then
    echo "Shallow fetch failed; falling back to full fetch..."
    git fetch origin "$PIN"
  fi
fi

if ! git cat-file -e "$PIN^{commit}" 2>/dev/null; then
  echo "ERROR: Could not obtain pinned Chiaki-NG commit $PIN from $URL" >&2
  exit 5
fi

git checkout --detach "$PIN"
git submodule sync --recursive
git submodule update --init --recursive

# Validate that the pinned upstream source is intact before applying overlays.
if [[ ! -f "$DEST/CMakeLists.txt" ]]; then
  echo "ERROR: Chiaki-NG source is incomplete (CMakeLists.txt missing)." >&2
  exit 6
fi

cd "$ROOT"
python3 scripts/prepare_upstream.py

echo "Bootstrap complete. Chiaki-NG pinned at $PIN."
