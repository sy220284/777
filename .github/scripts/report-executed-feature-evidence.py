#!/usr/bin/env python3
"""Bind existing feature test references to JUnit XML actually run at this Git commit.

Never marks a UI/product function signed off. Unit success proves its named
assertions only, not end-to-end Android usability or real-device performance.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs/ACCEPTANCE-MATRIX.json"


def source_identity(root: Path, reference: str) -> tuple[str, str, str]:
    source, method = reference.rsplit("#", 1)
    path = root / source
    text = path.read_text(encoding="utf-8")
    package = re.search(r"(?m)^package\s+([\w.]+)", text)
    if package is None:
        raise ValueError(f"测试源码缺少包名：{source}")
    classname = package.group(1) + "." + path.stem
    mode = "android" if "/src/androidTest/" in "/" + source else "jvm"
    return classname, method, mode


def discovered_test_results(root: Path) -> dict[tuple[str, str], str]:
    results = {}
    for xml in sorted(root.glob("**/build/test-results/**/TEST-*.xml")):
        try:
            tree = ET.parse(xml)
        except ET.ParseError as exc:
            raise ValueError(f"JUnit XML 结果损坏：{xml}") from exc
        for item in tree.getroot().iter("testcase"):
            classname = item.get("classname") or ""
            rawname = item.get("name") or ""
            method = re.split(r"[\[(]", rawname, 1)[0].strip()
            if not classname or not method:
                raise ValueError(f"JUnit XML 测试身份不完整：{xml}")
            if item.find("failure") is not None or item.find("error") is not None:
                state = "failed"
            elif item.find("skipped") is not None:
                state = "skipped"
            else:
                state = "passed"
            key = classname, method
            if key in results and results[key] != state:
                raise ValueError(f"重复测试身份出现互斥结果：{key}")
            results[key] = state
    return results


def evidence_report(root: Path, sha: str) -> dict:
    matrix = json.loads((root / "docs/ACCEPTANCE-MATRIX.json").read_text(encoding="utf-8"))
    executed = discovered_test_results(root)
    rows = []
    counters = {"jvm_all_passed": 0, "jvm_failed": 0,
                "jvm_not_observed": 0, "android_needed": 0}
    for item in matrix["items"]:
        proofs = []
        for ref in item["evidence"]:
            classname, method, mode = source_identity(root, ref)
            status = ("android_needed" if mode == "android" else
                      executed.get((classname, method), "not_observed"))
            proofs.append({"test": ref, "source": mode, "result": status})
        jvm = [p["result"] for p in proofs if p["source"] == "jvm"]
        if any(p == "failed" for p in jvm):
            result = "jvm_failed"
        elif jvm and all(p == "passed" for p in jvm):
            result = "jvm_all_passed" if len(jvm) == len(proofs) else "android_needed"
        elif any(p == "not_observed" or p == "skipped" for p in jvm):
            result = "jvm_not_observed"
        else:
            result = "android_needed"
        counters[result] += 1
        rows.append({
            "path": item["path"], "observed_result": result,
            "signed_off": False, "evidence": proofs,
        })
    # Always report ALL leaves, including leaves with no runnable evidence.
    review_path = root / "docs/FEATURE-REVIEW.auto.json"
    all_leaves = []
    coverage_counts = {"mapped": len(rows), "unmapped": 0, "fully_signed_off": 0}
    if review_path.is_file():
        review = json.loads(review_path.read_text(encoding="utf-8"))
        by_path = {row["path"]: row for row in rows}
        for leaf in review["leaf_evidence"]:
            proof = by_path.get(leaf["path"])
            all_leaves.append({
                "path": leaf["path"],
                "source_grade": leaf["verification"],
                "executable_state": proof["observed_result"] if proof else "no_executable_mapping",
                "mapped_proofs": proof["evidence"] if proof else [],
                "signed_off": False,
            })
        if len(all_leaves) != review["functional_leaves"] or len(set(x["path"] for x in all_leaves)) != len(all_leaves):
            raise ValueError("functional leaf evidence does not cover unique full tree")
        coverage_counts["unmapped"] = sum(x["executable_state"] == "no_executable_mapping" for x in all_leaves)
        if coverage_counts["mapped"] + coverage_counts["unmapped"] != len(all_leaves):
            raise ValueError("executable coverage counts inconsistent with feature tree")

    # U01-U10 share real JUnit facts, but no partial assertion may be promoted to complete.
    agent_groups = []
    group_path = root / "docs/AGENT-ACCEPTANCE-U01-U10.json"
    if group_path.is_file():
        groups = json.loads(group_path.read_text(encoding="utf-8"))
        if [g["id"] for g in groups["groups"]] != [f"U{i:02d}" for i in range(1, 11)]:
            raise ValueError("missing or duplicated agent delivery package")
        for group in groups["groups"]:
            proofs = []
            for ref in group["evidence"]:
                cls, method, mode = source_identity(root, ref)
                status = "android_needed" if mode == "android" else executed.get((cls, method), "not_observed")
                proofs.append({"test": ref, "source": mode, "result": status})
            agent_groups.append({
                "id": group["id"], "title": group["title"], "proofs": proofs,
                "open_requirements": group["open_requirements"], "full_acceptance": False,
            })

    return {
        "version": 1,
        "commit": sha,
        "kind": "executed_jvm_evidence_only",
        "junit_testcases_observed": len(executed),
        "mapped_features": len(rows),
        "counts": counters,
        "fully_signed_off": 0,
        "functional_leaves": len(all_leaves),
        "coverage_counts": coverage_counts,
        "all_feature_leaves": all_leaves,
        "agent_groups": agent_groups,
        "original_differential_cases_executed": 0,
        "items": rows,
    }


def self_test():
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "app/src/test/java/example/WorkflowTest.kt"
        source.parent.mkdir(parents=True)
        source.write_text("package example\nclass WorkflowTest {}\n", encoding="utf-8")
        matrix = root / "docs/ACCEPTANCE-MATRIX.json"
        matrix.parent.mkdir()
        matrix.write_text(json.dumps({"items": [{
            "path": "功能 / 已验证", "evidence": [
                "app/src/test/java/example/WorkflowTest.kt#works",
            ], "state": "automated_partial",
        }]}), encoding="utf-8")
        xml = root / "app/build/test-results/testDebugUnitTest/TEST-example.WorkflowTest.xml"
        xml.parent.mkdir(parents=True)
        xml.write_text('<testsuite><testcase classname="example.WorkflowTest" name="works"/></testsuite>',
                       encoding="utf-8")
        result = evidence_report(root, "test-sha")
        assert result["counts"]["jvm_all_passed"] == 1
        assert result["fully_signed_off"] == 0
        xml.write_text('<testsuite><testcase classname="example.WorkflowTest" name="works">'
                       '<failure message="bad"/></testcase></testsuite>', encoding="utf-8")
        assert evidence_report(root, "test-sha")["counts"]["jvm_failed"] == 1
        xml.unlink()
        assert evidence_report(root, "test-sha")["counts"]["jvm_not_observed"] == 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output", default=".ci/feature-jvm-evidence.json")
    args = parser.parse_args()
    self_test()
    if args.self_test:
        print("Executable feature evidence mapper self-test passed")
        return
    sha = os.getenv("GITHUB_SHA") or os.popen("git rev-parse HEAD").read().strip()
    report = evidence_report(ROOT, sha)
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    print(f"当前 {sha}: {report['mapped_features']} 个映射功能，"
          f"JVM 全通过 {report['counts']['jvm_all_passed']}，"
          f"失败 {report['counts']['jvm_failed']}，"
          f"未观察 {report['counts']['jvm_not_observed']}，"
          f"待设备证据 {report['counts']['android_needed']}，全量签收 0")
    if report["junit_testcases_observed"] == 0:
        raise ValueError("未发现任何 JUnit XML，拒绝声称已执行单元测试")
    if report["counts"]["jvm_failed"] != 0:
        raise ValueError("已登记功能的 JVM 证据实际失败")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"Executable feature evidence report FAILED: {error}", file=sys.stderr)
        sys.exit(1)
