#!/usr/bin/env python3
"""Bind actual AndroidJUnitRunner finished test receipts to every feature leaf.

A full-suite OK line and terminal code are mandatory. A named @Test declaration,
a successful build, and an Android startup smoke are never test execution receipts.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
STATUS = re.compile(r"^INSTRUMENTATION_STATUS: (class|test|numtests)=(.*)$")
CODE = re.compile(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$")
OK = re.compile(r"^OK \(([1-9]\d*) tests?\)$")


def parse_instrumentation(text: str) -> tuple[dict, int, int]:
    seen = {}
    named_receipts = 0
    classname = method = ""
    reported = None
    terminal = False
    for raw in text.splitlines():
        line = raw.strip()
        status = STATUS.fullmatch(line)
        if status:
            field, value = status.groups()
            if field == "class":
                classname = value
            elif field == "test":
                method = value
            continue
        code = CODE.fullmatch(line)
        if code:
            outcome = int(code.group(1))
            if outcome in (0, -2, -3, -4):
                named_receipts += 1
                if not classname or not method:
                    raise ValueError("Android test result lacks a class or method identity")
                key = (classname, method)
                result = {0: "passed", -2: "failed", -3: "skipped", -4: "skipped"}[outcome]
                if key in seen and seen[key] != result:
                    raise ValueError(f"contradictory Android test receipts: {key}")
                seen[key] = result
        matches = OK.fullmatch(line)
        if matches:
            reported = int(matches.group(1))
        if line == "INSTRUMENTATION_CODE: -1":
            terminal = True
    if not terminal or reported is None:
        raise ValueError("AndroidJUnitRunner did not report a complete successful test suite")
    if named_receipts > reported:
        raise ValueError(f"Android test receipts exceed reported suite size: {named_receipts}/{reported}")
    if any(state == "failed" for state in seen.values()):
        raise ValueError("Android suite has an actual failed named test receipt")
    # AndroidJUnitRunner includes two intentionally assumption-skipped cross-process
    # phases in its suite count; they run separately with an ADB force-stop.
    # A partial instrumentation stream cannot authenticate an unseen named test.
    return seen, reported, named_receipts


def test_identity(root: Path, reference: str):
    source, method = reference.rsplit("#", 1)
    if "/src/androidTest/" not in "/" + source:
        return None
    path = root / source
    package = re.search(r"(?m)^package\s+([\w.]+)", path.read_text(encoding="utf-8"))
    if not package:
        raise ValueError(f"Android test source lacks a package: {reference}")
    return package.group(1) + "." + path.stem, method


def report(root: Path, text: str, sha: str, android_api: int):
    results, reported, named_receipts = parse_instrumentation(text)
    matrix = json.loads((root / "docs/ACCEPTANCE-MATRIX.json").read_text(encoding="utf-8"))
    review = json.loads((root / "docs/FEATURE-REVIEW.auto.json").read_text(encoding="utf-8"))
    groups = json.loads((root / "docs/AGENT-ACCEPTANCE-U01-U10.json").read_text(encoding="utf-8"))
    by_path = {}
    for item in matrix["items"]:
        proofs = []
        for ref in item["evidence"]:
            key = test_identity(root, ref)
            if key is not None:
                proofs.append({"test": ref, "result": results.get(key, "not_observed")})
        by_path[item["path"]] = proofs
    leaves = [
        {"path": entry["path"],
         "device_evidence": by_path.get(entry["path"], []),
         "device_signed_off": False}
        for entry in review["leaf_evidence"]
    ]
    if len(leaves) != review["functional_leaves"] or len({x["path"] for x in leaves}) != len(leaves):
        raise ValueError("Android feature inventory does not match all functional leaves")
    mapped = [p for item in leaves for p in item["device_evidence"]]
    missing = [p["test"] for p in mapped if p["result"] != "passed"]
    if missing:
        raise ValueError("Android mapped feature tests were not executed successfully: " + ", ".join(missing))
    packages = []
    for group in groups["groups"]:
        receipts = []
        for ref in group["evidence"]:
            key = test_identity(root, ref)
            if key is not None:
                receipts.append({"test": ref, "result": results.get(key, "not_observed")})
        packages.append({"id": group["id"], "device_proofs": receipts, "full_acceptance": False})
    return {
        "version": 1, "commit": sha, "android_api": android_api,
        "kind": "executed_android_instrumentation_evidence_only",
        "junit_testcases_reported": reported,
        "named_test_receipts_observed": named_receipts,
        "unobserved_test_receipts": reported - named_receipts,
        "assumption_skipped_tests": sum(x == "skipped" for x in results.values()),
        "junit_testcases_observed": len(results),
        "functional_leaves": len(leaves),
        "mapped_android_test_references": len(mapped),
        "mapped_android_test_references_passed": len(mapped) - len(missing),
        "fully_signed_off": 0, "all_feature_leaves": leaves,
        "agent_groups": packages, "original_differential_cases_executed": 0,
    }


def self_test():
    sample = ("INSTRUMENTATION_STATUS: class=demo.RealTest\n"
              "INSTRUMENTATION_STATUS: test=works\n"
              "INSTRUMENTATION_STATUS_CODE: 1\n"
              "INSTRUMENTATION_STATUS: class=demo.RealTest\n"
              "INSTRUMENTATION_STATUS: test=works\n"
              "INSTRUMENTATION_STATUS_CODE: 0\n"
              "OK (1 test)\nINSTRUMENTATION_CODE: -1\n")
    assert parse_instrumentation(sample) == ({("demo.RealTest", "works"): "passed"}, 1, 1)
    skipped, total, count = parse_instrumentation(sample.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -4"))
    assert skipped == {("demo.RealTest", "works"): "skipped"} and total == count == 1
    partial, total, count = parse_instrumentation(sample.replace("OK (1 test)", "OK (2 tests)"))
    assert partial[("demo.RealTest", "works")] == "passed" and total == 2 and count == 1
    for candidate in (sample.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2"),
                      sample.replace("OK (1 test)", "OK (0 tests)"),
                      sample.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")):
        try:
            parse_instrumentation(candidate)
        except ValueError:
            continue
        raise AssertionError("Invalid Android test receipts were accepted")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("instrumentation_log", nargs="?")
    parser.add_argument("android_api", type=int, nargs="?")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print("Android feature evidence parser self-test passed")
        return
    if not args.instrumentation_log or args.android_api not in (36, 37):
        raise ValueError("An actual Android 16/17 instrumentation log and API level are required")
    log = Path(args.instrumentation_log)
    sha = os.environ.get("GITHUB_SHA") or os.popen("git rev-parse HEAD").read().strip()
    result = report(ROOT, log.read_text(encoding="utf-8"), sha, args.android_api)
    dest = ROOT / f".ci/executed-android{args.android_api}-feature-evidence.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Android {args.android_api} reported {result['junit_testcases_reported']} tests, "
          f"named receipts {result['named_test_receipts_observed']}, "
          f"unobserved receipts {result['unobserved_test_receipts']}; "
          f"{result['mapped_android_test_references_passed']} mapped Android proofs, "
          f"{result['functional_leaves']} enumerated leaves, 0 fully signed off")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as exc:
        print(f"Android feature evidence FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
