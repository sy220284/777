#!/usr/bin/env python3
"""Guard high-risk Kotlin patterns that have caused real regressions in this app.

This is deliberately small and deterministic. Android lint covers platform/resource issues; this
check covers coroutine/process patterns that lint does not reliably flag in this repository.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [
    path
    for path in ROOT.rglob("*.kt")
    if "/src/main/" in path.as_posix()
]

RUN_BLOCKING_ALLOW = {
    "app/src/main/java/com/labteto/dshmobile/ui/screens/main/WorkspacePanels.kt",
    "mock-harness/src/main/kotlin/com/labteto/dshmobile/mockharness/Main.kt",
}
THREAD_SLEEP_ALLOW = {
    "app/src/main/java/com/labteto/dshmobile/update/UpdateInstaller.kt",
    "harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/ManagedProcess.kt",
}

violations: list[str] = []

for path in SOURCES:
    rel = path.relative_to(ROOT).as_posix()
    text = path.read_text(encoding="utf-8")

    if re.search(r"\bGlobalScope\b", text):
        violations.append(f"{rel}: GlobalScope is forbidden; use an owned scope")

    if re.search(r"\brunBlocking\s*(?:<[^>]+>)?\s*\(", text) and rel not in RUN_BLOCKING_ALLOW:
        violations.append(f"{rel}: production runBlocking is not allowlisted")

    if "Thread.sleep(" in text and rel not in THREAD_SLEEP_ALLOW:
        violations.append(f"{rel}: Thread.sleep is not allowlisted")

shizuku = ROOT / "harness-device-android/src/main/java/com/labteto/dshmobile/device/shizuku/ShizukuBridge.kt"
if shizuku.is_file():
    text = shizuku.read_text(encoding="utf-8")
    if re.search(r'ProcessBuilder\s*\(\s*["\']/system/bin/sh["\']\s*,\s*["\']-c["\']', text):
        violations.append(
            f"{shizuku.relative_to(ROOT)}: privileged commands must not pass through a shell"
        )

if violations:
    print("Kotlin risk-pattern guard failed:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    sys.exit(1)

print(f"Kotlin risk-pattern guard passed ({len(SOURCES)} production Kotlin files checked)")
