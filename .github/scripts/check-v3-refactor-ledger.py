#!/usr/bin/env python3
"""Fail closed on invalid AE4 V3 dependency/status/evidence claims.

This is a machine-checkable *integrity* gate. It does not replace a successful
current-commit CI run, product acceptance, or human review of feature behavior.
"""

from __future__ import annotations

import copy
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEDGER = Path("docs/agent-engine4/PR705-KOTLIN-REBUILD-TASKS.json")
VALID_STATES = {"not_started", "in_progress", "completed"}
EXPECTED_IDS = [f"AE4V3-{i:02d}" for i in range(1, 25)]


def validate(data: dict, root: Path = ROOT) -> None:
    if data.get("baseline", {}).get("pr") != 705:
        raise ValueError("not the PR705 V3 authoritative ledger")
    tasks = data.get("tasks")
    if not isinstance(tasks, list) or [x.get("id") for x in tasks] != EXPECTED_IDS:
        raise ValueError("V3 must retain 24 unique, ordered canonical task IDs")
    known: dict[str, str] = {}
    for task in tasks:
        task_id = task["id"]
        state = task.get("status")
        if state not in VALID_STATES:
            raise ValueError(f"{task_id}: invalid status {state!r}")
        deps = task.get("depends_on")
        if not isinstance(deps, list) or len(deps) != len(set(deps)):
            raise ValueError(f"{task_id}: dependency list is missing or duplicated")
        if any(dep not in known for dep in deps):
            raise ValueError(f"{task_id}: dependencies must precede their consumer")
        paths = task.get("code_evidence")
        tests = task.get("tests_verified")
        if not isinstance(paths, list) or not isinstance(tests, list):
            raise ValueError(f"{task_id}: code/test evidence must be arrays")
        if len(paths) != len(set(paths)) or len(tests) != len(set(tests)):
            raise ValueError(f"{task_id}: duplicated evidence cannot count twice")
        for path in paths:
            if not isinstance(path, str) or path.startswith("/") or ".." in Path(path).parts:
                raise ValueError(f"{task_id}: unsafe evidence path {path!r}")
            if not (root / path).is_file():
                raise ValueError(f"{task_id}: evidence source does not exist: {path}")
        if state == "completed":
            waiting = [dep for dep in deps if known[dep] != "completed"]
            if waiting:
                raise ValueError(f"{task_id}: cannot close before predecessors {waiting}")
            if not paths or not tests:
                raise ValueError(f"{task_id}: production evidence and tests required to close")
            if any(not isinstance(evidence, str) or not evidence.strip() for evidence in tests):
                raise ValueError(f"{task_id}: empty or non-string test evidence")
        known[task_id] = state

    last = tasks[-1]
    if last["status"] == "completed":
        if any(state != "completed" for state in known.values()):
            raise ValueError("AE4V3-24 cannot close while any V3 task remains unfinished")
        signed = data.get("final_verification", {})
        if not isinstance(signed, dict) or not re.fullmatch(
            r"[0-9a-f]{40}", str(signed.get("commit_sha", "")),
        ) or not isinstance(signed.get("ci_run_id"), int) or signed["ci_run_id"] <= 0:
            raise ValueError("AE4V3-24 needs recorded final SHA and CI run identity")
        if signed.get("ci_conclusion") != "success":
            raise ValueError("AE4V3-24 needs a successful final CI attestation")


def self_test(data: dict) -> None:
    validate(data)
    invalid = copy.deepcopy(data)
    invalid["tasks"][5]["id"] = "AE4V3-05"
    _must_reject(invalid, "duplicate tasks")
    invalid = copy.deepcopy(data)
    final = invalid["tasks"][-1]
    final["status"] = "completed"
    final["code_evidence"] = [str(LEDGER)]
    final["tests_verified"] = ["fabricated"]
    _must_reject(invalid, "skip dependency closure")
    invalid = copy.deepcopy(data)
    invalid["tasks"][-1]["code_evidence"] = ["../../missing"]
    _must_reject(invalid, "path traversal evidence")


def _must_reject(value: dict, label: str) -> None:
    try:
        validate(value)
    except ValueError:
        return
    raise AssertionError(f"V3 gate accepted invalid {label}")


if __name__ == "__main__":
    try:
        ledger = json.loads((ROOT / LEDGER).read_text(encoding="utf-8"))
        if "--self-test" in sys.argv:
            self_test(ledger)
        else:
            validate(ledger)
            states = {status: sum(t["status"] == status for t in ledger["tasks"])
                      for status in VALID_STATES}
            print(f"V3 task ledger integrity OK: 24 tasks; {states}; "
                  "CI and functional sign-off are separate")
    except (ValueError, KeyError, OSError, AssertionError, TypeError) as error:
        print(f"V3 task ledger integrity FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
