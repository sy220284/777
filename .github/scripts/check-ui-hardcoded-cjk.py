#!/usr/bin/env python3
"""Ratchet hard-coded CJK string literals in Compose/UI Kotlin sources."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
UI_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
MAX_HARDCODED_CJK_LITERALS = 90


def contains_cjk(value: str) -> bool:
    return any("\u3400" <= char <= "\u9fff" for char in value)


def kotlin_string_literals(source: str):
    """Yield Kotlin normal/raw string literal bodies while ignoring comments and char literals."""
    index = 0
    length = len(source)
    block_depth = 0

    while index < length:
        if block_depth:
            if source.startswith("/*", index):
                block_depth += 1
                index += 2
            elif source.startswith("*/", index):
                block_depth -= 1
                index += 2
            else:
                index += 1
            continue

        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            index = length if newline < 0 else newline + 1
            continue

        if source.startswith("/*", index):
            block_depth = 1
            index += 2
            continue

        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end < 0:
                return
            yield source[index + 3:end]
            index = end + 3
            continue

        char = source[index]
        if char == '"':
            cursor = index + 1
            body = []
            while cursor < length:
                current = source[cursor]
                if current == "\\" and cursor + 1 < length:
                    body.append(current)
                    body.append(source[cursor + 1])
                    cursor += 2
                    continue
                if current == '"':
                    break
                body.append(current)
                cursor += 1
            if cursor >= length:
                return
            yield "".join(body)
            index = cursor + 1
            continue

        if char == "'":
            cursor = index + 1
            while cursor < length:
                current = source[cursor]
                if current == "\\" and cursor + 1 < length:
                    cursor += 2
                    continue
                cursor += 1
                if current == "'":
                    break
            index = cursor
            continue

        index += 1


counts: list[tuple[int, Path]] = []
total = 0
for path in sorted(UI_ROOT.rglob("*.kt")):
    source = path.read_text(encoding="utf-8")
    count = sum(1 for literal in kotlin_string_literals(source) if contains_cjk(literal))
    if count:
        counts.append((count, path.relative_to(ROOT)))
        total += count

print(f"UI hard-coded CJK string literals: {total} (budget: {MAX_HARDCODED_CJK_LITERALS})")
for count, path in sorted(counts, reverse=True)[:12]:
    print(f"  {count:4d}  {path}")

if total > MAX_HARDCODED_CJK_LITERALS:
    print(
        "Hard-coded UI text increased. Move new user-visible text into the Chinese base "
        "strings.xml, or migrate existing literals so the total does not grow.",
        file=sys.stderr,
    )
    sys.exit(1)
