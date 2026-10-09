#!/usr/bin/env python3
"""Guard test-suite determinism and evidence quality.

The goal is to keep tests explicit and repeatable without forbidding integration tests that
intentionally exercise real threads, processes, sockets, or platform state.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

TEST_ROOTS = (
    ROOT / "app" / "src" / "test",
    ROOT / "app" / "src" / "androidTest",
    ROOT / "core" / "src" / "test",
    ROOT / "harness-core" / "src" / "test",
    ROOT / "harness-device-android" / "src" / "test",
    ROOT / "harness-interop" / "src" / "test",
    ROOT / "harness-runtime-android" / "src" / "test",
    ROOT / "reference-validation" / "src" / "test",
)

ASSUMPTION_ALLOW = {
    "app/src/test/java/com/labteto/dshmobile/local/LocalSessionEventLogTest.kt",
    "harness-interop/src/test/kotlin/com/labteto/dshmobile/interop/lsp/LspPluginTest.kt",
}

THREAD_SLEEP_ALLOW = {
    "app/src/androidTest/java/com/labteto/dshmobile/local/LocalWebCancellationAndroidTest.kt",
    "app/src/test/java/com/labteto/dshmobile/local/LocalAttachmentImporterTest.kt",
    "app/src/test/java/com/labteto/dshmobile/update/UpdatePayloadTransferTest.kt",
}

violations: list[str] = []
test_files: list[Path] = []
for root in TEST_ROOTS:
    if not root.exists():
        continue
    test_files.extend(path for path in root.rglob("*") if path.suffix in {".kt", ".java"})

for path in sorted(test_files):
    rel = path.relative_to(ROOT).as_posix()
    text = path.read_text(encoding="utf-8")

    if re.search(r"@(Ignore|Disabled)\b", text):
        violations.append(f"{rel}: disabled tests are forbidden; model the condition explicitly")

    if re.search(r"assertTrue\s*\(\s*true\s*\)", text) or re.search(
        r"assertFalse\s*\(\s*false\s*\)", text
    ):
        violations.append(f"{rel}: trivial assertion does not prove behavior")

    uses_assumption = bool(
        re.search(r"\bAssume\.", text)
        or re.search(r"\bassume(?:True|False|NotNull|NoException|That)?\s*\(", text)
    )
    if uses_assumption and rel not in ASSUMPTION_ALLOW:
        violations.append(
            f"{rel}: environment-dependent skip must be reviewed and added to ASSUMPTION_ALLOW"
        )

    if "Thread.sleep(" in text and rel not in THREAD_SLEEP_ALLOW:
        violations.append(
            f"{rel}: real blocking sleep is not allowlisted; prefer deterministic synchronization"
        )


if violations:
    print("Test quality guard failed:")
    for violation in violations:
        print(f" - {violation}")
    raise SystemExit(1)

print(
    f"[test-quality] OK: {len(test_files)} test source files checked; "
    "disabled tests, trivial assertions, unreviewed assumptions, and unreviewed sleeps are blocked"
)
