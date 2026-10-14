#!/usr/bin/env python3
"""Package current Android V6 source of truth and validate actual editable icon inventory.

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

# Keep the historical 26-icon editable sheet in Git, but never export retired
# Projects / RunCenter tiles in the currently supported 24-icon design pack.
SVG_NS = "{http://www.w3.org/2000/svg}"
RETIRED = {"Projects", "RunCenter"}

def active_entry_icon_sheet(raw: bytes) -> bytes:
    root = ElementTree.fromstring(raw)
    top_level_tiles = root.findall(f"{SVG_NS}g")
    if len(top_level_tiles) == 24:
        labels = [e.text for e in root.iter(f"{SVG_NS}text") if e.text and re.fullmatch(r"[A-Za-z][A-Za-z0-9]+", e.text)]
        if len(set(labels)) != 24 or RETIRED.intersection(labels):
            raise ValueError("The checked-in active icon sheet contains duplicate or retired labels")
        return raw
    if len(top_level_tiles) != 26:
        raise ValueError(f"Expected 24 active or 26 historical icon tiles, found {len(top_level_tiles)}")
    removed = set()
    for tile in top_level_tiles:
        labels = {el.text for el in tile.findall(f"{SVG_NS}text")}
        matching = labels & RETIRED
        if matching:
            removed.update(matching)
            root.remove(tile)
    if removed != RETIRED or len(root.findall(f"{SVG_NS}g")) != 24:
        raise ValueError(f"Retired icon mismatch: {removed}, expected {RETIRED}")
    for child in root.findall(f"{SVG_NS}text"):
        if child.text and "26 枚" in child.text:
            child.text = child.text.replace("26 枚", "24 枚")
    ElementTree.register_namespace("", SVG_NS[1:-1])
    cleaned = ElementTree.tostring(root, encoding="utf-8", xml_declaration=True)
    ElementTree.fromstring(cleaned)
    if any(name.encode("utf-8") in cleaned for name in RETIRED):
        raise ValueError("Retired entry icon still exists in exported sheet")
    return cleaned

parser = argparse.ArgumentParser(description="Package current V6 mobile design and semantic sources")
parser.add_argument("--source", type=Path, required=True, help="Repository docs/design/v6 directory")
parser.add_argument("--output", type=Path, required=True, help="Destination ZIP archive")
args = parser.parse_args()
source, output = args.source.resolve(), args.output.resolve()
if source.name == "mobile" and (source.parent / "current-ui.json").is_file():
    source = source.parent
required = (
    "mobile/app.html", "mobile/index.html", "mobile/v6.css", "mobile/v6.js", "build-standalone.py", "current-ui.json", "V6-CURRENT-IMPLEMENTATION.zh-CN.md",
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


# Review the actual generated icon assets, not the advertised count in a Markdown file.
entry_svg = source / "sy-entry-icons.svg"
root = ElementTree.parse(entry_svg).getroot()
ns = "{http://www.w3.org/2000/svg}"
icon_labels = [node.text for node in root.iter(ns + "text") if node.text and re.fullmatch(r"[A-Za-z][A-Za-z0-9]+", node.text)]
expected_icons = int(manifest["iconInventory"]["activeCount"])
retired_names = set(manifest["iconInventory"]["retired"])
if len(icon_labels) != expected_icons or len(set(icon_labels)) != expected_icons:
    parser.error("Active SVG sprite must contain exactly 24 distinct named entry glyphs")
if retired_names.intersection(icon_labels):
    parser.error("Retired entry icons re-entered current V6 SVG sheet")
asset_match = re.search(r'window\.V6_ASSET_DATA\s*=\s*(\{.*?\})\s*;', html, re.S)
if not asset_match:
    parser.error("V6 standalone HTML missing embedded offline icon sources")
icons = json.loads(asset_match.group(1))
if set(icon_labels) != set(icons):
    parser.error("Standalone HTML and editable SVG sheet have different active icon sets")
if 'data-v6-authoritative="compose-' not in html:
    parser.error("Offline design lost Compose source marker")

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

payload = {p.relative_to(source).as_posix(): p.read_bytes() for p in allowed_files}
# The V6 deliverable is not the historical overview sheet: filter at export time.
payload["sy-entry-icons.svg"] = active_entry_icon_sheet(payload["sy-entry-icons.svg"])
rows = [hashlib.sha256(data).hexdigest() + "  " + name for name, data in payload.items()]
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=8) as z:
    for name, data in payload.items():
        z.writestr(name, data)
    z.writestr("MANIFEST.sha256", "\n".join(rows) + "\n")
with zipfile.ZipFile(output) as z:
    if z.testzip() is not None:
        raise SystemExit("ZIP CRC validation failed")
    for line in z.read("MANIFEST.sha256").decode("utf-8").splitlines():
        sha, filename = line.split("  ", 1)
        if hashlib.sha256(z.read(filename)).hexdigest() != sha:
            raise SystemExit("ZIP manifest checksum mismatch: " + filename)
print(json.dumps({
    "archive": str(output), "files": len(payload)+1, "activeEntryIcons": 24,
    "bytes": output.stat().st_size, "routes": routes,
    "sourceOfTruth": manifest["sourceOfTruth"],
    "warning": "HTML preview and ZIP are not Android device/Figma verification",
}, ensure_ascii=False))
