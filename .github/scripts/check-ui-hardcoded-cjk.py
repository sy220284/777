#!/usr/bin/env python3
"""Ratchet hard-coded CJK string literals in Compose/UI Kotlin sources.

The current codebase still has legacy user-facing strings to migrate. This guard makes that debt
monotonic: a change may reduce the count, but CI rejects any increase. Once the count reaches zero,
the threshold should be removed and any match should fail.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
MAX_HARDCODED_CJK_LITERALS = 300
PATTERN = re.compile(r'"(?:\\.|[^"\\])*[\u3400-\u9fff](?:\\.|[^"\\])*"')

counts: list[tuple[int, Path]] = []
total = 0
for path in sorted(UI_ROOT.rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    count = len(PATTERN.findall(text))
    if count:
        counts.append((count, path.relative_to(ROOT)))
        total += count

print(f"UI hard-coded CJK string literals: {total} (budget: {MAX_HARDCODED_CJK_LITERALS})")
for count, path in sorted(counts, reverse=True)[:12]:
    print(f"  {count:4d}  {path}")

if total > MAX_HARDCODED_CJK_LITERALS:
    print(
        "Hard-coded UI text increased. Move new user-visible text into strings.xml and "
        "values-zh-rCN/strings.xml, or migrate existing literals so the total does not grow.",
        file=sys.stderr,
    )
    sys.exit(1)
