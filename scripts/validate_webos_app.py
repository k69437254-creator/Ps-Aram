#!/usr/bin/env python3
"""Validate the PS Link LG webOS application package resources."""

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import json
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "receivers" / "webos"

def fail(message):
    print("FAIL:", message, file=sys.stderr)
    raise SystemExit(1)

def require_file(path, label):
    if not path.is_file():
        fail(f"Missing {label}: {path.relative_to(ROOT)}")

def safe_local_path(base, value, label):
    value = value.strip()
    if not value or value.startswith("#"):
        return
    parsed = urlsplit(value)
    if parsed.scheme or parsed.netloc:
        return
    candidate = (base / parsed.path).resolve()
    try:
        candidate.relative_to(APP.resolve())
    except ValueError:
        fail(f"{label} escapes the application directory: {value}")
    require_file(candidate, f"{label}: {value}")

class ResourceParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.references = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("src"):
            self.references.append(("script", attrs["src"]))
        elif tag == "link":
            rel = attrs.get("rel", "").lower().split()
            if "stylesheet" in rel and attrs.get("href"):
                self.references.append(("stylesheet", attrs["href"]))

meta_path = APP / "appinfo.json"
require_file(meta_path, "appinfo.json")

try:
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"Invalid appinfo.json: {exc}")

if meta.get("id") != "com.pslink.tvreceiver":
    fail("Unexpected webOS application ID.")

if meta.get("type") != "web":
    fail("Application type must be web.")

if not isinstance(meta.get("version"), str) or not meta["version"].strip():
    fail("Application version is missing.")

if not isinstance(meta.get("title"), str) or not meta["title"].strip():
    fail("Application title is missing.")

if len(meta["title"]) > 20:
    fail("Application title exceeds LG's 20-character limit.")

if not re.fullmatch(r"#[0-9A-Fa-f]{6}", str(meta.get("iconColor", ""))):
    fail("iconColor must be a six-digit hexadecimal color.")

if not isinstance(meta.get("requiredACG"), list):
    fail("requiredACG must be an array.")

for key, expected in (("icon", (80, 80)), ("largeIcon", (130, 130))):
    name = meta.get(key)
    if not isinstance(name, str) or not name:
        fail(f"appinfo.json is missing {key}.")
    icon_path = APP / name
    require_file(icon_path, key)
    data = icon_path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        fail(f"{name} is not a valid PNG file.")
    width, height = struct.unpack(">II", data[16:24])
    if (width, height) != expected:
        fail(f"{name} must be {expected[0]}x{expected[1]}, got {width}x{height}.")

main = meta.get("main")
if not isinstance(main, str) or not main:
    fail("appinfo.json main entry is missing.")

safe_local_path(APP, main, "main entry")

html_path = APP / main
parser = ResourceParser()
try:
    parser.feed(html_path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"Cannot parse main HTML: {exc}")

checked = 0
for label, value in parser.references:
    safe_local_path(html_path.parent, value, label)
    if value and not urlsplit(value).scheme and not value.startswith("#"):
        checked += 1

css_files = [
    APP / value
    for label, value in parser.references
    if label == "stylesheet"
]

url_pattern = re.compile(r"""url\(\s*(['"]?)(.*?)\1\s*\)""", re.I)
import_pattern = re.compile(r"""@import\s+(?:url\()?['"]([^'"]+)['"]""", re.I)

for css_path in css_files:
    css = css_path.read_text(encoding="utf-8")
    references = [
        match.group(2).strip()
        for match in url_pattern.finditer(css)
    ]
    references.extend(
        match.group(1).strip()
        for match in import_pattern.finditer(css)
    )
    for value in references:
        safe_local_path(css_path.parent, value, "CSS resource")
        if value and not urlsplit(value).scheme and not value.startswith("#"):
            checked += 1

for optional_key in ("splashBackground",):
    value = meta.get(optional_key)
    if value:
        safe_local_path(APP, value, optional_key)

print("webOS metadata: valid")
print("Application ID:", meta["id"])
print("Application version:", meta["version"])
print("Small and large PNG icons: valid")
print("HTML/CSS local resource references checked:", checked)
print("WEBOS_RESOURCE_VALIDATION=PASSED")
