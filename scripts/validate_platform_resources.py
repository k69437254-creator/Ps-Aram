# PSLINK_PLATFORM_RESOURCE_VALIDATOR_V1
from pathlib import Path
import json
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "receivers"

if not ROOT.is_dir():
    raise SystemExit("FAIL: receivers directory is missing")

files = sorted(
    p for p in ROOT.rglob("*")
    if p.is_file() and p.suffix.lower() in {".json", ".xml"}
)

errors = []

for path in files:
    try:
        if path.suffix.lower() == ".json":
            with path.open("r", encoding="utf-8") as stream:
                json.load(stream)
        else:
            ET.parse(path)
        print("PASS:", path.relative_to(ROOT.parent))
    except Exception as exc:
        errors.append((path, str(exc)))
        print("FAIL:", path.relative_to(ROOT.parent), "-", exc)

print(f"Validated {len(files)} JSON/XML files.")

if errors:
    raise SystemExit(f"Resource validation failed: {len(errors)} error(s)")

print("PS LINK PLATFORM JSON/XML VALIDATION PASSED")
