#!/usr/bin/env python3
"""独立验证生成结果与仓库事实：校验失败则不得提交功能树。"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
MD = DOCS / "FEATURE-TREE.zh-CN.md"
HTML = DOCS / "FEATURE-TREE.zh-CN.html"
REVIEW = DOCS / "FEATURE-REVIEW.auto.json"
INDEX = DOCS / "FEATURE-SOURCE-INDEX.auto.json"
REG = DOCS / "FEATURE-TREE-SOURCE-REGISTRY.json"
MATRIX = DOCS / "ACCEPTANCE-MATRIX.json"
CODE = {".kt", ".java", ".kts", ".py", ".sh", ".ts", ".tsx", ".js", ".jsx",
        ".xml", ".yml", ".yaml", ".gradle", ".toml"}
SKIP = {".git", ".gradle", "build", "node_modules", ".venv", "__pycache__"}


def inspect():
    text = MD.read_text(encoding="utf-8")
    block = re.search(r"\x60{3}text\s*\n(.*?)\n\x60{3}", text, re.S)
    if not block:
        raise ValueError("Markdown 中缺少真实功能树")
    md_paths, stack = [], []
    root = block.group(1).splitlines()[0].strip()
    for line in block.group(1).splitlines()[1:]:
        found = re.match(r"^([│ ]*)([├└])─ (.+)$", line)
        if not found:
            if line.strip():
                raise ValueError("功能树出现不合法节点：" + line)
            continue
        depth = len(found.group(1)) // 3
        if len(found.group(1)) % 3 or depth > len(stack):
            raise ValueError("功能树层级不合法")
        title = re.sub(r"\s+\[(?:来源 )?PR #\d+[^\]]*\]$", "", found.group(3))
        stack = stack[:depth] + [title]
        md_paths.append(" / ".join(stack))

    html = HTML.read_text(encoding="utf-8")
    match = re.search(r"const data=(\{.*?\});const tree=", html, re.S)
    if not match:
        raise ValueError("HTML 未找到功能树数据")
    data = json.loads(match.group(1))
    if data["name"] != root:
        raise ValueError("Markdown 和 HTML 树根不一致")
    html_paths, leaf_paths = [], []
    def walk(node, stack):
        for child in node["children"]:
            if child["depth"] != len(stack) + 1:
                raise ValueError("HTML 功能层级不正确")
            if child["name"] != child["name"].strip():
                raise ValueError("HTML 存在无效功能名称")
            path = stack + [child["name"]]
            html_paths.append(" / ".join(path))
            if not child["children"]:
                leaf_paths.append(" / ".join(path))
            walk(child, path)
    walk(data, [])
    if md_paths != html_paths or len(set(html_paths)) != len(html_paths):
        raise ValueError("Markdown、HTML 功能节点未逐条一致或存在重复")

    index = json.loads(INDEX.read_text(encoding="utf-8"))
    review = json.loads(REVIEW.read_text(encoding="utf-8"))
    registry = json.loads(REG.read_text(encoding="utf-8"))
    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    sha = subprocess.check_output(["git", "rev-parse", "--short=12", "HEAD"],
                                  cwd=ROOT, text=True).strip()
    if index["source_snapshot"] != sha or review["snapshot"] != sha:
        raise ValueError("生成文件绑定的 SHA 与实际源码不同")
    if review["feature_nodes"] != len(html_paths) or review["functional_leaves"] != len(leaf_paths):
        raise ValueError("复核报告记录的功能节点数量有误")
    paths = [item["path"] for item in matrix["items"]]
    if (review["existing_executable_test_mappings"] != len(paths)
        or len(paths) != len(set(paths)) or any(v not in leaf_paths for v in paths)):
        raise ValueError("测试证据索引与功能树失配")
    sources = tests = files = 0
    workflows = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or any(p in SKIP for p in path.relative_to(ROOT).parts):
            continue
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix not in CODE and path.name != "gradlew":
            continue
        files += 1
        if "/src/main/" in "/" + relative and path.suffix in {".kt", ".java"}:
            sources += 1
        if "/src/test/" in "/" + relative or "/src/androidTest/" in "/" + relative:
            tests += 1
        if relative.startswith(".github/workflows/") and path.suffix in {".yml", ".yaml"}:
            workflows.append(relative)
    if (index["repository_code_file_count"] != files
        or index["production_source_count"] != sources
        or index["test_code_file_count"] != tests
        or index["workflow_files"] != sorted(workflows)
        or review["code_files_scanned"] != files
        or review["production_sources_scanned"] != sources):
        raise ValueError("扫描文件数量不符：扫描器可能遗漏代码或工作流")

    ids = set()
    for item in registry["nodes"]:
        ident = item["id"]
        if ident in ids or item["path"] not in html_paths:
            raise ValueError("已登记功能与当前功能树不一致")
        ids.add(ident)
        file = ROOT / item["source"]
        if not file.is_file() and not any(p["id"] == ident for p in review["pending_changes"]):
            raise ValueError("确认登记的源码已丢失，且无待核实声明")
        if file.is_file():
            text = file.read_text(encoding="utf-8", errors="replace")
            if item.get("entry") and not re.search(
                r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
                re.escape(item["entry"]) + r"\b", text
            ):
                raise ValueError("已确认的入口与当前源码不一致: " + ident)
            if item.get("test"):
                tpath, method = item["test"].rsplit("#", 1)
                file = ROOT / tpath
                if not file.is_file() or not re.search(
                    r"@Test\s+fun\s+" + re.escape(method) + r"\s*\(",
                    file.read_text(encoding="utf-8", errors="replace"),
                ):
                    raise ValueError("已确认的测试证据已丢失: " + ident)
    if index["verified_registrations"] != sorted(ids):
        raise ValueError("已核实身份与索引不一致")
    if len(review["pending_changes"]) != index["unverified_changes_count"]:
        raise ValueError("未确认候选计数不一致")
    print(f"功能树二次独立复核通过：扫描 {files} 个代码/配置文件，生产 {sources} 个，"
          f"功能节点 {len(html_paths)}、叶节点 {len(leaf_paths)}，"
          f"已登记源证据 {len(ids)}、待确认变更 {len(review['pending_changes'])}。"
          "确认仅是源码级别，不能代替真实功能验收。")


if __name__ == "__main__":
    try:
        inspect()
    except (OSError, ValueError, AssertionError, KeyError, TypeError) as error:
        print("功能树独立复核失败：" + str(error), file=sys.stderr)
        sys.exit(1)
