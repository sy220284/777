#!/usr/bin/env python3
"""Keep high-level Compose screens on shared overlay/card primitives.

This is intentionally a narrow ratchet: full-screen Dialog surfaces remain allowed, while the
small modal/menu/card primitives that already have Design System wrappers may not be reintroduced
directly in feature screens.
"""

from pathlib import Path
import re
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"

RULES = (
    (re.compile(r"\bAlertDialog\s*\("), set(), "use SyDialog"),
    (
        re.compile(r"\bDialog\s*\("),
        {"shenyan/SyResponsiveSheet.kt", "shenyan/SyOverlays.kt"},
        "use SyResponsiveSheet/SyConfirmDialog",
    ),
    (
        re.compile(r"\bModalBottomSheet\s*\("),
        {"shenyan/SyResponsiveSheet.kt"},
        "use SyResponsiveSheet",
    ),
    (
        re.compile(r"\bDropdownMenu\s*\("),
        {"shenyan/SyOverlays.kt"},
        "use SyPopupMenu/SyMenu",
    ),
    (
        re.compile(r"\bPopup\s*\("),
        {"shenyan/SyOverlays.kt"},
        "use SyPopupMenu/SyContextActionMenu or another shared popup primitive",
    ),
    (
        re.compile(r"\bOutlinedCard\s*\("),
        set(),
        "use SyMaterialFrame/SyGlassCard",
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
                if source[j] == "\":
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


# V6 hardening: prevent deleted visual APIs and retired design references from returning.
RETIRED_DRAWING_CALL = re.compile(
    r"\bDs(?:Button|Card|Segmented|Checkbox|BottomSheet|SearchField|ToastHost|TopBar|ProgressBar)\s*\("
)
baseline_path = ROOT / "docs/design/v6/legacy-theme-consumers.json"
baseline = set(json.loads(baseline_path.read_text(encoding="utf-8"))["remainingProductionFiles"])
actual = set()
for path in UI.rglob("*.kt"):
    relative = path.relative_to(ROOT).as_posix()
    source = mask_non_code(path.read_text(encoding="utf-8"))
    for match in RETIRED_DRAWING_CALL.finditer(source):
        violations.append(f"{relative}: retired Ds drawing component {match.group(0)}")
    if re.search(r"\bDsTheme\.colors\b", source):
        actual.add(relative)
for new in sorted(actual - baseline):
    violations.append(f"{new}: new legacy DsTheme.colors consumer; use canonical LocalSyColors")
for stale in sorted(baseline - actual):
    violations.append(f"{stale}: remove migrated file from legacy-theme-consumers.json")

for relative in ["docs/UI-UX.zh-CN.md","docs/design/mobile-ui.md","docs/design/v6/README.zh-CN.md","docs/design/v6/STATUS.zh-CN.md","docs/README.md"]:
    file = ROOT / relative
    source = file.read_text(encoding="utf-8")
    for link in re.findall(r"\]\(([^)]+)\)", source):
        url = link.split("#")[0].split("?")[0].strip()
        if not url or re.match(r"^(?:https?:|mailto:|data:)", url):
            continue
        target = (file.parent / url).resolve()
        if not target.is_relative_to(ROOT) or not target.is_file():
            violations.append(f"{relative}: broken local design link -> {url}")

for relative in ("docs/design/v5-p0p1", "docs/design/clear-realm-theme.md",
                 "docs/design/v6/verification.json", "docs/design/v6/PR664-VISUAL-ACCEPTANCE.zh-CN.md",
                 "app/src/main/java/com/shenyan/designsystem/layout/SyAdaptiveShell.kt"):
    if (ROOT / relative).exists():
        violations.append(f"{relative}: retired V5 visual asset or obsolete component returned")

if violations:
    print("UI Design System boundary violations:")
    print("\n".join(f"  - {item}" for item in violations))
    sys.exit(1)

print("UI Design System boundary guard passed")
