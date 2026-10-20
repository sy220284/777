#!/usr/bin/env python3
"""CI contract: every V6.3 app icon has a matching editable SVG and sane dimensions."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
DESIGN = ROOT / "docs/design/v6"
DRAW = ROOT / "app/src/main/res/drawable"
SVG = "{http://www.w3.org/2000/svg}"
ANDROID = "{http://schemas.android.com/apk/res/android}"
issues = []

def check(ok, message):
    if not ok:
        issues.append(message)

def xml(path):
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        issues.append(f"{path.relative_to(ROOT)}: {exc}")
        return None

def paths(svgroot):
    return [p.attrib["d"] for p in svgroot.iter(SVG + "path") if "d" in p.attrib]

def check_svg_glyph(path, size=24, stroke="1.75"):
    root = xml(path)
    if root is None:
        return None
    check(root.tag == SVG + "svg", f"{path}: not an SVG")
    check(root.get("viewBox") == f"0 0 {size} {size}", f"{path}: viewport mismatch")
    check(root.get("stroke-width") == stroke, f"{path}: stroke mismatch")
    check(root.get("stroke-linecap") == "round", f"{path}: square cap")
    check(root.get("stroke-linejoin") == "round", f"{path}: square join")
    pts = paths(root)
    check(bool(pts), f"{path}: missing paths")
    for d in pts:
        check(bool(re.fullmatch(r"[0-9\s.,+\-MLHVQCASTZmlhvqcastzEe]+", d)),
              f"{path}: path contains unrecognized content")
    return pts

# Kotlin -> SVG: check path-by-path equivalence, not only catalog cardinality.
kotlin = (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/components/FeatherIcons.kt").read_text()
matches = list(re.finditer(
    r'val\s+(\w+)\s*:\s*ImageVector\s+by\s+lazy\s*\{\s*actionGlyph\("(\w+)",(.*?)\)\s*\}',
    kotlin, re.S,
))
names = [m.group(1) for m in matches]
action_dir = DESIGN / "assets/icons-actions"
assets = sorted(action_dir.glob("*.svg"))
check(len(names) == 61 and len(set(names)) == 61, "FeatherIcons.kt: expected 61 unique V6 action glyphs")
check({p.stem for p in assets} == set(names), "61 action SVG names differ from Kotlin API")
for m in matches:
    name, declared, block = m.group(1), m.group(2), m.group(3)
    check(name == declared, f"FeatherIcons.kt: {name} vs {declared}")
    strokes = re.findall(r'^\s*"([^"]+)"\s*,?\s*$', block, re.M)
    pt = check_svg_glyph(action_dir / f"{name}.svg")
    if pt is not None:
        check(strokes == pt, f"{name}: Compose and SVG pathData differ")
check("strokeLineWidth = 1.75f" in kotlin, "V6 action Kotlin stroke width must be 1.75")

# 777 paper-and-ink: verify current entry and supplemental SVGs match production.
entry_catalog = json.loads((DESIGN / "paper-ink-icon-catalog.json").read_text())
entry_kotlin = (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/components/SyEntryIcons.kt").read_text()
entry_matches = list(re.finditer(
    r'val\s+(\w+)\s*:\s*ImageVector\s+by\s+lazy\s*\{\s*entryGlyph\("(\w+)",([\s\S]*?)\)\s*\}',
    entry_kotlin,
))
current = set(entry_catalog["official"])
extra = set(entry_catalog["supplemental"])
check(len(current) == 24 and len(extra) == 3, "expected 24 current and 3 extra entry glyphs")
check("Projects" not in current and "RunCenter" not in current, "retired route became active")
check(current | extra <= {m.group(1) for m in entry_matches}, "Kotlin paper-ink glyph missing")
for m in entry_matches:
    name, declared, block = m.group(1), m.group(2), m.group(3)
    if name not in current | extra:
        continue
    check(name == declared, f"entry glyph name mismatch: {name}")
    strokes = re.findall(r'^\s*"([^"]+)"\s*,?\s*
file_spec = json.loads((DESIGN / "file-icons.json").read_text())
kinds = set(file_spec["glyphs"])
check(len(kinds) == 10, "file-icons.json must have 10 semantic file glyphs")
check(set(file_spec["labels"]) == kinds, "missing file icon labels")
check(set(file_spec["themes"]) == {"light", "dark"}, "file icons require light/dark modes")
for kind in kinds:
    for theme in ("light", "dark"):
        name = f"ic_ui_file{kind}_{theme}"
        x = xml(DRAW / f"{name}.xml")
        y = xml(DESIGN / f"assets/file-icons/{name}.svg")
        if x is None or y is None:
            continue
        check(x.get(ANDROID + "viewportWidth") == "40" and
              x.get(ANDROID + "viewportHeight") == "40", f"{name}: expected 40dp viewport")
        check(x.get(ANDROID + "width") == "40dp" and x.get(ANDROID + "height") == "40dp",
              f"{name}: legacy 52x68 resource leaked back")
        android_paths = [p.get(ANDROID + "pathData") for p in x.findall("path")]
        check(android_paths == paths(y), f"{name}: SVG and XML shapes differ")
        check(len(android_paths) == 3, f"{name}: expected material, fold, category paths")
        for path in x.findall("path"):
            check(path.get(ANDROID + "strokeWidth") == "1.75", f"{name}: nonstandard line width")
        check(y.get("viewBox") == "0 0 40 40", f"{name}: wrong SVG viewport")

# Android standalone glyphs: utility, stop/send contrast and large illustrations.
misc_dir = DESIGN / "assets/icons-misc"
misc = json.loads((misc_dir / "manifest.json").read_text())
check(misc["total"] == 11 and len(set(misc["items"])) == 11, "misc icon count mismatch")
for name in misc["items"]:
    x = xml(DRAW / f"{name}.xml")
    y = xml(misc_dir / f"{name}.svg")
    if x is None or y is None:
        continue
    check([p.get(ANDROID + "pathData") for p in x.findall("path")] == paths(y),
          f"{name}: Android drawable differs from SVG redraw")
    expected = 100 if name in misc["illustrations"] else 32 if name in misc["whiteOnAccent"] else 24
    check(x.get(ANDROID + "width") == f"{expected}dp", f"{name}: wrong nominal display size")
    check(x.get(ANDROID + "viewportWidth") == str(100 if expected == 100 else 24),
          f"{name}: wrong SVG grid")
    check(y.get("viewBox") == f"0 0 {100 if expected == 100 else 24} {100 if expected == 100 else 24}",
          f"{name}: SVG wrong viewBox")

for preserved in ("ic_notification_butterfly.xml", "splash_icon_transparent.xml"):
    check((DRAW / preserved).exists(), f"expected identity/status exception: {preserved}")

# Independent assets: active semantic destinations vs archived non-route redraws.
entry_dir = DESIGN / "assets/icons"
legacy_dir = DESIGN / "assets/icons-legacy-redrawn"
check(len(list(entry_dir.glob("*.svg"))) == 24, "24 active semantic SVGs required")
check(len(list(legacy_dir.glob("*.svg"))) == 12, "12 redrawn legacy SVGs required")
route_manifest = json.loads((DESIGN / "current-ui.json").read_text())
check("PROJECT_MANAGEMENT" in route_manifest["retiredUI"], "retired project route was restored")
check("RUN_CENTER_STANDALONE" in route_manifest["retiredUI"], "retired run center route was restored")

if issues:
    print("V6.3 icon system contract failed:")
    for issue in issues:
        print("  -", issue)
    sys.exit(1)
print("V6.3 icon contract passed: 24 active, 61 utility, 12 legacy, 20 file, 11 standalone = 128.")
, block, re.M)
    check(strokes == entry_catalog["paths"][name], f"catalog/Kotlin mismatch: {name}")
    folder = "icons" if name in current else "icons-supplemental"
    pt = check_svg_glyph(DESIGN / "assets" / folder / f"{name}.svg")
    if pt is not None:
        check(strokes == pt, f"SVG/Kotlin mismatch: {name}")
check("strokeLineWidth = 1.75f" in entry_kotlin, "entry stroke must remain 1.75")

# File styles: 10 semantic families x light/dark, one canonical JSON catalog.
file_spec = json.loads((DESIGN / "file-icons.json").read_text())
kinds = set(file_spec["glyphs"])
check(len(kinds) == 10, "file-icons.json must have 10 semantic file glyphs")
check(set(file_spec["labels"]) == kinds, "missing file icon labels")
check(set(file_spec["themes"]) == {"light", "dark"}, "file icons require light/dark modes")
for kind in kinds:
    for theme in ("light", "dark"):
        name = f"ic_ui_file{kind}_{theme}"
        x = xml(DRAW / f"{name}.xml")
        y = xml(DESIGN / f"assets/file-icons/{name}.svg")
        if x is None or y is None:
            continue
        check(x.get(ANDROID + "viewportWidth") == "40" and
              x.get(ANDROID + "viewportHeight") == "40", f"{name}: expected 40dp viewport")
        check(x.get(ANDROID + "width") == "40dp" and x.get(ANDROID + "height") == "40dp",
              f"{name}: legacy 52x68 resource leaked back")
        android_paths = [p.get(ANDROID + "pathData") for p in x.findall("path")]
        check(android_paths == paths(y), f"{name}: SVG and XML shapes differ")
        check(len(android_paths) == 3, f"{name}: expected material, fold, category paths")
        for path in x.findall("path"):
            check(path.get(ANDROID + "strokeWidth") == "1.75", f"{name}: nonstandard line width")
        check(y.get("viewBox") == "0 0 40 40", f"{name}: wrong SVG viewport")

# Android standalone glyphs: utility, stop/send contrast and large illustrations.
misc_dir = DESIGN / "assets/icons-misc"
misc = json.loads((misc_dir / "manifest.json").read_text())
check(misc["total"] == 11 and len(set(misc["items"])) == 11, "misc icon count mismatch")
for name in misc["items"]:
    x = xml(DRAW / f"{name}.xml")
    y = xml(misc_dir / f"{name}.svg")
    if x is None or y is None:
        continue
    check([p.get(ANDROID + "pathData") for p in x.findall("path")] == paths(y),
          f"{name}: Android drawable differs from SVG redraw")
    expected = 100 if name in misc["illustrations"] else 32 if name in misc["whiteOnAccent"] else 24
    check(x.get(ANDROID + "width") == f"{expected}dp", f"{name}: wrong nominal display size")
    check(x.get(ANDROID + "viewportWidth") == str(100 if expected == 100 else 24),
          f"{name}: wrong SVG grid")
    check(y.get("viewBox") == f"0 0 {100 if expected == 100 else 24} {100 if expected == 100 else 24}",
          f"{name}: SVG wrong viewBox")

for preserved in ("ic_notification_butterfly.xml", "splash_icon_transparent.xml"):
    check((DRAW / preserved).exists(), f"expected identity/status exception: {preserved}")

# Independent assets: active semantic destinations vs archived non-route redraws.
entry_dir = DESIGN / "assets/icons"
legacy_dir = DESIGN / "assets/icons-legacy-redrawn"
check(len(list(entry_dir.glob("*.svg"))) == 24, "24 active semantic SVGs required")
check(len(list(legacy_dir.glob("*.svg"))) == 12, "12 redrawn legacy SVGs required")
route_manifest = json.loads((DESIGN / "current-ui.json").read_text())
check("PROJECT_MANAGEMENT" in route_manifest["retiredUI"], "retired project route was restored")
check("RUN_CENTER_STANDALONE" in route_manifest["retiredUI"], "retired run center route was restored")

if issues:
    print("V6.3 icon system contract failed:")
    for issue in issues:
        print("  -", issue)
    sys.exit(1)
print("V6.3 icon contract passed: 24 active, 61 utility, 12 legacy, 20 file, 11 standalone = 128.")
