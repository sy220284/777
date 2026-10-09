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
        title = re.sub(r"\s+\[PR #\d+.*$", "", m.group(3).strip())
        if depth > len(chain):
            raise ValueError("tree jumped over a parent")
        chain = chain[:depth] + [title]
        nodes.append((depth, " / ".join(chain)))
    return [path for i, (depth, path) in enumerate(nodes)
            if i + 1 == len(nodes) or nodes[i + 1][0] <= depth]

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
    print(f"Repository executable evidence index checked: {len(items)} mapped leaves "
          f"of {len(leaves)} functional leaves; external-only validation excluded; "
          "ZERO fully signed-off leaves.")
    return partial

def self_test():
    sample = "```text\n ROOT\n├─ A\n│  ├─ leaf1\n│  └─ leaf2\n└─ B\n   └─ leaf3\n```"
    assert len(leaves_from_tree(sample)) == 3
    assert leaves_from_tree(sample)[-1] == "B / leaf3"
    # Subset validation: only evidence-backed leaves belong in the repo index;
    # duplicate/out-of-tree records must be rejected by validate().
    assert [leaf for leaf in leaves_from_tree(sample) if leaf.endswith("leaf2")] == ["A / leaf2"]

if __name__ == "__main__":
    try:
        self_test()
        if "--self-test" not in sys.argv:
            validate()
    except (ValueError, AssertionError, KeyError, OSError) as error:
        print(f"Acceptance evidence gate FAILED: {error}", file=sys.stderr)
        sys.exit(1)
