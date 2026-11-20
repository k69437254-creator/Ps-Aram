#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 scripts/validate_package.py
bash -n scripts/bootstrap.sh scripts/build.sh scripts/push-github.sh scripts/check-ps5-wiring.sh
python3 -m py_compile scripts/prepare_upstream.py scripts/validate_package.py scripts/prepare_pslink_upstream.py
bash scripts/check-pslink-features.sh
if [[ -f third_party/chiaki-ng/CMakeLists.txt ]]; then
    bash scripts/check-ps5-wiring.sh
else
    echo "PS5 native wiring check skipped: run bash scripts/bootstrap.sh first."
fi
printf '\nStatic source checks passed. A successful compile and a real PS5 device test are still required.\n'
