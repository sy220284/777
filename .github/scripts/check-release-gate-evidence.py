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
    "unit-tests", "relay-conformance", "build-arm64", "device-artifacts-x86",
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

if "'.event == \"push\"'" in RELEASE:
    pass  # Not a stable spelling requirement; the jq job evidence is authoritative.
if '.event == "push"' not in RELEASE or '.conclusion == "success"' not in RELEASE:
    fail("full validation must come from a successful main push workflow")
print("Release-eligibility evidence and scope regression passed")
