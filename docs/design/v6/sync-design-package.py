#!/usr/bin/env python3
"""Package exactly the current V6 PHONE design folder. Never inherit old editions."""
from __future__ import annotations
import argparse, hashlib, json, re, zipfile
from pathlib import Path
from xml.etree import ElementTree

parser = argparse.ArgumentParser(description="Only V6 phone artwork, source and current previews")
parser.add_argument("--source", type=Path, required=True, help="Unpacked current V6 phone folder")
parser.add_argument("--output", type=Path, required=True, help="New ZIP archive file")
args = parser.parse_args()
source, output = args.source.resolve(), args.output.resolve()
if not source.is_dir() or not (source / "app.html").is_file():
    parser.error("Expected current V6 mobile directory with app.html")
html = (source / "app.html").read_text(encoding="utf-8")
tokens = json.loads((source / "semantic-design-tokens.json").read_text(encoding="utf-8"))
if "shenyan.mobile.design.v6" != tokens.get("schema"):
    parser.error("Only normalized mobile V6 tokens are accepted")
ids = re.findall(r"'([0-9]{2})':\\{n:'", html)
if sorted(ids) != [f"{n:02d}" for n in range(1, 39)]:
    parser.error("The prototype must include exactly the same 38 screens")
if "function toggleDesktopSidebar" in html or "sy-desktop-toggle" in html and 'type="button"' in html.split("sy-desktop-toggle",1)[-1][:90]:
    parser.error("Desktop toolbar is forbidden in the canonical phone design")
icons = sorted((source / "assets/v6/entry-icons").glob("*.svg"))
editable = [p for p in icons if p.name != "entry-icons-preview.svg"]
if len(editable) != 26:
    parser.error("Expected exactly 26 separate entry icon SVGs")
allowed_roots = {"app.html", "README.zh-CN.md", "SCREENS.zh-CN.md",
                 "semantic-design-tokens.json", "INTEGRITY.json",
                 "QA-RESULT.json", "assets", "android-v6", "preview", "source"}
allfiles = sorted(p for p in source.rglob("*") if p.is_file() and p.name != "MANIFEST.sha256")
if any(p.relative_to(source).parts[0] not in allowed_roots for p in allfiles):
    parser.error("Found unapproved files: old editions or unexpected content")
for path in allfiles:
    name = path.relative_to(source).as_posix()
    if re.search(r"(?:^|/)(?:compose-v[2-5]|history-v\\d|_preview-v|supplements|references)(?:/|$)", name, re.I):
        parser.error("Legacy assets are forbidden: " + name)
    if path.suffix.lower() in {".apk", ".ttf", ".otf"}:
        parser.error("APK/font binary not allowed in this design archive: " + name)
    if path.suffix == ".svg" or path.suffix == ".xml":
        ElementTree.parse(path)
rows = [hashlib.sha256(p.read_bytes()).hexdigest()+"  "+p.relative_to(source).as_posix()
        for p in allfiles]
manifest = ("\\n".join(rows)+"\\n").encode()
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=8) as z:
    for path in allfiles:
        z.write(path, path.relative_to(source).as_posix())
    z.writestr("MANIFEST.sha256", manifest)
with zipfile.ZipFile(output) as z:
    if z.testzip() is not None:
        raise SystemExit("ZIP CRC verification failed")
    for row in z.read("MANIFEST.sha256").decode().splitlines():
        sha, name = row.split("  ", 1)
        if hashlib.sha256(z.read(name)).hexdigest() != sha:
            raise SystemExit("Hash mismatch: "+name)
print(json.dumps({"archive":str(output),"files":len(allfiles)+1,
                  "bytes":output.stat().st_size,"screenCount":38,
                  "editableIcons":26},ensure_ascii=False))
