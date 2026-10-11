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
REVIEW = Path("docs/FEATURE-REVIEW.auto.json")
EVIDENCE = Path("docs/FEATURE-CODE-EVIDENCE.zh-CN.md")
RETIREMENTS = Path("docs/FEATURE-TREE-RETIREMENTS.json")
import hashlib
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
            for key in ("entry", "owner", "test", "action", "reason"):
                if key in entry:
                    markers[ident][key] = entry[key]
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



OWNER_IDS = {"SHELL", "CHAT", "WORK", "PROJECT", "AUTOMATION", "TOOLS", "SETTINGS"}
CHINESE = re.compile(r"[\u3400-\u9fff]")
SYMBOL = r"(?:fun|class|object|interface|val|var|enum\s+class)\s+%s\b"


def proof(root, marker, earlier):
    """核实独立的源码事实；不能把静态匹配冒充已通过的行为验收。"""
    if marker.get("action") == "retire":
        if earlier is None or marker.get("path") != earlier["path"]:
            return "退役项必须对应已登记功能的原完整路径"
        if len(CHINESE.findall(marker.get("reason", ""))) < 2:
            return "退役必须说明中文原因"
        return None
    if marker.get("action", "upsert") != "upsert":
        return "操作类型无效"
    if not CHINESE.search(parts(marker["path"])[-1]):
        return "功能末级名称必须为中文"
    if marker.get("owner") not in OWNER_IDS:
        return "功能归属不是架构登记的七个所有权模块"
    symbol = marker.get("entry", "")
    if not isinstance(symbol, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", symbol):
        return "缺少明确的生产入口符号 entry"
    file = root / marker["source"]
    content = file.read_text(encoding="utf-8", errors="replace")
    if not re.search(SYMBOL % re.escape(symbol), content):
        return "源码没有声明对应的真实入口 " + symbol
    test_ref = marker.get("test", "")
    if not isinstance(test_ref, str) or "#" not in test_ref:
        return "缺少真实测试方法 test"
    test_path, method = test_ref.rsplit("#", 1)
    if not (test_path.endswith(".kt") and ("/src/test/" in "/" + test_path
            or "/src/androidTest/" in "/" + test_path)):
        return "测试必须指向现有 Kotlin 测试目录"
    test_file = root / test_path
    if not test_file.is_file():
        return "指定测试文件不存在"
    test_content = test_file.read_text(encoding="utf-8", errors="replace")
    if not re.search(r"@Test\s+(?:fun\s+)" + re.escape(method) + r"\s*\(", test_content):
        return "不存在该 @Test 方法"
    if symbol not in test_content:
        return "测试源码没有直接引用所登记的生产入口符号"
    return None


def checked_registration(root, current, previous):
    verified, pending, retired = {}, [], {}
    for ident, item in sorted(current.items()):
        old = previous.get(ident)
        problem = proof(root, item, old)
        if problem:
            pending.append({"id": ident, "path": item["path"],
                            "status": "待复核，未写入功能树", "reason": problem})
        elif item.get("action") == "retire":
            retired[ident] = item
        else:
            verified[ident] = item
    for ident, item in previous.items():
        if ident not in current:
            pending.append({"id": ident, "path": item["path"],
                            "status": "待复核，未删除功能树",
                            "reason": "源码登记消失，无法单凭文件删除证明功能已退役；需明确退役声明"})
            verified[ident] = item
        elif ident not in verified and ident not in retired:
            # 有问题的变更不能自动撤销历史登记或旧功能。
            verified[ident] = item
    return verified, retired, pending


HISTORICAL_LINK = re.compile(
    r"https://github\.com/[^/]+/[^/]+/blob/[^/]+/"
    r"(app/src/(?:main|test|androidTest)/[^\s)#]+?\.(?:kt|java))(?:#|[)])"
)


def historical_code_evidence(root, leafpaths):
    """扫描历史证据与现行代码的反向差异；缺失单一入口不能证明功能已退役。"""
    file = root / EVIDENCE
    if not file.is_file():
        return [], []
    current = set(leafpaths)
    missing_entries, current_orphans = [], []
    for line in file.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| "):
            continue
        cells = [x.strip() for x in line.strip("|").split("|")]
        if len(cells) < 4 or not cells[0].isdigit() or " / " not in cells[1]:
            continue
        name, prior_level, raw = cells[1:4]
        ref_paths = sorted(set(HISTORICAL_LINK.findall(raw)))
        production = [x for x in ref_paths if "/src/main/" in "/" + x]
        tests = [x for x in ref_paths if "/src/test/" in "/" + x
                 or "/src/androidTest/" in "/" + x]
        missing = [x for x in production if not (root / x).is_file()]
        kept = [x for x in production if (root / x).is_file()]
        item = {
            "path": name, "historical_grade": prior_level,
            "historical_production_sources": production,
            "missing_production_sources": missing,
            "remaining_production_sources": kept,
            "historical_test_files": tests,
        }
        if name in current:
            if missing:
                item["assessment"] = "在现行树内：旧源码证据已失效，可能迁移或退役，须追踪新入口"
                current_orphans.append(item)
        else:
            if production and len(missing) == len(production):
                item["assessment"] = "不在现行树内且历史生产来源全部消失；高度疑似已退役"
            elif kept:
                item["assessment"] = "不在现行树内但原有实现仍存在；可能改名、合并或重新归类"
            else:
                item["assessment"] = "不在现行树内，历史证据不足以判定退役"
            missing_entries.append(item)
    return missing_entries, current_orphans


def approved_legacy_retirements(root, model, registered, previous, matrix):
    """旧功能下线必须有独立的明确审核记录及负向源码证据。"""
    file = root / RETIREMENTS
    if not file.is_file():
        return [], []
    config = json.loads(file.read_text(encoding="utf-8"))
    if config.get("version") != 1 or not isinstance(config.get("approvals"), list):
        raise ValueError("功能退役批准清单版本或格式错误")
    approved, pending = [], []
    records = config["approvals"]
    code_contents = [
        p.read_text(encoding="utf-8", errors="replace")
        for p in sorted((root / "app").rglob("*"))
        if p.is_file() and p.suffix in {".kt", ".java"} and "/src/main/" in "/" + p.relative_to(root).as_posix()
    ] if records else []
    seen = set()
    for item in records:
        if not isinstance(item, dict):
            raise ValueError("退役审批记录必须是结构化对象")
        name, source, symbol, reason = (item.get("path"), item.get("source"),
                                        item.get("symbol"), item.get("reason"))
        if not isinstance(name, str) or name in seen:
            raise ValueError("退役路径为空或重复")
        seen.add(name)
        try:
            path = parts(name)
        except ValueError as e:
            raise ValueError("退役路径不合法") from e
        if not all(isinstance(x, str) for x in (source, symbol, reason)):
            raise ValueError("退役证据和中文审核理由缺失")
        if not source.startswith("app/src/main/") or not source.endswith((".kt", ".java")):
            raise ValueError("退役源码证据必须是生产 Kotlin/Java 路径")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", symbol):
            raise ValueError("退役符号无效")
        if len(CHINESE.findall(reason)) < 4:
            raise ValueError("退役须给出不少于四个中文字的审核原因")
        if any(r["path"] == name for r in registered.values()):
            pending.append({"path": name, "reason": "当前源码仍显式登记此功能"})
            continue
        historical, _ = historical_code_evidence(root, [name])
        source_history = set()
        for evidence in historical:
            if evidence["path"] == name:
                source_history.update(evidence["historical_production_sources"])
        for old in previous.values():
            if old["path"] == name:
                source_history.add(old["source"])
        if source not in source_history:
            pending.append({"path": name, "reason": "审批的旧生产源码找不到历史源码台账或稳定登记依据"})
            continue
        declaration = re.compile(SYMBOL % re.escape(symbol))
        if any(declaration.search(text) for text in code_contents):
            pending.append({"path": name, "reason": "生产源码仍存在原入口声明，可能已经迁移"})
            continue
        if (root / source).is_file():
            body = (root / source).read_text(encoding="utf-8", errors="replace")
            if declaration.search(body):
                pending.append({"path": name, "reason": "历史生产源码仍含原入口"})
                continue
        test_references = [
            x for x in matrix["items"] if x["path"] == name
        ]
        if test_references:
            pending.append({"path": name, "reason": "此功能仍有关联验收映射，须先处理测试与替代归属"})
            continue
        node = find(model, path)
        if node is None:
            approved.append({"path": name, "status": "已不在现行功能树"})
        elif node["children"]:
            pending.append({"path": name, "reason": "分类仍有子功能，禁止批量删除"})
        else:
            approved.append({"path": name, "status": "入口消失、测试映射已清理，批准删除"})
    return approved, pending


def snapshot(root):
    # 采用源码内容指纹：生成文档提交会改变 Git SHA，但不会导致无穷重新生成。
    digest = hashlib.sha256()
    skips = {".git", ".gradle", "build", "node_modules", ".venv", "__pycache__"}
    suffixes = {".kt", ".java", ".kts", ".py", ".sh", ".ts", ".tsx",
                ".js", ".jsx", ".xml", ".yml", ".yaml", ".gradle", ".toml"}
    for file in sorted(root.rglob("*")):
        if not file.is_file() or any(x in skips for x in file.relative_to(root).parts):
            continue
        if file.relative_to(root).as_posix() == ".github/workflows/feature-tree-auto.yml":
            continue  # 工作流控制脚本自身的文档提交不改变实际功能源码指纹。
        if file.suffix not in suffixes and file.name != "gradlew":
            continue
        digest.update(file.relative_to(root).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(file.read_bytes()).digest())
    return digest.hexdigest()[:12]


def validate_view(root_model, html_text):
    old, _ = original_html(html_text)
    mdpaths = []
    def walk(node, path):
        for child in node["children"]:
            here = path + [clean(child["name"])]
            mdpaths.append(" / ".join(here))
            walk(child, here)
    walk(root_model, [])
    found = DATA.search(html_text)
    html_model = json.loads(found.group(2))
    htmlpaths = []
    def walk_html(node, path):
        here = path + [node["name"]] if node.get("depth") else path
        if here:
            htmlpaths.append(" / ".join(here))
        for child in node["children"]:
            walk_html(child, here)
    walk_html(html_model, [])
    if mdpaths != htmlpaths:
        raise ValueError("HTML/Markdown 节点、层级或顺序不一致，拒绝写入")
    return len(mdpaths)



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
    prev = (json.loads((root / REG).read_text(encoding="utf-8"))
            if (root / REG).exists() else {"version": 1, "nodes": []})
    if prev.get("version") != 1:
        raise ValueError("不支持的功能注册表版本")
    older = {item["id"]: item for item in prev["nodes"]}
    valid, retired, pending = checked_registration(root, registered, older)
    old_paths = {}
    deleted = []
    # 退役必须有明确的退役声明；登记消失只产生候选，不做破坏性变更。
    for ident, item in retired.items():
        if find(model, parts(item["path"])):
            delete(model, parts(item["path"]))
            deleted.append(parts(item["path"]))
            old_paths[item["path"]] = None
    for ident, item in sorted(valid.items()):
        was = older.get(ident)
        if was and was["path"] != item["path"]:
            if find(model, parts(was["path"])):
                delete(model, parts(was["path"]))
                deleted.append(parts(was["path"]))
                old_paths[was["path"]] = item["path"]
        if not was and item.get("previous_path") and item["previous_path"] != item["path"]:
            if find(model, parts(item["previous_path"])):
                delete(model, parts(item["previous_path"]))
                deleted.append(parts(item["previous_path"]))
                old_paths[item["previous_path"]] = item["path"]
        add(model, parts(item["path"]))
    for old in deleted:
        prune_empty(model, old)
    matrix_text = (root / MATRIX).read_text(encoding="utf-8")
    matrix = json.loads(matrix_text)
    legacy_approved, legacy_pending = approved_legacy_retirements(
        root, model, registered, older, matrix
    )
    for result in legacy_approved:
        if result["status"] != "入口消失、测试映射已清理，批准删除":
            continue
        path = parts(result["path"])
        delete(model, path)
        old_paths[result["path"]] = None
        prune_empty(model, path)
        for ident in list(valid):
            if valid[ident]["path"] == result["path"]:
                valid.pop(ident)
    all_leaves = leaves(model)
    if len(all_leaves) != len(set(all_leaves)):
        raise ValueError("功能树叶节点存在重复路径")
    historical_gaps, current_source_gaps = historical_code_evidence(root, all_leaves)
    pending.extend({
        "path": item["path"], "status": "待复核，禁止移除旧功能", "reason": item["reason"]
    } for item in legacy_pending)
    ranks = {name: i for i, name in enumerate(all_leaves)}
    if old_paths:
        updated = []
        for item in matrix["items"]:
            target = old_paths.get(item["path"], item["path"])
            if target is None:
                continue
            if target not in ranks:
                raise ValueError("旧测试证据不能映射新功能路径: " + target)
            updated.append({**item, "path": target})
        matrix["items"] = sorted(updated, key=lambda x: ranks[x["path"]])
        matrix_text = json.dumps(matrix, ensure_ascii=False, indent=2) + "\n"
    for item in matrix["items"]:
        if item["path"] not in ranks:
            raise ValueError("存在已失效的测试证据映射: " + item["path"])

    # 所有节点仍以中文产品名称呈现；旧有混合术语不因扫描被擅自更名。
    snap = snapshot(root)
    model["name"] = "777 神言神语（当前生产代码功能树）"
    md = md[:block.start(2)] + render(model) + md[block.end(2):]
    header = (
        f"代码核对基准：本次全量扫描的源码内容指纹 \x60{snap}\x60。扫描后确认的功能变更写入功能树；"
        "证据不足的候选变化保留待核实，不能视为已交付。\n\n"
        "说明：本图展示已登记的产品功能及归属。历史功能仍需按真实调用链逐项验收；"
        "源码符号、路由和测试存在仅构成局部证据，不证明全部功能可用。"
        "参考 [功能审计](FUNCTION-AUDIT.zh-CN.md)、"
        "[代码证据台账](FEATURE-CODE-EVIDENCE.zh-CN.md)、"
        "[自动复核结果](FEATURE-REVIEW.auto.json) 与"
        "[交互式树状图](FEATURE-TREE.zh-CN.html)。"
    )
    md = re.sub(
        r"(?<=# 777《神言神语》全功能架构树\n\n)[\s\S]*?(?=\n\n\x60{3}text\n)",
        header, md, count=1,
    )
    old, html_match = original_html(html)
    evidence_counts = {item["path"]: len(item["evidence"]) for item in matrix["items"]}
    structure = to_html(model, old, {v["path"]: v for v in valid.values()
                                    if (root / v["source"]).is_file()})
    def annotate(node, stack):
        for child in node["children"]:
            path = stack + [child["name"]]
            full = " / ".join(path)
            if not child["children"] and full in evidence_counts:
                child["testCount"] = evidence_counts[full]
            annotate(child, path)
    annotate(structure, [])
    dataset = json.dumps(structure, ensure_ascii=False, separators=(",", ":"))
    html = html[:html_match.start(2)] + dataset + html[html_match.end(2):]
    # 替换过期的主线/PR 快照说明，避免历史提交号伪装为最新。
    html = re.sub(r'(<p class="sub">按架构 3\.0 的真实所有权展开.*?)</p>',
                  f'<p class="sub">按架构 3.0 的实际功能归属展示 · 当前源码快照 {snap}</p>',
                  html, count=1)
    html = re.sub(
        r'<div class="meta">.*?</div>',
        f'<div class="meta"><span class="chip">已扫描代码 <b>{index["repository_code_file_count"]}</b></span>'
        f'<span class="chip">生产源码 <b>{index["production_source_count"]}</b></span>'
        f'<span class="chip">源码基准 <b>{snap}</b></span><span class="chip" id="total"></span></div>',
        html, count=1, flags=re.S,
    )
    html = re.sub(
        r'<p class="foot">.*?</p>',
        '<p class="foot">依据：<a href="ARCHITECTURE.md">架构 3.0</a> · '
        '<a href="FEATURE-TREE.zh-CN.md">中文功能树</a> · '
        '<a href="FEATURE-REVIEW.auto.json">源码复核结果</a> · '
        '节点为功能索引，不代表运行验收全部通过。</p>',
        html, count=1, flags=re.S,
    )
    html = re.sub(
        r'(<p class="sub">2026-10-10 核对：).*?(</p>)',
        f'<p class="sub">本图按照代码快照 {snap} 与现存登记刷新。'
        '未经证明的新增与退役变化不会直接写入节点；验收仍以真实测试和设备执行为准。</p>',
        html, count=1, flags=re.S,
    )
    # 对已经退出当前功能树的历史来源进行单独审计，不再冒充现行能力。
    html = re.sub(
        r'<p class="sub">本图按照代码快照 .*?</p>',
        f'<p class="sub">本图依据源码内容指纹 {snap} 复核；'
        f'历史台账中 {len(historical_gaps)} 项已不在现行树内，'
        f'当前树有 {len(current_source_gaps)} 项引用过时源码路径。'
        '未确认替代实现之前不依据单个文件缺失判断退役。'
        '<a href="FEATURE-REVIEW.auto.json">查看逐项反向核查结果</a>。</p>',
        html, count=1, flags=re.S,
    )
    # 旧版写死的功能数和核查结论不得被冒充当前扫描事实。
    html = re.sub(
        r'<p class="sub">源码核查级别：.*?</p>',
        f'<p class="sub">当前功能树末级 {len(all_leaves)} 项；其中 '
        f'{len(evidence_counts)} 项存在直接 Kotlin 测试引用，其余 '
        f'{len(all_leaves) - len(evidence_counts)} 项仍需逐功能核对调用链。'
        '源码登记通过不等于真实运行验收；<a href="FEATURE-REVIEW.auto.json">查看完整复核结果</a>。</p>',
        html, count=1, flags=re.S,
    )
    node_count = validate_view(model, html)
    review = {
        "version": 1,
        "snapshot": snap,
        "scope": "全部仓库代码文件、全部生产源码，按产品真实入口复核",
        "code_files_scanned": index["repository_code_file_count"],
        "production_sources_scanned": index["production_source_count"],
        "feature_nodes": node_count,
        "functional_leaves": len(all_leaves),
        "existing_executable_test_mappings": len(matrix["items"]),
        "leaf_evidence": [
            {"path": path, "verification": (
                "存在直接测试方法引用（仅部分证据）" if path in evidence_counts else
                "尚无直接测试映射，待逐功能验证"
            ), "test_reference_count": evidence_counts.get(path, 0)}
            for path in all_leaves
        ],
        "source_verified_registrations": len(valid),
        "explicit_retirements": len(retired),
        "changes_applied": [{"previous": previous, "current": current}
                            for previous, current in sorted(old_paths.items())],
        "pending_changes": pending,
        "obsolete_historical_evidence_count": len(historical_gaps),
        "obsolete_historical_evidence": historical_gaps,
        "current_tree_outdated_source_count": len(current_source_gaps),
        "current_tree_outdated_sources": current_source_gaps,
        "approved_legacy_retirements": legacy_approved,
        "legacy_retirements_requiring_review": legacy_pending,
        "verification_limits": (
            "源码入口、所有权、中文名称、@Test 引用与双视图一致性已静态检查；"
            "无法据此证明测试在真实设备或服务上的行为完整。"
            "没有声明和证据的历史节点继续保留原状。"
        ),
    }
    index["source_snapshot"] = snap
    index["verified_registrations"] = sorted(valid)
    index["unverified_changes_count"] = len(pending)
    index["obsolete_historical_evidence_count"] = len(historical_gaps)
    index["current_tree_outdated_source_count"] = len(current_source_gaps)
    normalized = [{k: v for k, v in item.items() if k not in {"previous_path", "action", "reason"}}
                  for item in sorted(valid.values(), key=lambda v: v["id"])
                  if (root / item["source"]).is_file()]
    return {
        MD: md, HTML: html, MATRIX: matrix_text,
        REG: json.dumps({"version": 1, "nodes": normalized}, ensure_ascii=False, indent=2) + "\n",
        INDEX: json.dumps(index, ensure_ascii=False, indent=2) + "\n",
        REVIEW: json.dumps(review, ensure_ascii=False, indent=2) + "\n",
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
        test_file = root / "app/src/test/java/sample/FeatureTest.kt"
        test_file.parent.mkdir(parents=True)
        test_file.write_text("class FeatureTest { @Test fun entryWorks() { Entry() } }\n", encoding="utf-8")
        def mark(path, action=None, evidence=True):
            payload = {"id": "feature.first", "path": path}
            if action == "retire":
                payload.update({"action": "retire", "reason": "功能确认退役"})
            elif evidence:
                payload.update({"entry": "Entry", "owner": "CHAT",
                                "test": "app/src/test/java/sample/FeatureTest.kt#entryWorks"})
            source.write_text("// FEATURE_TREE: " + json.dumps(payload, ensure_ascii=False) +
                              "\nclass Entry {}\n", encoding="utf-8")
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
        # 未核实的声明丢失只能产生候选，不得移除原功能。
        source.write_text("class Entry {}\n", encoding="utf-8")
        tentative = generate(root)
        assert find(parse(tentative[MD])[0], ["B", "新功能"]) is not None
        assert json.loads(tentative[REVIEW])["pending_changes"]
        mark("B / 新功能", action="retire")
        removed = generate(root)
        removed_tree, _ = parse(removed[MD])
        assert find(removed_tree, ["B", "新功能"]) is None
        assert json.loads(removed[MATRIX])["items"] == []
        assert json.loads(removed[REG])["nodes"] == []
        write_outputs(removed)
        mark("B / 新功能", evidence=False)
        rejected = generate(root)
        assert json.loads(rejected[REVIEW])["pending_changes"]
        assert find(parse(rejected[MD])[0], ["B", "新功能"]) is None

        # 已删除但历史有证据的旧功能：审批源真实存在历史档案，实际入口已消失。
        historical_url = (
            "https://github.com/example/repo/blob/123abc/"
            "app/src/main/java/sample/LegacyDead.kt#L1"
        )
        (root / EVIDENCE).write_text(
            "| 层级 | 功能完整路径 | 证据 | 代码 |\\n"
            "| 3 | B / 已有能力 | 专属源码路径已核 | "
            "[旧实现](" + historical_url + ") |\\n", encoding="utf-8"
        )
        approval = {"version": 1, "approvals": [{
            "path": "B / 已有能力",
            "source": "app/src/main/java/sample/LegacyDead.kt",
            "symbol": "LegacyDead", "reason": "已经撤销原有功能入口",
        }]}
        (root / RETIREMENTS).write_text(json.dumps(approval, ensure_ascii=False), encoding="utf-8")
        legacy = generate(root)
        assert find(parse(legacy[MD])[0], ["B", "已有能力"]) is None
        assert len(json.loads(legacy[REVIEW])["approved_legacy_retirements"]) == 1
        assert len(json.loads(legacy[REVIEW])["obsolete_historical_evidence"]) == 1
        write_outputs(legacy)
        assert generate(root)[MD] == (root / MD).read_text(encoding="utf-8")


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--check", action="store_true")
    cli.add_argument("--self-test", action="store_true")
    cli.add_argument("--preview", action="store_true")
    args = cli.parse_args()
    try:
        test()
        if args.self_test:
            print("功能树同步器自测通过")
            sys.exit(0)
        output = generate(ROOT)
        if args.preview:
            review = json.loads(output[REVIEW])
            print(f"复核预览：扫描 {review['code_files_scanned']} 个代码文件，"
                  f"节点 {review['feature_nodes']} 项，自动核定变更 {len(review['changes_applied'])} 项，"
                  f"待核实 {len(review['pending_changes'])} 项。预览未修改文件。")
            sys.exit(0)
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
        import traceback
        traceback.print_exc()
        print("功能树自动维护失败: " + str(error), file=sys.stderr)
        sys.exit(1)
