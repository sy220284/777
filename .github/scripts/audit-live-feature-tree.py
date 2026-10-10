#!/usr/bin/env python3
"""Inspect every active feature-tree leaf against CURRENT repository source.

This is an evidence inventory, not a claim of feature acceptance. Class/file presence,
a symbol reference, and a passing test are different kinds of evidence.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HTML = ROOT / "docs/FEATURE-TREE.zh-CN.html"
LEDGER = ROOT / "docs/FEATURE-CODE-EVIDENCE.zh-CN.md"
MATRIX = ROOT / "docs/ACCEPTANCE-MATRIX.json"
SOURCE_RE = re.compile(r"(^|/)src/main/.*\.(kt|java)$")
DECL = re.compile(
    r"^\s*(?:(?:private|public|internal|protected|override|suspend|inline|"
    r"open|abstract|tailrec|operator|expect|actual|data|enum)\s+)*"
    r"(?:fun|class|object|interface)\s+(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)\b",
    re.MULTILINE,
)
TEST = re.compile(r"@Test\s+fun\s+([A-Za-z_]\w*)\s*\(")
LINK = re.compile(r"/blob/[a-f0-9]{40}/([^)]+)\)")
# Pure lexical hints are search aids. A positive hint must never be described
# as a runtime/behavioral acceptance result.
HINTS = [
    ("会话", "session"), ("消息", "message"), ("聊天", "chat"), ("回复", "reply"),
    ("群", "group"), ("成员", "member"), ("人物", "persona"), ("角色", "character"),
    ("图集", "gallery"), ("故事", "story"), ("日记", "diary"), ("关系", "relationship"),
    ("记忆", "memory"), ("情绪", "emotion"), ("行为", "behavior"), ("主动", "proactive"),
    ("计划", "plan"), ("任务", "task"), ("目标", "goal"), ("待办", "todo"),
    ("团队", "team"), ("集群", "team"), ("代理", "agent"), ("执行", "execute"),
    ("成果", "artifact"), ("文件", "file"), ("工作区", "workspace"), ("历史", "history"),
    ("记录", "record"), ("证据", "evidence"), ("版本", "version"),
    ("下载", "download"), ("更新", "update"), ("安装", "install"),
    ("模型", "model"), ("档位", "reasoning"), ("温度", "temperature"),
    ("推理", "reasoning"), ("账号", "account"), ("授权", "auth"),
    ("权限", "permission"), ("工具", "tool"), ("技能", "skill"),
    ("插件", "plugin"), ("网页", "web"), ("搜索", "search"), ("通知", "notification"),
    ("无障碍", "accessibility"), ("虚拟屏", "virtual"), ("屏幕", "screen"),
    ("导入", "import"), ("导出", "export"), ("内容", "content"),
    ("路由", "route"), ("资源", "resource"), ("存储", "store"), ("调度", "schedule"),
    ("定时", "schedule"), ("自动化", "automation"), ("中断", "interrupt"),
    ("恢复", "recover"), ("重启", "restore"), ("副作用", "effect"),
    ("安全", "safe"), ("校验", "valid"), ("测试", "test"),
    ("构建", "build"), ("发布", "release"), ("输入", "input"), ("输出", "output"),
    ("消耗", "usage"), ("费用", "cost"), ("额度", "limit"), ("缓存", "cache"),
    ("连续", "continuity"), ("关联", "link"), ("身份", "identity"),
    ("创建", "create"), ("新建", "create"), ("删除", "delete"),
    ("保存", "save"), ("修改", "edit"), ("编辑", "edit"), ("查看", "view"),
    ("列表", "list"), ("启用", "enable"), ("停用", "disable"),
    ("启动", "start"), ("停止", "stop"), ("关闭", "close"),
    ("进度", "progress"), ("状态", "state"), ("预览", "preview"),
    ("错误", "error"), ("失败", "fail"), ("审批", "approval"),
    ("检查点", "checkpoint"), ("归档", "archive"), ("重生成", "regenerate"),
]

def tree_data() -> dict:
    html = HTML.read_text(encoding="utf-8")
    match = re.search(r"const data=(\{.*?\});const tree=", html, re.S)
    if not match:
        raise ValueError("Feature-tree HTML has no JSON data")
    return json.loads(match.group(1))

def leaves(root: dict) -> list[tuple[str, dict, list[str]]]:
    output = []
    def visit(node: dict, path: list[str]):
        current = path + [node["name"]]
        if not node["children"]:
            output.append((" / ".join(current[1:]), node, current))
        for child in node["children"]:
            visit(child, current)
    visit(root, [])
    return output

def parse_ledger() -> dict[str, tuple[str, str]]:
    records = {}
    for line in LEDGER.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"\| [3-5] \| (.*?) \| (.*?) \| (.*?) \|", line)
        if match is None:
            continue
        path, category, detail = match.groups()
        if path in records:
            raise ValueError("Duplicate feature evidence row: " + path)
        records[path] = (category, detail)
    return records

def code_index() -> tuple[dict[str, list[tuple[str,int]]], dict[str, str], dict[str, int]]:
    sources, declarations, references = {}, {}, collections.Counter()
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        if not SOURCE_RE.search(rel):
            continue
        src = path.read_text(encoding="utf-8", errors="replace")
        sources[rel] = src
        declarations[rel] = [(m.group(1), src.count("\n", 0, m.start()) + 1) for m in DECL.finditer(src)]
        for name in set(re.findall(r"\b[A-Za-z_]\w{2,}\b", src)):
            references[name] += 1
    return declarations, sources, references

def score(symbol: str, file_name: str, hints: list[str], own_file: bool, ref_count: int):
    name = symbol.lower()
    file_base = Path(file_name).stem.lower()
    matching = [hint for hint in hints if hint in name]
    if not matching:
        return 0
    return (9 * len(matching) + max(map(len, matching)) +
            (4 if own_file else 0) + (3 if ref_count >= 2 else 0) +
            (1 if any(hint in file_base for hint in hints) else 0))

def inspect_feature(path, node, category, detail, source_decls, source_text, references, tested):
    match = LINK.search(detail)
    nominated = match.group(1) if match else ""
    hints = list(dict.fromkeys(english for chinese, english in HINTS if chinese in node["name"]))
    direct_test = tested.get(path, [])
    result = {
        "feature": path, "state": "", "source": "", "symbol": "", "line": "",
        "tested": len(direct_test), "hint_count": len(hints),
    }
    if direct_test:
        for item in direct_test:
            file, symbol = item.rsplit("#", 1)
            src = ROOT / file
            if not src.is_file() or symbol not in TEST.findall(src.read_text(encoding="utf-8")):
                raise ValueError("Missing actual @Test symbol for " + path + ": " + item)
        result.update(state="TEST_DECLARED", source=direct_test[0].split("#")[0],
                      symbol=direct_test[0].rsplit("#", 1)[-1])
        return result
    if not nominated or not (ROOT / nominated).is_file():
        result["state"] = "MISSING_NOMINATED_FILE"
        return result

    # First inspect the owner's nominated file and then sibling production implementation
    # files. Never look in test sources as proof of production behavior.
    rel_parent = str(Path(nominated).parent) + "/"
    candidates = [nominated] + [
        name for name in source_decls
        if name != nominated and name.startswith(rel_parent)
    ]
    ranked = []
    for file in candidates:
        for symbol, lineno in source_decls.get(file, []):
            points = score(symbol, file, hints, file == nominated, references[symbol])
            if points:
                ranked.append((points, file, symbol, lineno))
    if ranked:
        ranked.sort(key=lambda item: (-item[0], item[1], item[2]))
        _, source, symbol, lineno = ranked[0]
        result.update(
            state="LEXICAL_SYMBOL_CANDIDATE",
            source=source, symbol=symbol, line=lineno,
        )
    else:
        declarations = source_decls.get(nominated, [])
        result.update(
            state="OWNER_FILE_ONLY",
            source=nominated,
            symbol=declarations[0][0] if declarations else "",
            line=declarations[0][1] if declarations else "",
        )
    return result

def run() -> dict:
    tree = tree_data()
    all_leaves = leaves(tree)
    ledger = parse_ledger()
    tested = {item["path"]: item["evidence"] for item in json.loads(MATRIX.read_text())["items"]}
    paths = {p for p, _, _ in all_leaves}
    if set(ledger) != paths:
        raise ValueError(f"Ledger differs from actual leaves: missing={len(paths-set(ledger))}, extra={len(set(ledger)-paths)}")
    if not set(tested).issubset(paths):
        raise ValueError("Acceptance index contains nonexistent features")
    declarations, code, references = code_index()
    report = [
        inspect_feature(path, node, *ledger[path], declarations, code, references, tested)
        for path, node, _ in all_leaves
    ]
    states = collections.Counter(x["state"] for x in report)
    output = ROOT / "feature-code-audit.tsv"
    with output.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=report[0].keys(), delimiter="\t")
        w.writeheader()
        w.writerows(report)
    print("SOURCE CODE AUDIT")
    print(f"Snapshot features: {len(report)}; Kotlin/Java source files indexed: {len(code)}")
    for name, count in sorted(states.items()):
        print(f"{name}: {count}")
    print("Direct tests mean declaration, not passing tests or feature acceptance.")
    print("Lexical symbol candidates are SEARCH LEADS, not verified behavior/call-graph links.")
    print("Evidence file: " + str(output))
    return {"features": len(report), "sources": len(code), "states": dict(states)}

def self_test():
    assert score("createNewSession", "LocalSessionStore.kt", ["session", "create"], True, 2) > 20
    assert score("unrelated", "foo.kt", ["session"], True, 2) == 0
    assert TEST.findall("@Test fun basicWorks() {\n} @Test\n fun anotherWorks() {") == ["basicWorks", "anotherWorks"]

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    self_test()
    if not args.self_test:
        result = run()
        if result["states"].get("MISSING_NOMINATED_FILE", 0):
            raise SystemExit("Feature source audit found missing nominated source files")
