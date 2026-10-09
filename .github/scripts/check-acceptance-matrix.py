#!/usr/bin/env python3
"""Fail CI when the complete 424-leaf inventory drifts or claimed test evidence disappears.

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
    if len(leaves) != 424 or len(items) != 424:
        raise ValueError(f"expected 424 leaves and items, got {len(leaves)}, {len(items)}")
    if [item["path"] for item in items] != leaves or len(set(leaves)) != len(leaves):
        raise ValueError("feature tree differs from acceptance index; regenerate and review the index")
    partial = 0
    for item in items:
        if set(item) != {"path", "state", "evidence"}:
            raise ValueError(f"unexpected or missing acceptance fields: {item['path']}")
        if item["state"] not in ("unverified", "automated_partial"):
            raise ValueError(f"unsupported claimed acceptance status: {item['path']}")
        if not isinstance(item["evidence"], list):
            raise ValueError(f"non-list evidence: {item['path']}")
        if item["state"] == "automated_partial" and not item["evidence"]:
            raise ValueError(f"automated_partial lacks an executable test: {item['path']}")
        if item["state"] == "unverified" and item["evidence"]:
            raise ValueError(f"evidence must use automated_partial: {item['path']}")
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
    print(f"Acceptance inventory checked: {len(items)} leaves; "
          f"{partial} with partial executable evidence, {len(items)-partial} unverified; "
          "ZERO fully signed-off leaves.")
    return partial

def self_test():
    sample = "```text\n ROOT\n├─ A\n│  ├─ leaf1\n│  └─ leaf2\n└─ B\n   └─ leaf3\n```"
    assert len(leaves_from_tree(sample)) == 3
    assert leaves_from_tree(sample)[-1] == "B / leaf3"

if __name__ == "__main__":
    try:
        self_test()
        if "--self-test" not in sys.argv:
            validate()
    except (ValueError, AssertionError, KeyError, OSError) as error:
        print(f"Acceptance evidence gate FAILED: {error}", file=sys.stderr)
        sys.exit(1)
