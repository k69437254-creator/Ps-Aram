#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REPO_URL="${1:-}"
if [[ -z "$REPO_URL" ]]; then
  echo "Usage: scripts/push-github.sh https://github.com/USERNAME/PS-Link.git" >&2
  exit 2
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git init
fi
CURRENT_BRANCH="$(git branch --show-current || true)"
if [[ "$CURRENT_BRANCH" != "main" ]]; then
  git branch -M main
fi

if git remote get-url origin >/dev/null 2>&1; then
  EXISTING="$(git remote get-url origin)"
  if [[ "$EXISTING" != "$REPO_URL" ]]; then
    echo "ERROR: origin already points to $EXISTING; refusing to replace it." >&2
    exit 3
  fi
else
  git remote add origin "$REPO_URL"
fi

git add -A
if ! git diff --cached --quiet; then
  git commit -m "Initialize PS Link Android project"
else
  echo "No new changes to commit."
fi

git push -u origin main
