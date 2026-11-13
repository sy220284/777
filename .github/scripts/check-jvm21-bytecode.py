#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_MAJOR = 65  # Java 21
MODULE_OUTPUTS = (
    "core/build/classes/kotlin/main",
    "harness-core/build/classes/kotlin/main",
    "harness-runtime-android/build/classes/kotlin/main",
    "harness-interop/build/classes/kotlin/main",
    "reference-validation/build/classes/kotlin/main",
)


def fail(message: str) -> None:
    print(f"[jvm21-bytecode] {message}", file=sys.stderr)
    raise SystemExit(1)


checked = 0
for relative in MODULE_OUTPUTS:
    root = ROOT / relative
    class_files = sorted(root.rglob("*.class")) if root.is_dir() else []
    if not class_files:
        fail(f"没有找到预期编译产物：{relative}")

    for path in class_files:
        header = path.read_bytes()[:8]
        if len(header) < 8 or header[:4] != b"\xca\xfe\xba\xbe":
            fail(f"非法 class 文件：{path.relative_to(ROOT)}")
        major = struct.unpack(">H", header[6:8])[0]
        if major != EXPECTED_MAJOR:
            fail(
                f"{path.relative_to(ROOT)} class major={major}，"
                f"预期 Java 21 major={EXPECTED_MAJOR}"
            )
        checked += 1

print(f"[jvm21-bytecode] OK: {checked} 个 class 文件均为 major {EXPECTED_MAJOR}")
