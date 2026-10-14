#!/usr/bin/env python3
"""Package current Android V6 source of truth. Never inherit historical 38-screen archives.

Usage: python docs/design/v6/sync-design-package.py \
  --source docs/design/v6 --output /tmp/shenyan-v6-current.zip

This utility packages only tracked-design deliverables, not APKs, old previews, or fonts.
The resulting ZIP is a reproducible design hand-off, NOT device/Figma acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree

parser = argparse.ArgumentParser(description="Package current V6 mobile design and semantic sources")
parser.add_argument("--source", type=Path, required=True, help="Repository docs/design/v6 directory")
parser.add_argument("--output", type=Path, required=True, help="Destination ZIP archive")
args = parser.parse_args()
source, output = args.source.resolve(), args.output.resolve()
if source.name == "mobile" and (source.parent / "current-ui.json").is_file():
    source = source.parent
required = (
    "mobile/app.html", "current-ui.json", "V6-CURRENT-IMPLEMENTATION.zh-CN.md",
    "README.zh-CN.md", "STATUS.zh-CN.md", "SY-ENTRY-ICONS.zh-CN.md",
    "sy-entry-icons.svg", "file-icons.json", "assets/file-icons-preview.svg",
)
if any(not (source / name).is_file() for name in required):
    parser.error("Expected current checked-in docs/design/v6 with V6 spec, manifest and editable sources")
manifest = json.loads((source / "current-ui.json").read_text(encoding="utf-8"))
routes = [item["id"] for item in manifest["routes"]]
html = (source / "mobile/app.html").read_text(encoding="utf-8")
match = re.search(r"const\s+V6_ROUTES\s*=\s*(\[[^\]]+\])", html)
if not match or json.loads(match.group(1)) != routes:
    parser.error("V6 prototype routes differ from current-ui.json")
if routes != ["HOME", "PERSONA_GALLERY", "DIARY", "WORKSPACE", "TASKS", "TOOLS", "SETTINGS"]:
    parser.error("V6 route inventory changed; audit and update package contract before exporting")
if any(s in html for s in ("S03Board", "S02v2Work", "mobile-screen-tabs", "38 场景")):
    parser.error("Obsolete design screens/controls re-entered current prototype")

allowed_files = [source / name for name in required]
allowed_files += sorted(p for p in (source / "assets").rglob("*") if p.is_file())
allowed_files = sorted(set(allowed_files), key=lambda p: p.relative_to(source).as_posix())
if any(not p.is_relative_to(source) for p in allowed_files):
    parser.error("Packaging path escapes design root")
for item in allowed_files:
    name = item.relative_to(source).as_posix()
    if item.is_symlink():
        parser.error("Symlinks are forbidden: " + name)
    if re.search(r"(?:^|/)(?:compose-v[2-5]|history-v\d|_preview-v|references|supplements)(?:/|$)", name, re.I):
        parser.error("Old edition design artifact is forbidden: " + name)
    if item.suffix.lower() in {".apk", ".ttf", ".otf", ".zip"}:
        parser.error("Binary or nested archive is forbidden: " + name)
    if item.suffix.lower() in {".svg", ".xml"}:
        ElementTree.parse(item)

rows = [
    hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.relative_to(source).as_posix()
    for p in allowed_files
]
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=8) as z:
    for item in allowed_files:
        z.write(item, item.relative_to(source).as_posix())
    z.writestr("MANIFEST.sha256", "\n".join(rows) + "\n")
with zipfile.ZipFile(output) as z:
    if z.testzip() is not None:
        raise SystemExit("ZIP CRC validation failed")
    for line in z.read("MANIFEST.sha256").decode("utf-8").splitlines():
        sha, filename = line.split("  ", 1)
        if hashlib.sha256(z.read(filename)).hexdigest() != sha:
            raise SystemExit("ZIP manifest checksum mismatch: " + filename)
print(json.dumps({
    "archive": str(output), "files": len(allowed_files)+1,
    "bytes": output.stat().st_size, "routes": routes,
    "sourceOfTruth": manifest["sourceOfTruth"],
    "warning": "HTML preview and ZIP are not Android device/Figma verification",
}, ensure_ascii=False))
