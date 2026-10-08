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

violations = []
for path in UI.rglob("*.kt"):
    relative = path.relative_to(UI).as_posix()
    text = path.read_text(encoding="utf-8")
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
