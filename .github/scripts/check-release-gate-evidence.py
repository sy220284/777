#!/usr/bin/env python3
"""Exercise release authorization and scope decisions with executable negative cases.

Release is an independent permission gate. A normal CI success or missing one
critical lane must never count as complete binary-release acceptance.
"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
RELEASE = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
SCOPE = ROOT / ".github/scripts/classify-ci-scope.py"

def fail(message: str) -> None:
    raise SystemExit("Release policy regression failed: " + message)

# Test the *actual* jq expression embedded in release.yml, not a rewritten
# Python interpretation that could drift from what the release job executes.
match = re.search(
    r"""if printf '%s' "\$jobs" \| jq -e '\n(?P<filter>.*?)\n\s+' >/dev/null""",
    RELEASE,
    re.S,
)
if not match:
    fail("cannot locate the live full-main-validation jq predicate")
predicate = match.group("filter")
LANES = (
    "scope", "static-gates", "architecture-3-gates",
    "unit-tests", "build-arm64", "device-artifacts-x86",
    "android-16-instrumented", "android-17-instrumented", "merge-gate",
)

def evidence_passes(jobs: list[dict]) -> bool:
    result = subprocess.run(
        ["jq", "-e", predicate],
        input=json.dumps({"jobs": jobs}),
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode not in (0, 1):
        fail("jq predicate crashed: " + result.stderr.strip())
    return result.returncode == 0

def jobs_from(passed: set[str]) -> list[dict]:
    return [{"name": name, "conclusion": "success" if name in passed else "skipped"}
            for name in LANES]

if not evidence_passes(jobs_from(set(LANES))):
    fail("fully successful validation was rejected")
for missing in LANES:
    if evidence_passes(jobs_from(set(LANES) - {missing})):
        fail("missing mandatory lane was accepted: " + missing)
if evidence_passes([]):
    fail("empty job list was accepted")
if evidence_passes([{"name": "merge-gate", "conclusion": "success"}]):
    fail("merge-gate alone authorized release")
if evidence_passes([{"name": name, "conclusion": "failure"} for name in LANES]):
    fail("failed jobs authorized release")

def release_equivalent(paths: list[str]) -> bool:
    with tempfile.TemporaryDirectory(prefix="ci-release-policy-") as temp:
        file = pathlib.Path(temp) / "changed-paths.txt"
        file.write_text("\n".join(paths) + "\n", encoding="utf-8")
        result = subprocess.run(
            [sys.executable, str(SCOPE), "--paths-file", str(file), "--release-safe"],
            capture_output=True, text=True, cwd=ROOT,
        )
        if result.returncode not in (0, 1):
            fail("release-safe scope crashed: " + result.stderr.strip())
        return result.returncode == 0

for paths in (
    ["README.md", "docs/README.md"],
    ["docs/ACCEPTANCE-MATRIX.json", "docs/ACCEPTANCE-PLAYBOOK.zh-CN.md"],
    ["app/src/test/java/com/labteto/dshmobile/local/OfflineCrossFeatureAcceptanceTest.kt",
     "docs/ACCEPTANCE-MATRIX.json"],
    [".github/workflows/release.yml"],
    ["tools/dev/install.sh"],
):
    if not release_equivalent(paths):
        fail("non-binary change incorrectly requests publication: " + ", ".join(paths))

for paths in (
    [".github/release-version"],
    ["app/src/main/AndroidManifest.xml"],
    ["app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalComposer.kt"],
    ["docs/ACCEPTANCE-MATRIX.json", "app/src/main/java/com/labteto/dshmobile/MainActivity.kt"],
    ["tools/dev/toolchain-versions.env"],
    ["unrecognized/binary-impacting-source"],
):
    if release_equivalent(paths):
        fail("binary-impacting change incorrectly skipped: " + ", ".join(paths))

# CI and release must observe old as well as new paths on file moves. GitHub
# Compare uses previous_filename; local git diff disables rename coalescing.
CI = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
# Main pushes are isolated by commit: a newer merge must neither cancel nor
# replace an older full-validation run. PR refreshes may retire obsolete checks.
ci_concurrency = CI.split("\nconcurrency:\n", 1)[1].split("\n\njobs:", 1)[0]
ci_group = re.search(r"(?m)^  group: (.+)$", ci_concurrency)
if (ci_group is None or
        "github.event_name" not in ci_group.group(1) or
        "github.sha" not in ci_group.group(1) or
        "github.event.pull_request.number" not in ci_group.group(1)):
    fail("CI must use distinct concurrency groups per event and main commit / PR")
# Never cancel the exact main commit / manually validated main SHA. Obsolete
# feature-branch dispatches and PR checks may be replaced by newer revisions.
if ("github.event_name == 'workflow_dispatch'" not in ci_group.group(1) or
        "github.ref_name != 'main'" not in ci_group.group(1)):
    fail("CI must preserve main commit isolation while coalescing feature-branch runs")
expected_cancel = ("cancel-in-progress: ${{ github.event_name == 'pull_request' || "
                   "(github.event_name == 'workflow_dispatch' && github.ref_name != 'main') }}")
if expected_cancel not in ci_concurrency:
    fail("CI must retain immutable main validation and cancel only replaceable PR/branch runs")

if CI.count("git diff --no-renames --name-only --diff-filter=ACMRD") < 2:
    fail("CI rename detection can conceal a removed product source")
if "git diff --no-renames --name-only --diff-filter=ACMRD" not in RELEASE:
    fail("release version comparison can conceal a removed product source")
if ".previous_filename?" not in RELEASE:
    fail("GitHub compare ignores source paths of renamed files")
if release_equivalent([
    "app/src/main/java/com/labteto/dshmobile/local/LegacyEngine.kt",
    "docs/LegacyEngine.md",
]):
    fail("binary source moved to docs incorrectly considered release equivalent")

if '.event == "push"' not in RELEASE or '.conclusion == "success"' not in RELEASE:
    fail("full validation must come from a successful main push workflow")
# GitHub schedule 会漏触发：可信 main push CI 成功时检查，定时和手动兜底。
# 两个入口都必须继续冻结事件时的主线 SHA，不能被后续合并换包或中止。
triggers = RELEASE.split("'on':\n", 1)[1].split("\npermissions:", 1)[0]
if "cron: '13,43 * * * *'" not in triggers:
    fail("release must retain the half-hour fallback away from the busy hour boundary")
if not re.search(r"(?m)^  workflow_run:\n    workflows: \[CI\]\n    types: \[completed\]\n    branches: \[main\]$", triggers):
    fail("release must respond only to completed CI workflows on main")
if re.search(r"(?m)^  (?:push|pull_request|pull_request_target|release):", triggers):
    fail("release must not bypass complete main CI validation via push/PR/tag events")
required_trigger_fence = (
    "github.event_name != 'workflow_run'",
    "github.event.workflow_run.event == 'push'",
    "github.event.workflow_run.conclusion == 'success'",
    "github.event.workflow_run.head_branch == 'main'",
    "github.event.workflow_run.head_repository.full_name == github.repository",
)
check_release = RELEASE.split("  check-release:\n", 1)[1].split("\n  release:", 1)[0]
if not all(fence in check_release.split("    runs-on:", 1)[0] for fence in required_trigger_fence):
    fail("release trigger must exclude untrusted and failed upstream runs")
if "workflow_dispatch:" not in triggers:
    fail("manual release recovery entry was removed")
if not re.search(r"(?m)^  cancel-in-progress: false$", RELEASE):
    fail("a newer release run can cancel the running batch")
if "ref: ${{ github.sha }}" not in RELEASE:
    fail("scheduled checkout does not pin the trigger snapshot")
if 'BATCH_SHA="$(git rev-parse HEAD)"' not in RELEASE:
    fail("release batch does not freeze its starting SHA")
if 'release_equivalent_to_batch "$RELEASE_SHA" "$BATCH_SHA"' not in RELEASE:
    fail("release gate does not compare to the frozen batch")
if "git/ref/heads/main" in RELEASE or "Recheck main before publish" in RELEASE:
    fail("release still follows new main commits during an active build")
if "if: steps.freshness" in RELEASE:
    fail("new merges can still veto a completed release build")
if "target_commitish: ${{ needs.check-release.outputs.release_sha }}" not in RELEASE:
    fail("publication is not pinned to the validated candidate")

print("Release-eligibility evidence, CI completion trigger, half-hour fallback and immutable batch regression passed")
