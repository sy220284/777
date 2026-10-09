#!/usr/bin/env python3
"""Keep high-level Compose screens on shared overlay/card primitives.

This is intentionally a narrow ratchet: full-screen Dialog surfaces remain allowed, while the
small modal/menu/card primitives that already have Design System wrappers may not be reintroduced
directly in feature screens.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"

RULES = (
    (re.compile(r"\bAlertDialog\s*\("), set(), "use DsDialog"),
    (
        re.compile(r"\bDialog\s*\("),
        {"components/Overlays.kt"},
        "use DsDialog/DsFullScreenDialog",
    ),
    (
        re.compile(r"\bModalBottomSheet\s*\("),
        {"components/DsBottomSheet.kt"},
        "use DsBottomSheet",
    ),
    (
        re.compile(r"\bDropdownMenu\s*\("),
        {"components/Overlays.kt"},
        "use DsPopupMenu/DsMenu",
    ),
    (
        re.compile(r"\bPopup\s*\("),
        {"components/Overlays.kt"},
        "use DsPopupMenu/DsContextActionMenu or another shared Ds* popup primitive",
    ),
    (
        re.compile(r"\bOutlinedCard\s*\("),
        set(),
        "use a shared Ds* card such as DsDeliverableCard",
    ),
)

def mask_non_code(source: str) -> str:
    """Mask comments, Kotlin strings and character literals, retaining line positions."""
    output = list(source)
    i, n, depth = 0, len(source), 0
    while i < n:
        if depth:
            if source.startswith("/*", i):
                output[i:i+2] = [" ", " "]
                depth += 1
                i += 2
            elif source.startswith("*/", i):
                output[i:i+2] = [" ", " "]
                depth -= 1
                i += 2
            else:
                if source[i] != "\n":
                    output[i] = " "
                i += 1
            continue
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end < 0:
                end = n
            output[i:end] = " " * (end - i)
            i = end
            continue
        if source.startswith("/*", i):
            output[i:i+2] = [" ", " "]
            depth = 1
            i += 2
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            for j in range(i, end):
                if source[j] != "\n":
                    output[j] = " "
            i = end
            continue
        if source[i] in ('"', "'"):
            quote = source[i]
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == quote:
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                if source[k] != "\n":
                    output[k] = " "
            i = j
            continue
        i += 1
    return "".join(output)

def self_test() -> None:
    sample = '// Dialog(ignored)\nval text = "Popup(fake)"\n/* AlertDialog(no) */\nDialog(real)'
    result = mask_non_code(sample)
    assert "Dialog(real)" in result
    assert "Dialog(ignored)" not in result
    assert "Popup(fake)" not in result
    assert "AlertDialog(no)" not in result

self_test()

violations = []
for path in UI.rglob("*.kt"):
    relative = path.relative_to(UI).as_posix()
    text = mask_non_code(path.read_text(encoding="utf-8"))
    for pattern, allowed, hint in RULES:
        if relative in allowed:
            continue
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            violations.append(f"{relative}:{line}: {match.group(0).strip()} -> {hint}")

if violations:
    print("UI Design System boundary violations:")
    print("\n".join(f"  - {item}" for item in violations))
    sys.exit(1)

print("UI Design System boundary guard passed")
