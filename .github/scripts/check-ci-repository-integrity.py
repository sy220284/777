#!/usr/bin/env python3
"""Repository-wide CI/build integrity checks that should stay fast and deterministic."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
CI = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
RELEASE = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
CLEANUP = (WORKFLOWS / "cleanup-old-releases.yml").read_text(encoding="utf-8")
CAPTURE = (WORKFLOWS / "capture-readme-ui.yml").read_text(encoding="utf-8")
SETTINGS = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
VERSIONS = (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8")
WRAPPER = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
VERIFY = (ROOT / "gradle" / "verification-metadata.xml").read_text(encoding="utf-8")

violations: list[str] = []

# External GitHub Actions must be immutable. Local actions are allowed.
uses_pattern = re.compile(r"^\s*-\s*uses:\s*([^\s#]+)", re.MULTILINE)
for workflow in sorted(WORKFLOWS.glob("*.yml")):
    source = workflow.read_text(encoding="utf-8")
    if "permissions:" not in source:
        violations.append(f"{workflow.name}: workflow must declare explicit permissions")
    for use in uses_pattern.findall(source):
        if use.startswith("./"):
            continue
        if use.startswith("docker://"):
            violations.append(f"{workflow.name}: docker action must not use an unpinned mutable image: {use}")
            continue
        if "@" not in use:
            violations.append(f"{workflow.name}: action is not pinned: {use}")
            continue
        ref = use.rsplit("@", 1)[1]
        if not re.fullmatch(r"[0-9a-fA-F]{40}", ref):
            violations.append(f"{workflow.name}: action ref must be a full 40-char commit SHA: {use}")

    lines = source.splitlines()
    for index, line in enumerate(lines):
        if re.match(r"^\s{4}runs-on:", line):
            window = "\n".join(lines[index:index + 6])
            if "timeout-minutes:" not in window:
                violations.append(f"{workflow.name}:{index + 1}: every runner job must declare timeout-minutes")

# Gradle wrapper and dependency verification are supply-chain boundaries.
if "distributionSha256Sum=" not in WRAPPER:
    violations.append("Gradle wrapper distribution must have distributionSha256Sum")
if "validateDistributionUrl=true" not in WRAPPER:
    violations.append("Gradle wrapper must validate the distribution URL")
if "<verify-metadata>true</verify-metadata>" not in VERIFY:
    violations.append("Gradle dependency verification metadata must remain enabled")
if "<sha256 value=" not in VERIFY:
    violations.append("Gradle dependency verification metadata must contain SHA-256 checksums")

# Reject dynamic dependency versions in the central version catalog.
in_versions = False
for line_no, raw in enumerate(VERSIONS.splitlines(), 1):
    line = raw.strip()
    if line.startswith("["):
        in_versions = line == "[versions]"
        continue
    if not in_versions or not line or line.startswith("#"):
        continue
    match = re.match(r"[A-Za-z0-9_.-]+\s*=\s*\"([^\"]+)\"", line)
    if not match:
        continue
    value = match.group(1)
    if "+" in value or "latest." in value.lower() or value.upper().endswith("-SNAPSHOT"):
        violations.append(f"gradle/libs.versions.toml:{line_no}: dynamic dependency version is forbidden: {value}")

# Every module carrying unit tests must have an explicit CI task.
modules = re.findall(r'include\(\s*"(:[^"]+)"\s*\)', SETTINGS)
for module_ref in modules:
    module = module_ref[1:]
    test_dir = ROOT / module / "src" / "test"
    if not test_dir.exists():
        continue
    build_file = ROOT / module / "build.gradle.kts"
    build_source = build_file.read_text(encoding="utf-8") if build_file.exists() else ""
    android_module = "libs.plugins.android.application" in build_source or "libs.plugins.android.library" in build_source
    expected = f":{module}:testDebugUnitTest" if android_module else f":{module}:test"
    if expected not in CI:
        violations.append(f"{module}: unit tests exist but CI does not run {expected}")

if (ROOT / "app" / "src" / "androidTest").exists():
    for required in (
        ":app:assembleDebugAndroidTest",
        "app-debug-androidTest.apk",
        "androidx.test.runner.AndroidJUnitRunner",
    ):
        if required not in CI:
            violations.append(f"Android instrumentation pipeline is missing: {required}")

# Emulator lanes must consume one prebuilt x86_64 artifact rather than compile twice.
if "connectedDebugAndroidTest" in CI:
    violations.append("CI must not rebuild through connectedDebugAndroidTest inside emulator lanes")
if "android-x86_64-test-apks" not in CI:
    violations.append("CI must publish/reuse one x86_64 device artifact set for Android 16/17")
if "actions/download-artifact@" not in CI:
    violations.append("Android lanes must download the shared x86_64 test artifact")
if ".gradle/runtime-cache" not in CI or "actions/cache@" not in CI:
    violations.append("CI must cache verified Runtime downloads instead of redownloading per runner")

# Architecture 3.0 CI is authoritative on both PR and main. Main must classify every
# change instead of bypassing control/test changes through workflow-level path ignores.
if "paths-ignore:" in CI:
    violations.append("main CI must classify every push; workflow-level paths-ignore is forbidden")
for required_architecture_ci in (
    "run_architecture:",
    "architecture-3-gates:",
    "check-local-architecture-boundaries.py",
    "check-local-performance-invariants.py",
    "REQUIRE_ARCHITECTURE:",
    "ARCHITECTURE_RESULT:",
):
    if required_architecture_ci not in CI:
        violations.append(
            "Architecture 3.0 CI lane is incomplete: " + required_architecture_ci
        )
if CI.count("check-local-architecture-boundaries.py") != 1:
    violations.append("Architecture 3.0 ownership guard must run exactly once in its dedicated lane")
if CI.count("check-local-performance-invariants.py") != 1:
    violations.append("Architecture 3.0 performance invariant guard must run exactly once in its dedicated lane")
if "Validate Architecture 3.0 ownership and dependencies" not in CI:
    violations.append("Architecture 3.0 ownership guard must have its own diagnosable CI step")
if "Validate Architecture 3.0 execution invariants" not in CI:
    violations.append("Architecture 3.0 execution invariant guard must have its own diagnosable CI step")
if "classify-ci-scope.py --self-test" not in CI:
    violations.append("CI scope classifier must self-test before downstream validation")

# release-version affects the app's versionName/versionCode and must never be ignored on main.
if "- '.github/release-version'" in CI or '- ".github/release-version"' in CI:
    violations.append(".github/release-version must not be ignored by main CI")

# Release freshness must reuse the same path classifier.
if "classify-ci-scope.py" not in RELEASE or "--release-safe" not in RELEASE:
    violations.append("Release freshness must reuse classify-ci-scope.py --release-safe")
if "*/30 * * * *" in RELEASE:
    violations.append("Release fallback polling must not run every 30 minutes")

# Cleanup should be event-driven with a low-frequency fallback.
if "release:" not in CLEANUP or "types: [published]" not in CLEANUP:
    violations.append("Cleanup must react to published releases")
if "*/30 * * * *" in CLEANUP:
    violations.append("Cleanup fallback must not run every 30 minutes")
if "keep = formal_releases[:3]" not in CLEANUP or "remove = formal_releases[3:]" not in CLEANUP:
    violations.append("Release retention must keep exactly the three highest formal published versions")
if "timedelta(" in CLEANUP or "cutoff =" in CLEANUP:
    violations.append("Release retention must not add an age-based retention window")

# Screenshot bot commits are docs-only; they must not dispatch a forced full CI.
if "gh workflow run ci.yml" in CAPTURE:
    violations.append("README screenshot workflow must not force workflow_dispatch full CI")
if "actions: write" in CAPTURE:
    violations.append("README screenshot workflow no longer needs actions: write")

if violations:
    print("Repository CI/build integrity violations:")
    for item in violations:
        print(f" - {item}")
    sys.exit(1)

print("Repository CI/build integrity checks passed")
