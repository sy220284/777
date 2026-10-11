#!/usr/bin/env python3
"""全仓库生产源码扫描，维护带稳定 ID 的功能树节点与代码候选索引。"""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MD = Path("docs/FEATURE-TREE.zh-CN.md")
HTML = Path("docs/FEATURE-TREE.zh-CN.html")
MATRIX = Path("docs/ACCEPTANCE-MATRIX.json")
REG = Path("docs/FEATURE-TREE-SOURCE-REGISTRY.json")
INDEX = Path("docs/FEATURE-SOURCE-INDEX.auto.json")
BLOCK = re.compile(r"(\x60{3}text\s*\n)(.*?)(\n\x60{3})", re.S)
ROW = re.compile(r"^([│ ]*)([├└])─ (.+)$")
DATA = re.compile(r"(const data=)(\{.*?\})(;const tree=)", re.S)
TAG = re.compile(r"^\s*//\s*FEATURE_TREE:\s*(\{.*\})\s*$", re.M)
SUFFIX = re.compile(r"\s+\[(?:来源 )?PR #\d+[^\]]*\]$")


def clean(name):
    return SUFFIX.sub("", name)


def parts(value):
    values = value.split(" / ") if isinstance(value, str) else value
    if not isinstance(values, list) or not values or any(
        not isinstance(v, str) or not v.strip() for v in values
    ):
        raise ValueError("功能路径不能为空，格式为 父级 / 子级 / 功能")
    return [v.strip() for v in values]


def parse(text):
    m = BLOCK.search(text)
    if not m:
        raise ValueError("找不到功能树正文")
    lines = m.group(2).splitlines()
    if not lines:
        raise ValueError("空功能树")
    root = {"name": lines[0].strip(), "children": []}
    stack = [root]
    for line in lines[1:]:
        if not line.strip():
            continue
        x = ROW.match(line)
        if not x or len(x.group(1)) % 3:
            raise ValueError("树结构无效: " + line)
        depth = len(x.group(1)) // 3 + 1
        if depth > len(stack):
            raise ValueError("树层级跳跃: " + line)
        stack = stack[:depth]
        node = {"name": x.group(3), "children": []}
        stack[-1]["children"].append(node)
        stack.append(node)
    return root, m


def render(root):
    lines = [" " + root["name"]]
    def walk(node, prefix):
        for i, child in enumerate(node["children"]):
            last = i == len(node["children"]) - 1
            lines.append(prefix + ("└─ " if last else "├─ ") + child["name"])
            walk(child, prefix + ("   " if last else "│  "))
    walk(root, "")
    return "\n".join(lines)


def find(root, path):
    node = root
    for name in path:
        node = next((x for x in node["children"] if clean(x["name"]) == name), None)
        if node is None:
            return None
    return node


def delete(root, path):
    parent = find(root, path[:-1])
    if parent is None:
        raise ValueError("找不到功能父级: " + " / ".join(path))
    node = next((x for x in parent["children"] if clean(x["name"]) == path[-1]), None)
    if node is None or node["children"]:
        raise ValueError("禁止删除不存在或含子功能的节点: " + " / ".join(path))
    parent["children"].remove(node)


def prune_empty(root, path):
    # 在新节点写入后清理旧空分类，避免同级改名导致原分类位置跳动。
    for depth in range(len(path) - 1, 0, -1):
        current = path[:depth]
        node = find(root, current)
        if node is None or node["children"]:
            break
        parent = find(root, current[:-1])
        parent["children"].remove(node)


def add(root, path):
    node = root
    for name in path:
        child = next((x for x in node["children"] if clean(x["name"]) == name), None)
        if child is None:
            child = {"name": name, "children": []}
            node["children"].append(child)
        node = child


def leaves(root):
    result = []
    def walk(node, path):
        for child in node["children"]:
            cur = path + [clean(child["name"])]
            if not child["children"]:
                result.append(" / ".join(cur))
            walk(child, cur)
    walk(root, [])
    return result


def scan(root):
    markers, screens, routes = {}, [], []
    production, tested, scanned = 0, 0, 0
    workflow_files = []
    code_suffixes = {".kt", ".java", ".kts", ".py", ".sh", ".ts", ".tsx",
                     ".js", ".jsx", ".xml", ".yml", ".yaml", ".gradle", ".toml"}
    for file in sorted(root.rglob("*")):
        if not file.is_file():
            continue
        path = file.relative_to(root).as_posix()
        if any(part in {".git", ".gradle", "build", "node_modules", ".venv", "__pycache__"}
               for part in file.relative_to(root).parts):
            continue
        if file.suffix not in code_suffixes and file.name not in {"gradlew"}:
            continue
        scanned += 1
        if path.startswith(".github/workflows/") and file.suffix in {".yml", ".yaml"}:
            workflow_files.append(path)
        text = file.read_text(encoding="utf-8", errors="replace")
        is_production = ("/src/main/" in "/" + path and file.suffix in {".kt", ".java"})
        if "/src/test/" in "/" + path or "/src/androidTest/" in "/" + path:
            tested += 1
        if not is_production:
            continue
        production += 1
        if file.name == "LocalFeatureCatalog.kt":
            match = re.search(r"enum class LocalFeatureRoute\s*\{([^}]*)\}", text, re.S)
            if match:
                routes = re.findall(r"^\s*([A-Z][A-Z_0-9]+)\s*,?\s*$", match.group(1), re.M)
        if "/ui/" in path:
            for item in re.finditer(
                r"@Composable\s+(?:(?:internal|private|public)\s+)?fun\s+(\w*Screen\w*)\s*\(", text
            ):
                screens.append({"symbol": item.group(1), "source": path})
        candidate_lines = [line for line in text.splitlines()
                           if re.match(r"^\s*//\s*FEATURE_TREE\s*:", line)]
        matches = list(TAG.finditer(text))
        if len(candidate_lines) != len(matches):
            raise ValueError(f"{path}: FEATURE_TREE 注释语法错误，禁止将其视为已删除")
        for tag in matches:
            try:
                entry = json.loads(tag.group(1))
                ident = entry["id"]
                fullpath = " / ".join(parts(entry["path"]))
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(f"{path}: 功能声明无效: {exc}") from exc
            if not isinstance(ident, str) or not re.fullmatch(r"[a-z][a-z0-9._-]*", ident):
                raise ValueError(f"{path}: id 必须是稳定小写标识")
            if ident in markers:
                raise ValueError("功能 ID 重复: " + ident)
            markers[ident] = {"id": ident, "path": fullpath, "source": path}
            if "previous_path" in entry:
                markers[ident]["previous_path"] = " / ".join(parts(entry["previous_path"]))
    return markers, {
        "version": 1, "scope": "repository_code_and_all_production_sources",
        "repository_code_file_count": scanned,
        "production_source_count": production, "test_code_file_count": tested,
        "workflow_files": workflow_files,
        "registered_routes": routes,
        "candidate_ui_screens": sorted(screens, key=lambda x: (x["symbol"], x["source"])),
        "semantic_registration_count": len(markers),
        "warning": "代码候选不等于产品功能或功能验收，功能节点仅依据源码中 FEATURE_TREE 声明变更。",
    }


def original_html(html):
    match = DATA.search(html)
    if not match:
        raise ValueError("交互 HTML 未找到数据结构")
    old = json.loads(match.group(2))
    existing = {}
    def walk(node, path):
        current = path + ([clean(node["name"])] if node.get("depth") else [])
        if current:
            existing[" / ".join(current)] = {
                "paths": node.get("paths", []), "pr": node.get("pr")
            }
        for child in node["children"]:
            walk(child, current)
    walk(old, [])
    return existing, match


def to_html(node, old, marked, path=()):
    depth = len(path)
    here = path + (clean(node["name"]),) if path else ()
    # root has no logical path
    if depth == 0:
        children = [to_html(n, old, marked, (clean(n["name"]),)) for n in node["children"]]
        return {"name": node["name"], "children": children, "depth": 0, "paths": []}
    full = " / ".join(path)
    meta = old.get(full, {})
    value = {"name": clean(node["name"]), "children": [
        to_html(n, old, marked, (*path, clean(n["name"]))) for n in node["children"]
    ], "depth": depth, "paths": [marked[full]["source"]] if full in marked else meta.get("paths", [])}
    source_pr = re.search(r"\[来源 PR #(\d+)\]", node["name"])
    if source_pr:
        value["pr"] = int(source_pr.group(1))
    elif meta.get("pr"):
        value["pr"] = meta["pr"]
    return value


def generate(root):
    md = (root / MD).read_text(encoding="utf-8")
    html = (root / HTML).read_text(encoding="utf-8")
    model, block = parse(md)
    registered, index = scan(root)
    prev = json.loads((root / REG).read_text(encoding="utf-8")) if (root / REG).exists() else {"version": 1, "nodes": []}
    if prev.get("version") != 1:
        raise ValueError("不支持的功能注册表版本")
    older = {item["id"]: item for item in prev["nodes"]}
    changed_paths = {}
    removed_paths = []
    for ident, item in older.items():
        updated = registered.get(ident)
        if updated is None or updated["path"] != item["path"]:
            if find(model, parts(item["path"])):
                delete(model, parts(item["path"]))
                removed_paths.append(parts(item["path"]))
                changed_paths[item["path"]] = updated["path"] if updated else None
    for ident, item in sorted(registered.items()):
        if ident not in older and item.get("previous_path") and item["previous_path"] != item["path"]:
            if find(model, parts(item["previous_path"])):
                delete(model, parts(item["previous_path"]))
                removed_paths.append(parts(item["previous_path"]))
                changed_paths[item["previous_path"]] = item["path"]
        add(model, parts(item["path"]))
    for previous_path in removed_paths:
        prune_empty(model, previous_path)
    leafpaths = leaves(model)
    if len(leafpaths) != len(set(leafpaths)):
        raise ValueError("功能树叶节点重复")
    # 无语义节点变化时保持两份旧图原样，避免格式化引起无意义的大规模差异。
    semantic_diff = bool(registered or older)
    if semantic_diff:
        md = md[:block.start(2)] + render(model) + md[block.end(2):]
        old, match = original_html(html)
        dataset = json.dumps(
            to_html(model, old, {v["path"]: v for v in registered.values()}),
            ensure_ascii=False, separators=(",", ":")
        )
        html = html[:match.start(2)] + dataset + html[match.end(2):]
    matrix_text = (root / MATRIX).read_text(encoding="utf-8")
    if changed_paths:
        matrix = json.loads(matrix_text)
        ranks = {name: i for i, name in enumerate(leafpaths)}
        items = []
        for item in matrix["items"]:
            location = changed_paths.get(item["path"], item["path"])
            if location is None:
                continue
            if location not in ranks:
                raise ValueError("测试证据无法映射新功能位置: " + location)
            items.append({**item, "path": location})
        matrix["items"] = sorted(items, key=lambda x: ranks[x["path"]])
        matrix_text = json.dumps(matrix, ensure_ascii=False, indent=2) + "\n"
    normalized = [{k: v for k, v in item.items() if k != "previous_path"}
                  for item in sorted(registered.values(), key=lambda v: v["id"])]
    return {
        MD: md, HTML: html, MATRIX: matrix_text,
        REG: json.dumps({"version": 1, "nodes": normalized}, ensure_ascii=False, indent=2) + "\n",
        INDEX: json.dumps(index, ensure_ascii=False, indent=2) + "\n",
    }


def test():
    fence = chr(96) * 3
    text = fence + "text\n ROOT\n├─ A\n│  ├─ 甲\n│  └─ 乙\n└─ B\n   └─ 丙\n" + fence
    model, _ = parse(text)
    assert render(model) == " ROOT\n├─ A\n│  ├─ 甲\n│  └─ 乙\n└─ B\n   └─ 丙"
    delete(model, ["A", "乙"])
    add(model, ["B", "丁"])
    assert leaves(model) == ["A / 甲", "B / 丙", "B / 丁"]


    # 完整模拟从「新增登记→改名/移入新父级→删除」的三轮真实文件同步。
    from tempfile import TemporaryDirectory
    with TemporaryDirectory() as directory:
        root = Path(directory)
        (root / MD).parent.mkdir(parents=True)
        fixture = fence + "text\n ROOT\n├─ A\n│  └─ 旧功能\n└─ B\n   └─ 已有能力\n" + fence + "\n"
        (root / MD).write_text(fixture, encoding="utf-8")
        initial, _ = parse(fixture)
        init_html = '<script>const data=' + json.dumps(
            to_html(initial, {}, {}), ensure_ascii=False, separators=(",", ":")
        ) + ';const tree=1;</script>'
        (root / HTML).write_text(init_html, encoding="utf-8")
        test_matrix = {"version": 2, "items": [
            {"path": "A / 旧功能", "state": "automated_partial", "evidence": ["test.kt#test"]}
        ]}
        (root / MATRIX).write_text(json.dumps(test_matrix, ensure_ascii=False), encoding="utf-8")
        source = root / "app/src/main/java/sample/Feature.kt"
        source.parent.mkdir(parents=True)
        def mark(path):
            payload = json.dumps({"id": "feature.first", "path": path}, ensure_ascii=False)
            source.write_text("// FEATURE_TREE: " + payload + "\nclass Entry {}\n", encoding="utf-8")
        def write_outputs(outputs):
            for filename, value in outputs.items():
                (root / filename).write_text(value, encoding="utf-8")

        mark("A / 旧功能")
        write_outputs(generate(root))
        assert json.loads((root / REG).read_text(encoding="utf-8"))["nodes"][0]["source"].endswith("Feature.kt")
        assert generate(root)[MD] == (root / MD).read_text(encoding="utf-8")
        mark("B / 新功能")
        renamed = generate(root)
        renamed_tree, _ = parse(renamed[MD])
        assert find(renamed_tree, ["A", "旧功能"]) is None
        assert find(renamed_tree, ["A"]) is None  # 退役分类不得留下空壳
        assert find(renamed_tree, ["B", "新功能"]) is not None
        assert json.loads(renamed[MATRIX])["items"][0]["path"] == "B / 新功能"
        assert "新功能" in renamed[HTML] and "旧功能" not in renamed[HTML]
        write_outputs(renamed)
        source.write_text("class Entry {}\n", encoding="utf-8")
        removed = generate(root)
        removed_tree, _ = parse(removed[MD])
        assert find(removed_tree, ["B", "新功能"]) is None
        assert json.loads(removed[MATRIX])["items"] == []
        assert json.loads(removed[REG])["nodes"] == []


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--check", action="store_true")
    cli.add_argument("--self-test", action="store_true")
    args = cli.parse_args()
    try:
        test()
        if args.self_test:
            print("功能树同步器自测通过")
            sys.exit(0)
        output = generate(ROOT)
        stale = []
        for file, content in output.items():
            target = ROOT / file
            if not target.exists() or target.read_text(encoding="utf-8") != content:
                stale.append(str(file))
                if not args.check:
                    target.write_text(content, encoding="utf-8")
        print(f"完成全量扫描；需要同步 {len(stale)} 个文件: {', '.join(stale)}")
        if args.check and stale:
            sys.exit(1)
    except (OSError, ValueError, AssertionError, KeyError, TypeError) as error:
        print("功能树自动维护失败: " + str(error), file=sys.stderr)
        sys.exit(1)
