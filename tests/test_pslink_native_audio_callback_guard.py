#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "scripts/prepare_pslink_live_tv_streaming.py"

source = GENERATOR.read_text(encoding="utf-8")

required = [
    "PSLINK_NATIVE_AUDIO_CALLBACK_BOUNDS_V1",
    "channels < 1 || channels > 2",
    "rate != 96000",
    "rate != 88200",
    "rate != 64000",
    "rate != 48000",
    "rate != 44100",
    "rate != 32000",
    "rate != 24000",
    "rate != 22050",
    "rate != 16000",
    "rate != 12000",
    "rate != 11025",
    "rate != 8000",
    "rate != 7350",
    "samples_count > (size_t)(256 * 1024) / sizeof(int16_t)",
    "samples_count % channels != 0",
    "pcm_bytes % ((size_t)channels * sizeof(int16_t)) != 0",
    "jsize length = (jsize)pcm_bytes;",
    "NewByteArray(env, length)",
]

missing = [item for item in required if item not in source]
if missing:
    raise SystemExit(
        "Native audio callback guard regression: missing:\n"
        + "\n".join(missing)
    )

print("Native PCM JNI callback bounds regression: PASS")
