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
    run_android17: bool = False

    def as_outputs(self) -> dict[str, str]:
        return {
            "scope": self.scope,
            "run_static": str(self.run_static).lower(),
            "run_architecture": str(self.run_architecture).lower(),
            "run_unit": str(self.run_unit).lower(),
            "run_build": str(self.run_build).lower(),
            "run_device": str(self.run_device).lower(),
            "run_android16": str(self.run_android).lower(),
            "run_android17": str(self.run_android17).lower(),
            "run_fixture": str(self.run_fixture).lower(),
            "affects_release": str(self.affects_release).lower(),
        }


DOC_ROOT_FILES = {"LICENSE"}
REPO_METADATA_FILES = {
    ".editorconfig",
    ".gitignore",
    ".gitattributes",
    ".github/PULL_REQUEST_TEMPLATE.md",
}
FULL_VALIDATION_SCRIPTS = {
    ".github/scripts/run-android-instrumentation.sh",
    ".github/scripts/verify-android16-apk.sh",
    ".github/scripts/wait-android-device-artifacts.sh",
    ".github/scripts/smoke-test-android-startup.sh",
    ".github/scripts/check-apk-runtime-layout.py",
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
    return path.startswith("docs/") or path in DOC_ROOT_FILES or ("/" not in path and path.endswith(".md"))


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


# Device and architecture decisions are based on affected runtime contracts,
# not on the mere presence of a Kotlin source file.
PLATFORM_PREFIXES = (
    "app/src/main/java/com/labteto/dshmobile/update/",
    "app/src/main/java/com/labteto/dshmobile/automation/",
    "app/src/main/java/com/labteto/dshmobile/device/",
    "app/src/main/java/com/labteto/dshmobile/observability/",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorage",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionRuntime",
    "harness-device-android/src/main/",
    "harness-runtime-android/src/main/",
)
PLATFORM_FILES = {
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/labteto/dshmobile/MainActivity.kt",
    "app/src/main/java/com/labteto/dshmobile/DshApplication.kt",
}
HIGH_RISK_PREFIXES = (
    "harness-core/src/main/",
    "app/src/main/java/com/labteto/dshmobile/local/session/",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgent",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeState",
)
ANDROID_UI_ENTRYPOINTS = {
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeatureNavigation.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeaturePageContent.kt",
}
CORE_CI_CONTROLS = {
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    ".github/scripts/classify-ci-scope.py",
    ".github/scripts/check-ci-repository-integrity.py",
}
ANDROID_PLATFORM_FILES = {
    "app/build.gradle.kts",
    "harness-device-android/build.gradle.kts",
    "settings.gradle.kts",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/verification-metadata.xml",
}

def classify(
    paths: Iterable[str], *, force_full: bool = False,
    main_full_on_release: bool = False,
) -> CiPlan:
    automation = False
    architecture = force_full
    unit = False
    build = False
    device = False
    android16 = False
    android17 = False
    fixture = force_full
    full = force_full
    affects_release = False
    audit_index = False

    for raw in paths:
        path = normalize(raw)
        if not path:
            continue

        architecture = architecture or is_architecture_3_path(path)

        if path in {"docs/FEATURE-TREE.zh-CN.md", "docs/FEATURE-TREE.zh-CN.html",
                    "docs/ACCEPTANCE-MATRIX.json", "docs/FUNCTION-AUDIT.zh-CN.md"}:
            audit_index = True
            continue

        if path in ARCHITECTURE_3_CONTROL_FILES and is_documentation(path):
            continue
        if is_documentation(path) or is_repository_metadata(path):
            continue

        # Local developer installers do not enter the Android binary. Keep
        # their real Artifact install regression in the separate toolchain workflow.
        # The shared version manifest is excluded: FontTools/JDK values also
        # influence reproducible packaged Android assets and build baselines.
        if path.startswith("tools/dev/") and path != "tools/dev/toolchain-versions.env":
            automation = True
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
        if path in CORE_CI_CONTROLS:
            automation = True
            full = True  # Validate the actual workflow being changed, including device execution.
            continue
        if is_ci_control(path):
            automation = True
            continue
        if is_automation(path):
            automation = True
            continue
        if is_android_test(path):
            device = True
            android16 = True
            android17 = True
            continue
        if is_unit_test(path) or is_test_tooling(path):
            unit = True
            continue

        if path in PLATFORM_FILES or path in ANDROID_PLATFORM_FILES or path.startswith(PLATFORM_PREFIXES):
            full = True
            affects_release = True
            continue
        if path.startswith(HIGH_RISK_PREFIXES):
            full = True
            affects_release = True
            continue

        if path.startswith(("app/src/main/", "core/src/main/", "harness-core/src/main/",
                            "harness-interop/src/main/", "harness-runtime-android/src/main/",
                            "harness-device-android/src/main/")):
            unit = True
            build = True
            affects_release = True
            if path in ANDROID_UI_ENTRYPOINTS:
                device = True
                android16 = True
            continue

        # Unknown inputs and build/runtime paths fail closed.
        full = True
        affects_release = True

    # PRs get targeted tests; main product changes retain the complete release
    # matrix. Documentation and automation-only pushes never become releases.
    if main_full_on_release and affects_release:
        full = True

    run_static = automation or architecture or unit or build or device or full or fixture or audit_index
    run_architecture = architecture or full
    run_unit = unit or full
    run_build = build or full
    run_device = device or full
    run_android16 = android16 or full
    run_android17 = android17 or full

    if full:
        scope = "full"
    elif affects_release:
        scope = "product-targeted"
    elif architecture and (unit or device):
        scope = "architecture-targeted"
    elif architecture:
        scope = "architecture-control"
    elif fixture:
        scope = "fixture"
    elif device:
        scope = "android-test"
    elif unit:
        scope = "unit-test"
    elif automation:
        scope = "automation"
    else:
        scope = "docs"

    return CiPlan(
        scope=scope,
        run_static=run_static,
        run_architecture=run_architecture,
        run_unit=run_unit,
        run_build=run_build,
        run_device=run_device,
        run_android=run_android16,
        run_android17=run_android17,
        run_fixture=fixture,
        affects_release=affects_release,
    )

def self_test() -> None:
    def verify(paths: list[str], *, required: tuple[str, ...] = (),
               excluded: tuple[str, ...] = (), full: bool = False,
               release: bool = False, main: bool = False) -> None:
        result = classify(paths, main_full_on_release=main)
        values = result.as_outputs()
        assert result.scope == "full" if full else result.scope != "full", (paths, result)
        assert result.affects_release == release, (paths, result)
        for item in required:
            assert values["run_" + item] == "true", (paths, item, result)
        for item in excluded:
            assert values["run_" + item] == "false", (paths, item, result)

    verify(["README.md"], excluded=("static", "architecture", "unit", "build",
                                   "device", "android16", "android17"))
    verify(["docs/ARCHITECTURE.md"], required=("static", "architecture"),
           excluded=("unit", "device"))
    verify([".github/workflows/ci.yml"], full=True,
           required=("architecture", "unit", "build", "android16", "android17"), release=False)
    verify([".github/workflows/release.yml"], full=True, release=False)
    verify([".github/scripts/check-local-architecture-boundaries.py"],
           required=("static", "architecture"), release=False)
    verify(["tools/dev/install.sh"], required=("static",),
           excluded=("unit", "build", "android16", "android17"), release=False)
    verify(["tools/dev/toolchain-versions.env"], full=True, release=True)
    verify([".github/release-version"], full=True, release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalComposer.kt"],
           required=("static", "architecture", "unit", "build"),
           excluded=("device", "android16", "android17"), release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/local/model/LocalGateway.kt"],
           required=("architecture", "unit", "build"),
           excluded=("android16", "android17"), release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeatureNavigation.kt"],
           required=("android16", "device"), excluded=("android17",), release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRepository.kt"],
           full=True, release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalComposer.kt"],
           full=True, main=True, release=True)
    verify(["README.md"], main=True, release=False)
    # A Markdown prompt/asset shipped within the app is product code, never
    # classified as documentation solely because of its extension.
    verify(["app/src/main/assets/prompts/system.md"], required=("unit", "build"),
           release=True)
    verify(["app/src/main/assets/prompts/system.md"], main=True, full=True,
           release=True)
    verify(["docs/FEATURE-TREE.zh-CN.md"], required=("static",),
           excluded=("unit", "build", "device"), release=False)
    verify(["docs/ACCEPTANCE-MATRIX.json"], required=("static",),
           excluded=("unit", "build"), release=False)
    verify(["app/src/androidTest/java/com/labteto/dshmobile/local/ExampleTest.kt"],
           required=("device", "android16", "android17"), release=False)
    verify(["harness-core/src/test/kotlin/example/Test.kt"],
           required=("unit", "architecture"), excluded=("build", "device"), release=False)
    verify(["upstream/deepseek-harness.lock.json"],
           required=("unit", "fixture"), excluded=("build", "android16"), release=False)
    verify(["app/src/main/AndroidManifest.xml"], full=True, release=True)
    verify(["build.gradle.kts"], full=True, release=True)
    verify(["app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalComposer.kt",
            "harness-core/src/test/kotlin/example/Test.kt"],
           required=("unit", "architecture", "build"), release=True)
    verify([".github/scripts/check-new-guard.py"],
           required=("static",), release=False)
    for path in sorted(ARCHITECTURE_AUTHORITY_FILES | ARCHITECTURE_3_CONTROL_FILES):
        verify([path], required=("static", "architecture"),
               full=(path in CORE_CI_CONTROLS))
    for path in sorted(FULL_VALIDATION_SCRIPTS):
        verify([path], full=True, required=("android16", "android17", "device"),
               release=False)
    for path in sorted(FIXTURE_PROVENANCE_PATHS):
        verify([path], required=("static", "unit", "fixture"))
    assert classify(["README.md"], force_full=True).run_android17
    assert classify(["README.md"], force_full=True).run_fixture
    print("CI scope classifier self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paths-file", type=Path)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--force-full", action="store_true")
    parser.add_argument("--main-full-on-release", action="store_true")
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
    plan = classify(paths, force_full=args.force_full, main_full_on_release=args.main_full_on_release)

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
