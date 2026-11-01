#!/usr/bin/env python3
"""Fail CI when the feature tree and acceptance evidence index drift.

A passing audit-index check proves index integrity, NEVER functional acceptance.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TREE = ROOT / "docs/FEATURE-TREE.zh-CN.md"
MATRIX = ROOT / "docs/ACCEPTANCE-MATRIX.json"
TEST_RE = r"@Test\s+(?:fun\s+)(?P<name>[A-Za-z][A-Za-z_0-9]*)\s*\("

def leaves_from_tree(text):
    match = re.search(r"```text\s*\n(.*?)\n```", text, re.S)
    if not match:
        raise ValueError("function tree has no text block")
    nodes, chain = [], []
    for line in match.group(1).splitlines():
        m = re.match(r"^([│ ]*)([├└])─ (.*)$", line)
        if not m:
            continue
        if len(m.group(1)) % 3:
            raise ValueError("invalid tree depth")
        depth = len(m.group(1)) // 3
        title = re.sub(r"\s+\[(?:来源 )?PR #\d+[^\]]*\]$", "", m.group(3).strip())
        if depth > len(chain):
            raise ValueError("tree jumped over a parent")
        chain = chain[:depth] + [title]
        nodes.append((depth, " / ".join(chain)))
    return [path for i, (depth, path) in enumerate(nodes)
            if i + 1 == len(nodes) or nodes[i + 1][0] <= depth]

def expected_open_gaps(root, leaves, items):
    review = json.loads((root / "docs/FEATURE-REVIEW.auto.json").read_text(encoding="utf-8"))
    groups = json.loads((root / "docs/AGENT-ACCEPTANCE-U01-U10.json").read_text(encoding="utf-8"))
    if [item["path"] for item in review["leaf_evidence"]] != leaves:
        raise ValueError("functional review and authoritative feature tree differ")
    indexed = {item["path"] for item in items}
    pending = [item for item in review["leaf_evidence"] if item["path"] not in indexed]
    return {
        "version": 1,
        "scope": "all_unmapped_feature_leaves_and_multi_agent_open_requirements",
        "feature_tree_total": review["functional_leaves"],
        "mapped_partial": len(items),
        "pending_feature_count": len(pending),
        "full_signed_off": 0,
        "original_differential": {
            "required_count": groups["scenario_baseline"]["required_count"],
            "original_cases_recorded": groups["scenario_baseline"]["original_cases_recorded"],
            "status": "原始84条同题差分定义尚未入仓；禁止以派生测试代替",
        },
        "agent_groups": [
            {"id": group["id"], "full_acceptance": group["full_acceptance"],
             "existing_test_references": len(group["evidence"]),
             "open_requirements": group["open_requirements"]}
            for group in groups["groups"]
        ],
        "items": [
            {"path": item["path"], "status": "open_unverified",
             "source_evidence_grade": item["verification"],
             "production_sources": item.get("existing_production_sources", []),
             "named_symbols": [symbol["symbol"] for symbol in item.get("named_symbols", [])
                               if symbol.get("declaration_found")],
             "next_validation": (
                 "补充对应命令的真实运行回执及行为验收" if "CI或发布工作流" in item["verification"]
                 else "确认真实生产入口、补充独立测试和运行回执" if "候选生产文件" in item["verification"]
                 else "复核入口到结果的调用链、补充独立测试和运行回执" if "历史生产文件" in item["verification"]
                 else "补充与功能语义对应的独立测试、当次运行回执及必要设备端到端验收")}
            for item in pending
        ],
    }


def validate(root=ROOT):
    leaves = leaves_from_tree((root / "docs/FEATURE-TREE.zh-CN.md").read_text(encoding="utf-8"))
    matrix = json.loads((root / "docs/ACCEPTANCE-MATRIX.json").read_text(encoding="utf-8"))
    items = matrix["items"]
    if not leaves or not items:
        raise ValueError("feature tree and acceptance index must not be empty")
    # The feature tree is authoritative: additions/removals cannot be hidden by a frozen count.
    if len(set(leaves)) != len(leaves):
        raise ValueError("feature tree contains duplicate leaf paths")
    if matrix.get("version") != 2 or matrix.get("repository_scope") != "executable_evidence_only":
        raise ValueError("repository evidence index schema and scope must be v2")
    if not isinstance(matrix.get("external_validation_exclusions"), list) or not matrix["external_validation_exclusions"]:
        raise ValueError("external-only validation dimensions must be excluded from repository CI")
    leaf_order = {leaf: i for i, leaf in enumerate(leaves)}
    paths = [item["path"] for item in items]
    if len(set(paths)) != len(paths) or any(path not in leaf_order for path in paths):
        raise ValueError("evidence index has duplicate or unknown feature-tree leaf")
    if paths != sorted(paths, key=leaf_order.__getitem__):
        raise ValueError("evidence index order differs from authoritative feature tree")
    partial = 0
    for item in items:
        if set(item) != {"path", "state", "evidence"}:
            raise ValueError(f"unexpected or missing acceptance fields: {item['path']}")
        if item["state"] != "automated_partial":
            raise ValueError(f"repository acceptance records must carry executable evidence: {item['path']}")
        if not isinstance(item["evidence"], list):
            raise ValueError(f"non-list evidence: {item['path']}")
        if not item["evidence"]:
            raise ValueError(f"repository acceptance item lacks an executable test: {item['path']}")
        if len(item["evidence"]) != len(set(item["evidence"])):
            raise ValueError(f"duplicate executable evidence reference: {item['path']}")
        for ref in item["evidence"]:
            if not isinstance(ref, str) or "#" not in ref:
                raise ValueError(f"invalid evidence reference: {ref}")
            path, method = ref.rsplit("#", 1)
            source = root / path
            if not source.is_file() or not path.endswith(".kt") or "/src/" not in path:
                raise ValueError(f"missing executable test source: {ref}")
            methods = set(re.findall(TEST_RE, source.read_text(encoding="utf-8")))
            if method not in methods:
                raise ValueError(f"missing @Test method: {ref}")
        partial += item["state"] == "automated_partial"
    expected_gaps = expected_open_gaps(root, leaves, items)
    observed_gaps = json.loads((root / "docs/ACCEPTANCE-OPEN-GAPS.json").read_text(encoding="utf-8"))
    if observed_gaps != expected_gaps:
        raise ValueError("open-gap registry stale: run check-acceptance-matrix.py --sync-open-gaps")
    if len(items) + expected_gaps["pending_feature_count"] != len(leaves):
        raise ValueError("feature tree coverage has omitted leaves")
    # Never let an already-reviewed test fall out of the executable index.
    registry = json.loads((root / "docs/FEATURE-TREE-SOURCE-REGISTRY.json").read_text(encoding="utf-8"))
    explicit = json.loads((root / "docs/FEATURE-TREE-EXPLICIT-EVIDENCE.json").read_text(encoding="utf-8"))
    required = {}
    for node in registry["nodes"]:
        required.setdefault(node["path"], set()).add(node["test"])
    for node in explicit["items"]:
        for test in node.get("tests", []):
            required.setdefault(node["path"], set()).add(test["path"] + "#" + test["method"])
    # Previously reviewed triage tests become durable evidence obligations.
    # An unavailable UI test is recorded separately, never as executable evidence.
    triage = json.loads((root / "docs/FEATURE-EXISTENCE-TRIAGE.json").read_text(encoding="utf-8"))
    for node in triage["items"]:
        for test in node.get("test", []):
            required.setdefault(node["path"], set()).add(test["path"] + "#" + test["method"])
    indexed = {item["path"]: set(item["evidence"]) for item in items}
    for path, tests in required.items():
        if not tests.issubset(indexed.get(path, set())):
            raise ValueError(f"confirmed feature tests missing from acceptance index: {path} {sorted(tests - indexed.get(path, set()))}")
    groups = json.loads((root / "docs/AGENT-ACCEPTANCE-U01-U10.json").read_text(encoding="utf-8"))
    if groups.get("version") != 1 or groups.get("scenario_baseline", {}).get("required_count") != 84:
        raise ValueError("original 84 differential cases requirement was lost")
    if [g.get("id") for g in groups["groups"]] != [f"U{i:02d}" for i in range(1, 11)]:
        raise ValueError("U01-U10 delivery package ids missing, duplicated or reordered")
    for group in groups["groups"]:
        if not group["evidence"] or not group.get("open_requirements") or group.get("full_acceptance") is not False:
            raise ValueError("agent delivery scope or unsigned status was changed")
        for ref in group["evidence"]:
            if "#" not in ref:
                raise ValueError(f"invalid agent test reference: {ref}")
            path, method = ref.rsplit("#", 1)
            file = root / path
            if not file.is_file() or "/src/" not in path or not path.endswith(".kt"):
                raise ValueError(f"agent test source missing: {ref}")
            if method not in set(re.findall(TEST_RE, file.read_text(encoding="utf-8"))):
                raise ValueError(f"agent test method missing: {ref}")

    print(f"Repository executable evidence index checked: {len(items)} mapped leaves "
          f"of {len(leaves)} functional leaves; external-only validation excluded; "
          "ZERO fully signed-off leaves.")
    return partial

def self_test():
    sample = "```text\n ROOT\n├─ A\n│  ├─ leaf1\n│  └─ leaf2\n└─ B\n   └─ leaf3\n```"
    assert len(leaves_from_tree(sample)) == 3
    assert leaves_from_tree(sample)[-1] == "B / leaf3"
    assert leaves_from_tree(sample.replace("leaf3", "leaf3 [来源 PR #610]"))[-1] == "B / leaf3"
    assert leaves_from_tree(sample.replace("leaf3", "leaf3 [PR #610]"))[-1] == "B / leaf3"
    # Subset validation: only evidence-backed leaves belong in the repo index;
    # duplicate/out-of-tree records must be rejected by validate().
    assert [leaf for leaf in leaves_from_tree(sample) if leaf.endswith("leaf2")] == ["A / leaf2"]

if __name__ == "__main__":
    try:
        self_test()
        if "--sync-open-gaps" in sys.argv:
            leaves = leaves_from_tree(TREE.read_text(encoding="utf-8"))
            matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
            expected = expected_open_gaps(ROOT, leaves, matrix["items"])
            (ROOT / "docs/ACCEPTANCE-OPEN-GAPS.json").write_text(
                json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print("已更新全部未映射功能及多代理缺口:", expected["pending_feature_count"])
        elif "--self-test" not in sys.argv:
            validate()
    except (ValueError, AssertionError, KeyError, OSError) as error:
        print(f"Acceptance evidence gate FAILED: {error}", file=sys.stderr)
        sys.exit(1)
