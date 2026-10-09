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
SCRIPTS = ROOT / ".github" / "scripts"
CLASSIFIER = (SCRIPTS / "classify-ci-scope.py").read_text(encoding="utf-8")
AGENTS = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
ARCHITECTURE = (ROOT / "docs" / "ARCHITECTURE.md").read_text(encoding="utf-8")
AUDIT_GUIDE = (ROOT / "docs" / "SYSTEM-AUDIT-GUIDE.zh-CN.md").read_text(encoding="utf-8")
VALIDATION = (ROOT / "docs" / "VALIDATION.md").read_text(encoding="utf-8")
DOCS_INDEX = (ROOT / "docs" / "README.md").read_text(encoding="utf-8")

violations: list[str] = []

# External GitHub Actions must be immutable. Local actions are allowed.
# Both step-level `- uses:` and job-level / nested `uses:` are valid workflow syntax.
uses_pattern = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)", re.MULTILINE)
# Both supported action shapes must be recognized; neither should evade pin checks.
assert uses_pattern.findall("  - uses: actions/checkout@abcdef\n    uses: actions/cache@123456\n") == [
    "actions/checkout@abcdef", "actions/cache@123456"
]

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

# Authority documents and the CI control plane must stay mutually reachable and internally aligned.
authority_refs = (
    "docs/ARCHITECTURE.md",
    "docs/SYSTEM-AUDIT-GUIDE.zh-CN.md",
    "docs/VALIDATION.md",
    "docs/DEVELOPMENT.md",
    "docs/SECURITY.md",
)
for authority_ref in authority_refs:
    if authority_ref not in AGENTS:
        violations.append(f"AGENTS.md lost authoritative entry: {authority_ref}")

architecture_refs = (
    "SYSTEM-AUDIT-GUIDE.zh-CN.md",
    "SHARED-AUDIT-CONCLUSIONS.zh-CN.md",
    "VALIDATION.md",
    "ANDROID-HARNESS-STATUS.zh-CN.md",
    "../AGENTS.md",
)
for authority_ref in architecture_refs:
    if authority_ref not in ARCHITECTURE:
        violations.append(f"docs/ARCHITECTURE.md lost related authority reference: {authority_ref}")

audit_contracts = (
    "字段契约对账",
    "配置与定义对账及共享配置提取",
    "依赖、导入、声明与装配对账",
    "关联功能协同",
    "自动能力闭环",
    "兼容与版本演进",
    "验证证据",
)
for audit_contract in audit_contracts:
    if audit_contract not in AUDIT_GUIDE:
        violations.append(
            "SYSTEM-AUDIT-GUIDE lost required system-audit dimension: " + audit_contract
        )

for raw_target in re.findall(r"\]\(([^)]+)\)", DOCS_INDEX):
    target = raw_target.split("#", 1)[0]
    if not target or "://" in target:
        continue
    resolved = (ROOT / "docs" / target).resolve()
    try:
        resolved.relative_to(ROOT.resolve())
    except ValueError:
        violations.append(f"docs/README.md contains out-of-repository link: {raw_target}")
        continue
    if not resolved.exists():
        violations.append(f"docs/README.md links missing current document/resource: {raw_target}")

required_ci_lanes = (
    "scope",
    "static-gates",
    "architecture-3-gates",
    "fixture-provenance",
    "unit-tests",
    "relay-conformance",
    "build-arm64",
    "device-artifacts-x86",
    "android-16-instrumented",
    "android-17-instrumented",
    "merge-gate",
)
for lane in required_ci_lanes:
    if re.search(rf"^  {re.escape(lane)}:\s*$", CI, re.MULTILINE) is None:
        violations.append(f"CI lost required lane: {lane}")
    if lane not in VALIDATION:
        violations.append(f"docs/VALIDATION.md lost current CI lane: {lane}")

validation_merge_section = ""
if "### merge-gate" in VALIDATION:
    validation_merge_section = VALIDATION.split("### merge-gate", 1)[1].split("\n## ", 1)[0]
for lane in (
    "static-gates",
    "architecture-3-gates",
    "unit-tests",
    "relay-conformance",
    "build-arm64",
    "device-artifacts-x86",
    "android-16-instrumented",
    "android-17-instrumented",
):
    if lane not in validation_merge_section:
        violations.append(f"docs/VALIDATION.md complete-product merge-gate list lost required lane: {lane}")

bootstrap_index = CI.find("- name: Bootstrap CI control plane")
classify_index = CI.find("- name: Classify CI scope")
if bootstrap_index < 0 or classify_index < 0 or bootstrap_index > classify_index:
    violations.append("scope must bootstrap CI control-plane checks before classifying changed files")
for bootstrap_command in (
    "python3 -m py_compile",
    "classify-ci-scope.py --self-test",
    "check-ci-repository-integrity.py",
):
    if bootstrap_command not in CI:
        violations.append("scope bootstrap lost required control-plane check: " + bootstrap_command)

if "- name: Verify selected scope coverage" not in CI:
    violations.append("scope must independently verify that critical changes cannot weaken selected CI coverage")

critical_architecture_controls = (
    "AGENTS.md",
    "docs/ARCHITECTURE.md",
    "docs/SYSTEM-AUDIT-GUIDE.zh-CN.md",
    "docs/VALIDATION.md",
    ".github/workflows/ci.yml",
    ".github/scripts/classify-ci-scope.py",
    ".github/scripts/check-ci-repository-integrity.py",
    ".github/scripts/check-local-architecture-boundaries.py",
    ".github/scripts/check-local-performance-invariants.py",
)
for control_path in critical_architecture_controls:
    if f'"{control_path}"' not in CLASSIFIER:
        violations.append(f"CI classifier lost architecture authority/control path: {control_path}")
    if control_path not in CI:
        violations.append(f"scope coverage verification lost architecture control path: {control_path}")

# Keep independent CI lanes concurrent without allowing expensive jobs to run
# before the cheap static smoke gates. The final merge gate remains authoritative.
def ci_job_source(lane: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(lane)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
        CI,
    )
    if match is None:
        violations.append(f"CI missing job body: {lane}")
        return ""
    return match.group(1)


ci_dag = {
    "static-gates": ("scope",),
    "architecture-3-gates": ("scope",),
    "fixture-provenance": ("scope",),
    "unit-tests": ("scope", "static-gates"),
    "relay-conformance": ("scope", "static-gates"),
    "build-arm64": ("scope", "static-gates"),
    "device-artifacts-x86": ("scope", "static-gates"),
    "android-16-instrumented": ("scope", "device-artifacts-x86"),
    "android-17-instrumented": ("scope", "device-artifacts-x86"),
}
for lane, dependencies in ci_dag.items():
    source = ci_job_source(lane)
    expected_needs = (
        f"    needs: {dependencies[0]}" if len(dependencies) == 1
        else f"    needs: [{', '.join(dependencies)}]"
    )
    if expected_needs not in source.splitlines():
        violations.append(f"{lane}: CI critical-path dependencies must be {dependencies}")
    for dependency in dependencies:
        if dependency == "scope":
            condition = "needs.scope.result == 'success'"
        elif dependency == "static-gates":
            condition = "needs.static-gates.result == 'success'"
        else:
            condition = "needs.device-artifacts-x86.result == 'success'"
        if condition not in source:
            violations.append(f"{lane}: missing successful dependency condition: {condition}")


merge_gate = CI.split("\n  merge-gate:\n", 1)[1] if "\n  merge-gate:\n" in CI else ""
for lane in required_ci_lanes[:-1]:
    if f"      - {lane}" not in merge_gate:
        violations.append(f"merge-gate no longer depends on required lane: {lane}")

merge_gate_contracts = {
    "static-gates": ("REQUIRE_STATIC:", "STATIC_RESULT:"),
    "architecture-3-gates": ("REQUIRE_ARCHITECTURE:", "ARCHITECTURE_RESULT:"),
    "fixture-provenance": ("REQUIRE_FIXTURE:", "FIXTURE_RESULT:"),
    "unit-tests": ("REQUIRE_UNIT:", "UNIT_RESULT:"),
    "relay-conformance": ("REQUIRE_RELAY:", "RELAY_RESULT:"),
    "build-arm64": ("REQUIRE_BUILD:", "BUILD_RESULT:"),
    "device-artifacts-x86": ("REQUIRE_DEVICE:", "DEVICE_RESULT:"),
    "android-16-instrumented": ("REQUIRE_ANDROID16:", "ANDROID16_RESULT:"),
    "android-17-instrumented": ("REQUIRE_ANDROID17:", "ANDROID17_RESULT:"),
}
for lane, tokens in merge_gate_contracts.items():
    for token in tokens:
        if token not in merge_gate:
            violations.append(f"merge-gate lost {lane} selection/result contract: {token}")

relay_contract_tokens = (
    "repository: sorsama/deepseek-harness-relay",
    "ref: 10c2758e77192413d9450a4d641daafb2a675286",
    "DSH_RELAY_CONFORMANCE_REQUIRED: true",
    "RelayConformanceTest",
)
for token in relay_contract_tokens:
    if token not in CI:
        violations.append(f"real relay conformance lane lost pinned contract: {token}")

# Every check-*.py file is a gate by convention. Reachability uses real run steps and
# interpreter invocations, not arbitrary mentions in comments, filenames or YAML conditions.
def workflow_run_commands(source: str) -> str:
    lines = source.splitlines()
    commands: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        match = re.match(r"^(\s*)(?:-\s*)?run:\s*(.*)$", line)
        if not match:
            index += 1
            continue
        indent = len(match.group(1))
        inline = match.group(2).strip()
        if inline not in ("|", "|-", ">", ">-", ""):
            commands.append(inline)
        else:
            index += 1
            while index < len(lines):
                continuation = lines[index]
                if continuation.strip() and len(continuation) - len(continuation.lstrip()) <= indent:
                    index -= 1
                    break
                if continuation.strip() and not continuation.lstrip().startswith("#"):
                    commands.append(continuation.strip())
                index += 1
        index += 1
    return "\n".join(commands)


script_invocation = re.compile(
    r"(?m)^\s*(?:(?:&&|\|\||;)\s*)?(?:(?:python3?|bash|sh)\s+)?(?:\./)?"
    r"(\.github/scripts/[A-Za-z0-9_.-]+\.(?:py|sh))\b"
)


def invoked_script_paths(source: str) -> set[str]:
    return set(script_invocation.findall(
        "\n".join(line for line in source.splitlines()
                  if not line.lstrip().startswith("#"))
    ))


# Guard against commented mentions, echo-only references and non-executing compile checks.
assert invoked_script_paths('python3 .github/scripts/check-example.py') == {
    '.github/scripts/check-example.py'
}
assert invoked_script_paths('.github/scripts/check-example.py') == {'.github/scripts/check-example.py'}
assert invoked_script_paths('echo .github/scripts/check-example.py') == set()
assert invoked_script_paths('echo python3 .github/scripts/check-example.py') == set()
assert invoked_script_paths('# python3 .github/scripts/check-example.py') == set()
assert invoked_script_paths('python3 -m py_compile .github/scripts/check-example.py') == set()
assert invoked_script_paths(workflow_run_commands('run: |\n  python3 .github/scripts/check-example.py\n')) == {
    '.github/scripts/check-example.py'
}
assert invoked_script_paths(workflow_run_commands(
    'run: echo ".github/scripts/check-example.py"\n'
)) == set()

script_sources: dict[str, str] = {}
for candidate in sorted(SCRIPTS.iterdir()):
    if not candidate.is_file():
        continue
    try:
        script_sources[candidate.relative_to(ROOT).as_posix()] = candidate.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        continue

reachable_scripts: set[str] = set()
frontier_sources = [
    workflow_run_commands(workflow.read_text(encoding="utf-8"))
    for workflow in sorted(WORKFLOWS.glob("*.yml"))
]
while frontier_sources:
    source = frontier_sources.pop()
    for relative in invoked_script_paths(source):
        if relative not in script_sources or relative in reachable_scripts:
            continue
        reachable_scripts.add(relative)
        frontier_sources.append(script_sources[relative])

for guard in sorted(SCRIPTS.glob("check-*.py")):
    if guard.relative_to(ROOT).as_posix() not in reachable_scripts:
        violations.append(f"{guard.name}: gate is not invoked by any reachable workflow run step")

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
architecture_guard_command = "run: python3 .github/scripts/check-local-architecture-boundaries.py"
performance_guard_command = "run: python3 .github/scripts/check-local-performance-invariants.py"
normalized_ci_lines = [line.strip() for line in CI.splitlines()]
if normalized_ci_lines.count(architecture_guard_command) != 1:
    violations.append("Architecture 3.0 ownership guard must execute exactly once in its dedicated lane")
if normalized_ci_lines.count(performance_guard_command) != 1:
    violations.append("Architecture 3.0 performance invariant guard must execute exactly once in its dedicated lane")
if "classify-ci-scope.py --self-test" not in CI:
    violations.append("CI scope classifier must self-test before downstream validation")

# release-version affects the app's versionName/versionCode and must never be ignored on main.
if "- '.github/release-version'" in CI or '- ".github/release-version"' in CI:
    violations.append(".github/release-version must not be ignored by main CI")

# Release freshness must reuse the same path classifier.
if "classify-ci-scope.py" not in RELEASE or "--release-safe" not in RELEASE:
    violations.append("Release freshness must reuse classify-ci-scope.py --release-safe")
if "cron: '*/30 * * * *'" not in RELEASE:
    violations.append("Release fallback polling must run every 30 minutes")

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
