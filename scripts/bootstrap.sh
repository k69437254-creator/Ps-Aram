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

mkdir -p "$ROOT/third_party"
if [[ ! -d "$DEST/.git" ]]; then
  if [[ -e "$DEST" ]]; then
    echo "ERROR: $DEST exists but is not a Git checkout. Move it aside; refusing to overwrite." >&2
    exit 3
  fi
  echo "Fetching official Chiaki-NG source..."
  git -C "$ROOT" init -q "$DEST"
  git -C "$DEST" remote add origin "$URL"
fi

cd "$DEST"
if ! git cat-file -e "$PIN^{commit}" 2>/dev/null; then
  git fetch --depth=1 origin "$PIN"
fi
git checkout --detach "$PIN"
git submodule sync --recursive
git submodule update --init --recursive

cd "$ROOT"
python3 scripts/prepare_upstream.py

echo "Bootstrap complete. Chiaki-NG pinned at $PIN."
