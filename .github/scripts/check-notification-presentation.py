#!/usr/bin/env python3
"""Validate active notification resources and launcher structure, independent of filenames."""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
KOTLIN = APP / "java"
ANDROID = "{http://schemas.android.com/apk/res/android}"
violations: list[str] = []

def kotlin_code(text: str) -> str:
    # Block and line comments do not create actual Notification builders.
    text = re.sub(r"/\*[\s\S]*?\*/", "", text)
    return re.sub(r"//[^\n]*", "", text)

def resource(name: str, *, kind: str = "drawable") -> Path | None:
    for folder in sorted((APP / "res").glob(kind + "*")):
        candidate = folder / (name + ".xml")
        if candidate.is_file():
            return candidate
    return None

def notification_configuration(text: str) -> tuple[str | None, bool]:
    code = kotlin_code(text)
    if not re.search(r"\bNotificationCompat\s*\.\s*Builder\s*\(", code):
        return None, False
    has_large_icon = bool(re.search(r"\.\s*setLargeIcon\s*\(", code))
    found = re.findall(r"\.\s*setSmallIcon\s*\(\s*R\.drawable\.([A-Za-z_]\w*)", code)
    return (found[0] if len(set(found)) == 1 else None), has_large_icon

def self_test() -> None:
    assert notification_configuration(
        "NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.icon)"
    ) == ("icon", False)
    assert notification_configuration(
        "// .setLargeIcon(old)\nNotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.icon)"
    ) == ("icon", False)
    assert notification_configuration(
        "NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.icon).setLargeIcon(bitmap)"
    ) == ("icon", True)
    assert notification_configuration(
        "NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.one).setSmallIcon(R.drawable.two)"
    )[0] is None

if "--self-test" in sys.argv:
    self_test()
    print("Notification resource-structure self-test passed")
    sys.exit(0)

small_icons: set[str] = set()
builder_files: list[Path] = []
for path in KOTLIN.rglob("*.kt"):
    code = kotlin_code(path.read_text(encoding="utf-8"))
    if not re.search(r"\bNotificationCompat\s*\.\s*Builder\s*\(", code):
        continue
    builder_files.append(path)
    icon, has_large = notification_configuration(code)
    if has_large:
        violations.append(f"{path.relative_to(ROOT)}: notification template must not setLargeIcon")
    if not icon:
        violations.append(f"{path.relative_to(ROOT)}: notification must set one static drawable small icon")
    else:
        small_icons.add(icon)
if len(small_icons) != 1:
    violations.append(f"all notification builders must share one monochrome small icon, got {sorted(small_icons)}")
for icon in small_icons:
    path = resource(icon)
    if path is None:
        violations.append(f"notification small icon drawable is missing: {icon}")
    else:
        xml = ET.parse(path).getroot()
        if xml.tag != "vector" or not xml.findall("path"):
            violations.append(f"notification small icon must be a vector silhouette: {path.relative_to(ROOT)}")

manifest = ET.parse(APP / "AndroidManifest.xml").getroot()
application = manifest.find("application")
if application is None:
    violations.append("AndroidManifest.xml: no application element")
else:
    for attr in ("icon", "roundIcon"):
        value = application.get(ANDROID + attr, "")
        match = re.fullmatch(r"@mipmap/([A-Za-z_]\w*)", value)
        if match is None:
            violations.append(f"AndroidManifest.xml: missing valid application {attr}")
            continue
        path = resource(match.group(1), kind="mipmap")
        if path is None or ET.parse(path).getroot().tag != "adaptive-icon":
            violations.append(f"AndroidManifest.xml: application {attr} must resolve to an adaptive icon")

if not builder_files:
    violations.append("no NotificationCompat.Builder: notification validation has no owner")
if violations:
    print("Notification resource-structure guard failed:")
    for error in violations:
        print(" - " + error)
    sys.exit(1)
print(f"Notification resource-structure guard passed ({len(builder_files)} builders)")
