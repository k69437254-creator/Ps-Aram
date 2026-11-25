#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 scripts/validate_package.py
bash -n scripts/bootstrap.sh scripts/build.sh scripts/push-github.sh scripts/check-ps5-wiring.sh
python3 -m py_compile scripts/prepare_upstream.py scripts/validate_package.py scripts/prepare_pslink_upstream.py scripts/prepare_pslink_stream_recording.py scripts/prepare_pslink_audio_recording.py scripts/prepare_pslink_complete_fix.py scripts/prepare_pslink_live_tv_streaming.py
# PSLINK_REMAINING_REPAIRS_V3: tests for Chiaki PCM -> AAC -> HLS routing and PTS ordering
python3 tests/test_hls_audio_repair_contracts.py
# PSLINK_RESOURCE_BACKUP_GUARD_V1
python3 scripts/test_pslink_backup_storage.py
for resource_dir in mobile/src/main/res tv/app/src/main/res third_party/chiaki-ng/android/app/src/main/res; do
    [[ -d "$resource_dir" ]] || continue
    invalid_resource="$(find "$resource_dir" -type f \( -name '*.before-pslink-*.bak' -o -name '*.pslink-tmp-*' \) -print -quit)"
    if [[ -n "$invalid_resource" ]]; then
        echo "ERROR: PS Link backup/temp file found inside Android resources: $invalid_resource" >&2
        echo "Move it under .pslink-backups/ before running Gradle." >&2
        exit 1
    fi
done
bash scripts/check-pslink-features.sh
if [[ -f third_party/chiaki-ng/CMakeLists.txt ]]; then
    bash scripts/check-ps5-wiring.sh
else
    echo "PS5 native wiring check skipped: run bash scripts/bootstrap.sh first."
fi
printf '\nStatic source checks passed. A successful compile and a real PS5 device test are still required.\n'
