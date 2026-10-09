#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_JDK_MIN = 27
EXPECTED_JVM_TARGET = 21
EXPECTED_NODE_MIN = 24
EXPECTED_ANDROID_BUILD_TOOLS = "37.0.0"


def fail(message: str) -> None:
    print(f"[build-baseline] {message}", file=sys.stderr)
    raise SystemExit(1)


def tracked_java_files() -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "--", "*.java"],
        cwd=ROOT,
        text=True,
    )
    return [
        line.strip()
        for line in output.splitlines()
        if line.strip() and not line.startswith("upstream/")
    ]


java_files = tracked_java_files()
if java_files:
    fail("项目源码必须保持 Kotlin 单一语言，发现 Java 文件：" + ", ".join(java_files))

versions_path = ROOT / "tools/dev/toolchain-versions.env"
versions = versions_path.read_text(encoding="utf-8")
for key, value in (
    ("BUILD_JDK_MIN_MAJOR", EXPECTED_JDK_MIN),
    ("JVM_TARGET", EXPECTED_JVM_TARGET),
    ("NODE_MIN_MAJOR", EXPECTED_NODE_MIN),
):
    if not re.search(rf"^{key}={value}$", versions, re.MULTILINE):
        fail(f"{key} 必须为 {value}")

for legacy_key in ("JDK_MAJOR", "NODE_VERSION"):
    if re.search(rf"^{legacy_key}=", versions, re.MULTILINE):
        fail(f"旧版本键 {legacy_key} 不得继续存在")

configured_modules = 0
for build_file in sorted(ROOT.glob("*/build.gradle.kts")):
    text = build_file.read_text(encoding="utf-8")
    if "sourceCompatibility" not in text and "jvmTarget.set" not in text:
        continue
    configured_modules += 1
    if "sourceCompatibility" in text:
        if "JavaVersion.VERSION_21" not in text or "JavaVersion.VERSION_17" in text:
            fail(f"{build_file.relative_to(ROOT)} 的 Java target 必须统一为 21")
    if "JvmTarget.JVM_21" not in text or "JvmTarget.JVM_17" in text:
        fail(f"{build_file.relative_to(ROOT)} 的 Kotlin JVM target 必须统一为 21")

if configured_modules < 8:
    fail(f"只检查到 {configured_modules} 个 JVM/Android 模块，低于当前基线 8")

for relative in ("app/build.gradle.kts", "harness-device-android/build.gradle.kts"):
    text = (ROOT / relative).read_text(encoding="utf-8")
    expected = f'buildToolsVersion = "{EXPECTED_ANDROID_BUILD_TOOLS}"'
    if expected not in text:
        fail(f"{relative} 必须显式固定 Android Build Tools {EXPECTED_ANDROID_BUILD_TOOLS}")

hpatch = ROOT / "app/src/main/java/com/github/sisong/HPatch.kt"
if not hpatch.is_file():
    fail("缺少 Kotlin HPatch JNI 桥")
hpatch_text = hpatch.read_text(encoding="utf-8")
for token in ("object HPatch", "@JvmStatic", "external fun patch", 'System.loadLibrary("hpatchz")'):
    if token not in hpatch_text:
        fail(f"HPatch JNI ABI 声明缺少：{token}")

def workflow_toolchain_violations(source: str) -> list[str]:
    """Check versions within each setup Action step, including quoted YAML values."""
    findings: list[str] = []
    lines = source.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(\s*)-\s*uses:\s*actions/setup-(node|java)@", line)
        if not match:
            continue
        indent, kind = match.groups()
        field = "node-version" if kind == "node" else "java-version"
        step_lines: list[str] = []
        for following in lines[index + 1:]:
            if re.match(r"^" + re.escape(indent) + r"-\s+", following):
                break
            step_lines.append(following)
        version_match = re.search(
            rf"(?m)^\s*{re.escape(field)}:\s*([^\n#]+)",
            "\n".join(step_lines),
        )
        if version_match is None:
            findings.append(f"setup-{kind} missing {field}")
            continue
        value = version_match.group(1).strip().strip("'\"").strip()
        if re.fullmatch(r"\d+(?:\.\d+){0,2}", value) is None:
            findings.append(f"setup-{kind} has non-verifiable {field}={value!r}")
            continue
        major = int(value.split(".")[0])
        minimum = EXPECTED_NODE_MIN if kind == "node" else EXPECTED_JDK_MIN
        if major < minimum:
            findings.append(f"setup-{kind} uses {major}, below minimum {minimum}")
        if kind == "node" and len(value.split(".")) == 3:
            findings.append(f"setup-node pins a patch version: {value}")
    return findings


def toolchain_self_test() -> None:
    def sample(kind: str, version: str) -> str:
        field = "node-version" if kind == "node" else "java-version"
        return f"steps:\n  - uses: actions/setup-{kind}@0123456789abcdef\n    with:\n      {field}: {version}\n"
    assert workflow_toolchain_violations(sample("node", "24")) == []
    assert workflow_toolchain_violations(sample("java", "'27'")) == []
    for value in ("24.21.0", "'24.21.0'", '"24.21.0"', "23", '"23"', "unknown"):
        assert workflow_toolchain_violations(sample("node", value)), value
    for value in ("26", "'17'", '"21"'):
        assert workflow_toolchain_violations(sample("java", value)), value
    assert workflow_toolchain_violations("  - uses: actions/setup-node@0123456789abcdef\n")


toolchain_self_test()
for workflow in sorted((ROOT / ".github/workflows").glob("*.yml")):
    for violation in workflow_toolchain_violations(workflow.read_text(encoding="utf-8")):
        fail(f"{workflow.relative_to(ROOT)}: {violation}")

print(
    f"[build-baseline] OK: Kotlin-only, JDK >= {EXPECTED_JDK_MIN}, "
    f"JVM target {EXPECTED_JVM_TARGET}, Node >= {EXPECTED_NODE_MIN}"
)
