#!/usr/bin/env python3
"""Classify changed paths into Architecture 3.0-aware CI lanes and release impact."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class CiPlan:
    scope: str
    run_static: bool
    run_architecture: bool
    run_unit: bool
    run_build: bool
    run_device: bool
    run_android: bool
    run_fixture: bool
    affects_release: bool
    run_relay: bool = False

    def as_outputs(self) -> dict[str, str]:
        return {
            "scope": self.scope,
            "run_static": str(self.run_static).lower(),
            "run_architecture": str(self.run_architecture).lower(),
            "run_unit": str(self.run_unit).lower(),
            "run_build": str(self.run_build).lower(),
            "run_device": str(self.run_device).lower(),
            "run_android16": str(self.run_android).lower(),
            "run_android17": str(self.run_android).lower(),
            "run_fixture": str(self.run_fixture).lower(),
            "run_relay": str(self.run_relay).lower(),
            "affects_release": str(self.affects_release).lower(),
        }


DOC_ROOT_FILES = {"screen.png", "LICENSE"}
REPO_METADATA_FILES = {
    ".editorconfig",
    ".gitignore",
    ".gitattributes",
    ".github/PULL_REQUEST_TEMPLATE.md",
}
FULL_VALIDATION_SCRIPTS = {
    ".github/scripts/run-android-instrumentation.sh",
    ".github/scripts/verify-android16-apk.sh",
    ".github/scripts/smoke-test-android-startup.sh",
    ".github/scripts/check-apk-runtime-layout.py",
}
RELAY_CONFORMANCE_PATHS = {
    ".github/workflows/ci.yml",
    ".github/scripts/classify-ci-scope.py",
    "app/src/test/java/com/labteto/dshmobile/connection/RelayConformanceTest.kt",
}

FIXTURE_PROVENANCE_PATHS = {
    "upstream/deepseek-harness.lock.json",
    "tools/reference-validation/official-runner.ts",
    "tools/reference-validation/official-advanced-runner.ts",
    "tools/reference-validation/refresh-official-fixtures.sh",
    "tools/reference-validation/package.json",
    "tools/reference-validation/package-lock.json",
}
FIXTURE_PROVENANCE_PREFIXES = (
    "reference-validation/src/test/resources/official-semantic/",
)

CI_CONTROL_FILES = {
    ".github/workflows/ci.yml",
    ".github/scripts/classify-ci-scope.py",
    ".github/scripts/check-ci-repository-integrity.py",
    ".github/scripts/check-android-security-boundaries.py",
}
ARCHITECTURE_AUTHORITY_FILES = {
    "AGENTS.md",
    "docs/ARCHITECTURE.md",
    "docs/SYSTEM-AUDIT-GUIDE.zh-CN.md",
    "docs/VALIDATION.md",
}
ARCHITECTURE_3_CONTROL_FILES = ARCHITECTURE_AUTHORITY_FILES | {
    ".github/workflows/ci.yml",
    ".github/scripts/classify-ci-scope.py",
    ".github/scripts/check-ci-repository-integrity.py",
    ".github/scripts/check-local-architecture-boundaries.py",
    ".github/scripts/check-local-performance-invariants.py",
}
ARCHITECTURE_3_PREFIXES = (
    "app/src/main/java/com/labteto/dshmobile/local/",
    "app/src/test/java/com/labteto/dshmobile/local/",
    "app/src/androidTest/java/com/labteto/dshmobile/local/",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/",
    "app/src/test/java/com/labteto/dshmobile/ui/screens/local/",
    "app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/",
    "app/src/test/java/com/labteto/dshmobile/ui/screens/settings/",
    "app/src/main/java/com/labteto/dshmobile/automation/",
    "app/src/test/java/com/labteto/dshmobile/automation/",
    "harness-core/src/",
    "harness-runtime-android/src/",
    "harness-interop/src/",
    "harness-device-android/src/",
)


def normalize(path: str) -> str:
    value = path.strip()
    return value[2:] if value.startswith("./") else value


def is_fixture_provenance_path(path: str) -> bool:
    return path in FIXTURE_PROVENANCE_PATHS or path.startswith(FIXTURE_PROVENANCE_PREFIXES)


def is_documentation(path: str) -> bool:
    return path.endswith(".md") or path.startswith("docs/") or path in DOC_ROOT_FILES


def is_repository_metadata(path: str) -> bool:
    return path in REPO_METADATA_FILES


def is_android_test(path: str) -> bool:
    return "/src/androidTest/" in f"/{path}"


def is_unit_test(path: str) -> bool:
    wrapped = f"/{path}"
    return "/src/test/" in wrapped or "/src/testFixtures/" in wrapped


def is_test_tooling(path: str) -> bool:
    return path.startswith(
        (
            "tools/reference-validation/",
            "tools/capture/",
            "reference-validation/",
            "mock-harness/",
            "upstream/",
        )
    )


def is_ci_control(path: str) -> bool:
    return path in CI_CONTROL_FILES or (
        path.startswith(".github/scripts/check-") and path.endswith(".py")
    )


def is_automation(path: str) -> bool:
    return path.startswith(".github/workflows/") or path.startswith(".github/scripts/")


def is_architecture_3_path(path: str) -> bool:
    return path in ARCHITECTURE_3_CONTROL_FILES or path.startswith(ARCHITECTURE_3_PREFIXES)


def classify(paths: Iterable[str], *, force_full: bool = False) -> CiPlan:
    automation = False
    ci_control = False
    architecture = force_full
    unit = False
    android = False
    fixture = force_full
    relay = force_full
    full = force_full
    affects_release = force_full

    for raw in paths:
        path = normalize(raw)
        if not path:
            continue

        architecture_path = is_architecture_3_path(path)
        architecture = architecture or architecture_path
        relay = relay or path in RELAY_CONFORMANCE_PATHS

        if path in ARCHITECTURE_3_CONTROL_FILES and is_documentation(path):
            continue

        if is_documentation(path) or is_repository_metadata(path):
            continue

        if path == ".github/release-version":
            full = True
            affects_release = True
            continue

        if is_fixture_provenance_path(path):
            unit = True
            fixture = True
            continue

        if path in FULL_VALIDATION_SCRIPTS:
            automation = True
            full = True
            continue

        if is_ci_control(path):
            automation = True
            ci_control = True
            continue

        if is_automation(path):
            automation = True
            continue

        if is_android_test(path):
            android = True
            continue

        if is_unit_test(path) or is_test_tooling(path):
            unit = True
            continue

        # Product/build/runtime/unknown files conservatively receive the full matrix.
        full = True
        affects_release = True

    run_static = automation or architecture or unit or android or full
    run_architecture = architecture or full
    run_unit = unit or full
    run_build = full
    run_device = android or full
    run_android = android or full
    run_relay = relay or full

    if full:
        scope = "full"
    elif architecture and ci_control:
        scope = "architecture-control"
    elif architecture and android:
        scope = "architecture-android"
    elif architecture and unit:
        scope = "architecture-unit"
    elif architecture:
        scope = "architecture-control"
    else:
        active = sum((automation, unit, android))
        if active == 0:
            scope = "docs"
        elif active > 1:
            scope = "mixed-light"
        elif android:
            scope = "android-test"
        elif unit:
            scope = "unit-test"
        else:
            scope = "automation"

    return CiPlan(
        scope=scope,
        run_static=run_static,
        run_architecture=run_architecture,
        run_unit=run_unit,
        run_build=run_build,
        run_device=run_device,
        run_android=run_android,
        run_fixture=fixture,
        affects_release=affects_release,
        run_relay=run_relay,
    )


def self_test() -> None:
    cases = [
        (
            ["README.md"],
            CiPlan("docs", False, False, False, False, False, False, False, False),
        ),
        (
            ["docs/ARCHITECTURE.md"],
            CiPlan("architecture-control", True, True, False, False, False, False, False, False),
        ),
        (
            [".github/scripts/check-ci-repository-integrity.py"],
            CiPlan("architecture-control", True, True, False, False, False, False, False, False),
        ),
        (
            [".github/workflows/release.yml"],
            CiPlan("automation", True, False, False, False, False, False, False, False),
        ),
        (
            [".github/workflows/ci.yml"],
            CiPlan("architecture-control", True, True, False, False, False, False, False, False, True),
        ),
        (
            [".github/scripts/check-local-architecture-boundaries.py"],
            CiPlan("architecture-control", True, True, False, False, False, False, False, False),
        ),
        (
            [".github/release-version"],
            CiPlan("full", True, True, True, True, True, True, False, True, True),
        ),
        (
            ["app/src/test/java/com/labteto/dshmobile/local/ExampleTest.kt"],
            CiPlan("architecture-unit", True, True, True, False, False, False, False, False),
        ),
        (
            ["app/src/androidTest/java/com/labteto/dshmobile/local/ExampleTest.kt"],
            CiPlan("architecture-android", True, True, False, False, True, True, False, False),
        ),
        (
            ["harness-core/src/test/kotlin/example/Test.kt"],
            CiPlan("architecture-unit", True, True, True, False, False, False, False, False),
        ),
        (
            ["upstream/deepseek-harness.lock.json"],
            CiPlan("unit-test", True, False, True, False, False, False, True, False),
        ),
        (
            ["app/src/main/java/com/labteto/dshmobile/local/chat/ChatFeature.kt"],
            CiPlan("full", True, True, True, True, True, True, False, True, True),
        ),
        (
            ["app/src/main/java/example/App.kt"],
            CiPlan("full", True, True, True, True, True, True, False, True, True),
        ),
        (
            ["build.gradle.kts"],
            CiPlan("full", True, True, True, True, True, True, False, True, True),
        ),
        (
            ["README.md", "app/src/main/java/example/App.kt"],
            CiPlan("full", True, True, True, True, True, True, False, True, True),
        ),
        (
            [".github/workflows/release.yml", "app/src/test/java/example/Test.kt"],
            CiPlan("mixed-light", True, False, True, False, False, False, False, False),
        ),
        (
            [".github/scripts/verify-android16-apk.sh"],
            CiPlan("full", True, True, True, True, True, True, False, False, True),
        ),
    ]
    for paths, expected in cases:
        actual = classify(paths)
        if actual != expected:
            raise AssertionError(f"{paths}: expected {expected}, got {actual}")

    # Control-plane coverage is an invariant, not a hand-maintained list of examples.
    for path in sorted(ARCHITECTURE_AUTHORITY_FILES):
        plan = classify([path])
        if not (plan.run_static and plan.run_architecture):
            raise AssertionError(f"{path}: architecture authority must run static + architecture gates: {plan}")

    for path in sorted(CI_CONTROL_FILES):
        plan = classify([path])
        if not plan.run_static:
            raise AssertionError(f"{path}: CI control file must run static gates: {plan}")

    for path in sorted(ARCHITECTURE_3_CONTROL_FILES):
        plan = classify([path])
        if not (plan.run_static and plan.run_architecture):
            raise AssertionError(f"{path}: architecture control file must run static + architecture gates: {plan}")

    relay_plan = classify(["app/src/test/java/com/labteto/dshmobile/connection/RelayConformanceTest.kt"])
    if not (relay_plan.run_static and relay_plan.run_unit and relay_plan.run_relay):
        raise AssertionError(f"real relay conformance test must select unit + relay lanes: {relay_plan}")

    synthetic_guard = classify([".github/scripts/check-new-guard.py"])
    if not synthetic_guard.run_static:
        raise AssertionError("new check-*.py guards must automatically enter static-gates")

    for path in sorted(FULL_VALIDATION_SCRIPTS):
        plan = classify([path])
        if not (
            plan.run_static
            and plan.run_architecture
            and plan.run_unit
            and plan.run_build
            and plan.run_device
            and plan.run_android
        ):
            raise AssertionError(f"{path}: full validation control must run the full product matrix: {plan}")

    for path in sorted(FIXTURE_PROVENANCE_PATHS):
        plan = classify([path])
        if not (plan.run_static and plan.run_unit and plan.run_fixture):
            raise AssertionError(f"{path}: fixture provenance change lost required verification: {plan}")

    advanced_fixture = "reference-validation/src/test/resources/official-semantic/advanced.json"
    plan = classify([advanced_fixture])
    if not (plan.run_static and plan.run_unit and plan.run_fixture):
        raise AssertionError(
            f"{advanced_fixture}: fixture provenance change lost required verification: {plan}"
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paths-file", type=Path)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--force-full", action="store_true")
    parser.add_argument("--release-safe", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("CI scope classifier self-test passed")
        return

    if not args.paths_file:
        parser.error("--paths-file is required unless --self-test is used")

    paths = args.paths_file.read_text(encoding="utf-8").splitlines()
    plan = classify(paths, force_full=args.force_full)

    if args.release_safe:
        if plan.affects_release:
            print(f"Release-impacting scope: {plan.scope}")
            raise SystemExit(1)
        print(f"Release-equivalent scope: {plan.scope}")
        return

    outputs = plan.as_outputs()
    print(f"CI scope: {plan.scope}")
    for key, value in outputs.items():
        print(f"{key}={value}")

    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as handle:
            for key, value in outputs.items():
                handle.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
