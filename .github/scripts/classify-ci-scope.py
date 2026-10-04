#!/usr/bin/env python3
"""Classify changed paths into the minimum safe CI lanes."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class CiPlan:
    scope: str
    run_automation: bool
    run_preflight: bool
    run_build: bool
    run_android: bool

    def as_outputs(self) -> dict[str, str]:
        return {
            "scope": self.scope,
            "run_automation": str(self.run_automation).lower(),
            "run_preflight": str(self.run_preflight).lower(),
            "run_build": str(self.run_build).lower(),
            "run_android16": str(self.run_android).lower(),
            "run_android17": str(self.run_android).lower(),
        }


DOC_ROOT_FILES = {"screen.png", "LICENSE"}
REPO_METADATA_FILES = {
    ".editorconfig",
    ".gitignore",
    ".gitattributes",
    ".github/PULL_REQUEST_TEMPLATE.md",
}
FULL_VALIDATION_SCRIPTS = {
    ".github/scripts/verify-android16-apk.sh",
    ".github/scripts/smoke-test-android-startup.sh",
    ".github/scripts/check-apk-runtime-layout.py",
}


def normalize(path: str) -> str:
    value = path.strip()
    return value[2:] if value.startswith("./") else value


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
    return (
        path == ".github/workflows/ci.yml"
        or path == ".github/scripts/classify-ci-scope.py"
        or (path.startswith(".github/scripts/check-") and path.endswith(".py"))
    )


def is_automation(path: str) -> bool:
    return (
        path.startswith(".github/workflows/")
        or path.startswith(".github/scripts/")
        or path == ".github/release-version"
    )


def classify(paths: Iterable[str], *, force_full: bool = False) -> CiPlan:
    automation = False
    ci_control = False
    unit = False
    android = False
    full = force_full

    for raw in paths:
        path = normalize(raw)
        if not path:
            continue

        if is_documentation(path) or is_repository_metadata(path):
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

        # Any product/build/runtime/unknown path falls back to the full matrix.
        full = True

    run_preflight = full or unit
    run_build = full
    run_android = full or android

    if full:
        scope = "full"
    elif ci_control and not (unit or android):
        scope = "ci-control"
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
        run_automation=automation,
        run_preflight=run_preflight,
        run_build=run_build,
        run_android=run_android,
    )


def self_test() -> None:
    cases = [
        (["README.md"], CiPlan("docs", False, False, False, False)),
        (["docs/VALIDATION.md", "screen.png"], CiPlan("docs", False, False, False, False)),
        ([".github/workflows/release.yml"], CiPlan("automation", True, False, False, False)),
        ([".github/workflows/ci.yml"], CiPlan("ci-control", True, False, False, False)),
        (
            [".github/scripts/check-local-performance-invariants.py"],
            CiPlan("ci-control", True, False, False, False),
        ),
        (["app/src/test/java/example/Test.kt"], CiPlan("unit-test", False, True, False, False)),
        (
            ["app/src/androidTest/java/example/Test.kt"],
            CiPlan("android-test", False, False, False, True),
        ),
        (["app/src/main/java/example/App.kt"], CiPlan("full", False, True, True, True)),
        (["build.gradle.kts"], CiPlan("full", False, True, True, True)),
        (
            ["README.md", "app/src/main/java/example/App.kt"],
            CiPlan("full", False, True, True, True),
        ),
        (
            [".github/workflows/release.yml", "app/src/test/java/example/Test.kt"],
            CiPlan("mixed-light", True, True, False, False),
        ),
        (
            [".github/scripts/verify-android16-apk.sh"],
            CiPlan("full", True, True, True, True),
        ),
    ]
    for paths, expected in cases:
        actual = classify(paths)
        if actual != expected:
            raise AssertionError(f"{paths}: expected {expected}, got {actual}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paths-file", type=Path)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--force-full", action="store_true")
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
