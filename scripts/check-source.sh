#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 scripts/validate_package.py
bash -n scripts/bootstrap.sh scripts/build.sh scripts/push-github.sh
python3 -m py_compile scripts/prepare_upstream.py scripts/validate_package.py
printf '\nStatic source checks passed. Android compilation still requires Android SDK, NDK and upstream checkout.\n'
