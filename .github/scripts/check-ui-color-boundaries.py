#!/usr/bin/env python3
"""Reject new UI color literals outside the palette and retired precolored composer icons."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
DRAWABLE = ROOT / "app/src/main/res/drawable"
# Theme.kt / Color.kt / AccentTheme.kt intentionally own palette primitives.
FORBIDDEN = re.compile(r"\bColor\s*\(\s*0x[0-9A-Fa-f]{6,8}\b|\bColor\.(?:Red|Blue|Green|Yellow|Magenta|Cyan)\b")
RETIRED = (
    "ic_ui_button_send_light.xml", "ic_ui_button_send_dark.xml",
    "ic_ui_button_send_disabled_light.xml", "ic_ui_button_send_disabled_dark.xml",
    "ic_ui_button_stop_light.xml", "ic_ui_button_stop_dark.xml",
)

def violations_in_text(source: str, path: str) -> list[str]:
    found = []
    for lineno, line in enumerate(source.splitlines(), 1):
        code = line.split("//", 1)[0]
        if FORBIDDEN.search(code):
            found.append(f"{path}:{lineno}: UI literal color; use canonical LocalSyColors.current")
    return found

def self_test() -> None:
    assert len(violations_in_text("Text(color = Color(0xFF3366CC))", "test.kt")) == 1
    assert len(violations_in_text("Text(color = Color.Red)", "test.kt")) == 1
    assert not violations_in_text("Text(color = DsTheme.colors.error)", "test.kt")
    assert not violations_in_text("// Color(0xFF3366CC)", "test.kt")

if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
        print("UI color boundary guard self-test passed")
        sys.exit(0)
    violations = []
    for directory in (UI / "components", UI / "screens"):
        for file in directory.rglob("*.kt"):
            violations.extend(violations_in_text(file.read_text(encoding="utf-8"), str(file.relative_to(ROOT))))
    # A fixed shade baked into a full-circle send/stop vector bypasses dynamic accent.
    for name in RETIRED:
        if (DRAWABLE / name).exists():
            violations.append(f"app/src/main/res/drawable/{name}: retired non-theme-aware composer image")
    if violations:
        print("UI color boundary violations:")
        print("\n".join("  - " + item for item in violations))
        sys.exit(1)
    print("UI color boundary guard passed")
