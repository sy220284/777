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
LEGACY_IDENTITIES = Path("docs/FEATURE-TREE-LEGACY-IDENTITIES.json")
EXPLICIT_EVIDENCE = Path("docs/FEATURE-TREE-EXPLICIT-EVIDENCE.json")
WORKFLOW_EVIDENCE = Path("docs/FEATURE-TREE-WORKFLOW-EVIDENCE.json")
EXISTENCE_TRIAGE = Path("docs/FEATURE-EXISTENCE-TRIAGE.json")
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
    source_fingerprints = []
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
        source_fingerprints.append({
            "path": path, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()[:16]
        })
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
        "production_file_fingerprints": source_fingerprints,
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
        if len(CHINESE.findall(marker.get("reason", ""))) < 4:
            return "退役必须说明至少四个汉字的原因"
        symbol = earlier.get("entry")
        if not symbol:
            return "原登记缺少可核查的实现入口，禁止直接退役"
        prod = root / "app"
        declaration = re.compile(SYMBOL % re.escape(symbol))
        mention = re.compile(r"\b" + re.escape(symbol) + r"\b")
        for file in prod.rglob("*"):
            if not file.is_file() or file.suffix not in {".kt", ".java"}:
                continue
            relative = file.relative_to(root).as_posix()
            if "/src/main/" in "/" + relative and declaration.search(
                file.read_text(encoding="utf-8", errors="replace")
            ):
                return "其他生产源码仍声明旧实现入口，禁止退役"
            if ("/src/test/" in "/" + relative or "/src/androidTest/" in "/" + relative) and mention.search(
                file.read_text(encoding="utf-8", errors="replace")
            ):
                return "测试代码仍使用旧实现入口，禁止退役"
        matrix_file = root / MATRIX
        if matrix_file.exists() and any(
            item["path"] == earlier["path"]
            for item in json.loads(matrix_file.read_text(encoding="utf-8"))["items"]
        ):
            return "旧功能还有测试验收映射，须先将测试迁移到接管功能或完成审核"
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


def verified_workflow_evidence(root, leafpaths):
    """只验证具体CI/发布作业和命令确实存在，不推断实际执行成功。"""
    source = root / WORKFLOW_EVIDENCE
    if not source.is_file():
        return {}
    manifest = json.loads(source.read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or not isinstance(manifest.get("items"), list):
        raise ValueError("工作流证据索引格式无效")
    result = {}
    for item in manifest["items"]:
        name = item.get("path")
        if not isinstance(name, str) or name in result or name not in leafpaths:
            raise ValueError("工作流证据引用重复或不存在的功能")
        if not isinstance(item.get("grade"), str) or "静态" not in item["grade"]:
            raise ValueError("工作流证据必须明确为静态证据")
        sources = item.get("sources")
        if not isinstance(sources, list) or not sources:
            raise ValueError("工作流证据缺少明确CI任务来源")
        checked = []
        for entry in sources:
            path, job, literals = entry.get("path"), entry.get("job"), entry.get("literals")
            if (not isinstance(path, str) or not path.startswith(".github/workflows/")
                    or not path.endswith((".yml", ".yaml"))
                    or ".." in Path(path).parts or not (root / path).is_file()):
                raise ValueError("工作流证据引用了不存在或非法的YAML文件")
            if not isinstance(job, str) or not re.fullmatch(r"[a-z][\w-]*", job):
                raise ValueError("工作流证据的job身份无效")
            if not isinstance(literals, list) or not literals or any(
                not isinstance(x, str) or not x.strip() for x in literals
            ):
                raise ValueError("工作流证据缺少具体命令、工具或步骤")
            body = (root / path).read_text(encoding="utf-8")
            candidates = list(re.finditer(r"^  ([A-Za-z][\w-]*):[ \t]*(?:#.*)?$", body, re.M))
            matches = [m for m in candidates if m.group(1) == job]
            if len(matches) != 1:
                raise ValueError("未找到唯一的工作流作业: " + path + "#" + job)
            start = matches[0].end()
            next_job = next((m for m in candidates if m.start() > matches[0].start()), None)
            task = body[start:next_job.start()] if next_job else body[start:]
            for literal in literals:
                if literal not in task:
                    raise ValueError("工作流作业缺少预期命令：" + path + "#" + job + " / " + literal)
            checked.append({"path": path, "job": job, "literals": literals})
        result[name] = {"grade": item["grade"], "sources": checked}
    return result


def legacy_leaf_evidence(root, leafpaths, matrix):
    """旧台账只提供证据线索：文件、具名声明、测试映射分开复核。"""
    historical = {}
    if (root / EVIDENCE).is_file():
        for line in (root / EVIDENCE).read_text(encoding="utf-8").splitlines():
            if not line.startswith("| "):
                continue
            cells = [x.strip() for x in line.strip("|").split("|")]
            if len(cells) < 4 or not cells[0].isdigit() or " / " not in cells[1]:
                continue
            path, grade, raw = cells[1:4]
            paths = sorted(set(HISTORICAL_LINK.findall(raw)))
            sources = [x for x in paths if "/src/main/" in "/" + x]
            historical[path] = (grade, sources, raw)
    explicit = {}
    file = root / EXPLICIT_EVIDENCE
    if file.is_file():
        manifest = json.loads(file.read_text(encoding="utf-8"))
        if manifest.get("version") != 1 or not isinstance(manifest.get("items"), list):
            raise ValueError("手工源码证据索引格式无效")
        for item in manifest["items"]:
            name = item.get("path")
            if name in explicit or name not in leafpaths:
                raise ValueError("具名源码证据引用重复或不存在的功能路径")
            verified = []
            for evidence in item.get("sources", []):
                source = evidence.get("path")
                if (not isinstance(source, str)
                        or "/src/main/" not in "/" + source
                        or ".." in Path(source).parts
                        or not source.endswith((".kt", ".java"))):
                    raise ValueError("手工证据来源不是合法的生产源码")
                file = root / source
                if not file.is_file():
                    raise ValueError("手工证据引用的生产源码不存在: " + source)
                body = file.read_text(encoding="utf-8", errors="replace")
                names = evidence.get("symbols")
                if not isinstance(names, list) or not names:
                    raise ValueError("必须提供真实存在的具名代码声明")
                for name_symbol in names:
                    if not isinstance(name_symbol, str) or not re.search(
                        SYMBOL % re.escape(name_symbol), body
                    ):
                        raise ValueError("手工证据未找到真实源码声明: " + str(name_symbol))
                for literal in evidence.get("literals", []):
                    if not isinstance(literal, str) or literal not in body:
                        raise ValueError("手工证据约定的工具调用分支不存在: " + str(literal))
                verified.append({"path": source, "symbols": names})
            test_candidates = []
            for candidate in item.get("tests", []):
                test_path, method, reference = (
                    candidate.get("path"), candidate.get("method"), candidate.get("references")
                )
                if (not isinstance(test_path, str) or ".." in Path(test_path).parts
                    or not test_path.endswith(".kt")
                    or not ("/src/test/" in "/" + test_path
                            or "/src/androidTest/" in "/" + test_path)
                    or not isinstance(method, str) or not re.fullmatch(r"[A-Za-z_]\w*", method)
                    or not isinstance(reference, str)
                    or not re.fullmatch(r"[A-Za-z_]\w*", reference)
                    or not (root / test_path).is_file()):
                    raise ValueError("候选功能测试引用必须是已存在的真实Kotlin测试")
                actual_test = (root / test_path).read_text(encoding="utf-8")
                if (not re.search(r"@Test\s+fun\s+" + re.escape(method) + r"\s*\(", actual_test)
                    or not re.search(r"\b" + re.escape(reference) + r"\b", actual_test)):
                    raise ValueError("测试方法或实现引用并未出现在声明的真实测试文件")
                if not any(reference in src["symbols"] for src in verified):
                    raise ValueError("候选测试的实现引用没有对应已经核实的生产源码")
                test_candidates.append({"path": test_path, "method": method,
                                        "references": reference,
                                        "status": "仅发现测试源码，未验证CI是否执行通过"})
            scope = item.get("scope", "")
            if len(CHINESE.findall(scope)) < 4:
                raise ValueError("手工证据必须用中文解释静态验证的范围")
            explicit[item["path"]] = {"sources": verified, "scope": scope,
                                      "tests": test_candidates}
    workflow = verified_workflow_evidence(root, leafpaths)
    mapped = {item["path"]: len(item["evidence"]) for item in matrix["items"]}
    result = []
    for name in leafpaths:
        grade, sources, raw = historical.get(name, (None, [], ""))
        existing = [x for x in sources if (root / x).is_file()]
        missing = [x for x in sources if x not in existing]
        symbols = []
        for label in re.findall(r"\[([^\]]+)\]\(https://github\.com/[^)]+\)", raw):
            if " · " not in label:
                continue
            symbol = label.rsplit(" · ", 1)[-1].strip()
            if not re.fullmatch(r"[A-Za-z_]\w*", symbol):
                continue
            declared = any(
                re.search(SYMBOL % re.escape(symbol),
                          (root / src).read_text(encoding="utf-8", errors="replace"))
                for src in existing
            )
            symbols.append({"symbol": symbol, "declaration_found": declared})
        manual = explicit.get(name)
        workflow_anchor = workflow.get(name)
        if manual:
            for source in manual["sources"]:
                if source["path"] not in existing:
                    existing.append(source["path"])
                for symbol in source["symbols"]:
                    old_symbol = next((x for x in symbols if x["symbol"] == symbol), None)
                    if old_symbol is not None:
                        old_symbol["declaration_found"] = True
                    else:
                        symbols.append({"symbol": symbol, "declaration_found": True})
        test_count = mapped.get(name, 0)
        if missing and not existing and not test_count:
            grade_now = "旧源码已失效，需核实迁移或退役"
        elif test_count and any(x["declaration_found"] for x in symbols):
            grade_now = "真实测试映射及具名源码声明（静态）"
        elif test_count:
            grade_now = "真实测试映射（仅局部）"
        elif manual and manual["tests"] and any(x["declaration_found"] for x in symbols):
            grade_now = "具名源码及仪表测试声明存在（静态，未执行）"
        elif any(x["declaration_found"] for x in symbols):
            grade_now = "具名源码声明存在（静态，待调用链复核）"
        elif workflow_anchor:
            grade_now = workflow_anchor["grade"]
        elif existing and grade and "候选" not in grade:
            grade_now = "历史生产文件仍存在（待入口复核）"
        elif existing:
            grade_now = "候选生产文件仍存在（不足以证明功能）"
        else:
            grade_now = "没有可确认的现行入口证据"
        result.append({
            "path": name, "verification": grade_now,
            "test_reference_count": test_count,
            "historical_grade": grade,
            "existing_production_sources": existing,
            "missing_production_sources": missing,
            "named_symbols": symbols,
            "explicit_source_anchor": bool(manual),
            "explicit_static_scope": manual["scope"] if manual else None,
            "explicit_test_candidates": manual["tests"] if manual else [],
            "workflow_evidence": workflow_anchor["sources"] if workflow_anchor else [],
        })
    return result


def ambiguous_source_anchors(root, entries):
    """仅统计某个文件中实际存在声明的符号，不能把一个符号虚构到所有相关文件。"""
    cached = {}
    assignments = {}
    for record in entries:
        path = record["path"]
        for filename in record.get("existing_production_sources", []):
            if filename not in cached:
                file = root / filename
                cached[filename] = (file.read_text(encoding="utf-8", errors="replace")
                                    if file.is_file() else "")
            body = cached[filename]
            for symbol in record.get("named_symbols", []):
                if not symbol.get("declaration_found"):
                    continue
                name = symbol["symbol"]
                if re.search(SYMBOL % re.escape(name), body):
                    assignments.setdefault((filename, name), set()).add(path)
    return [{"source": src, "symbol": name, "features": sorted(paths)}
            for (src, name), paths in sorted(assignments.items())
            if len(paths) > 1]


def historical_identity_review(root, leafpaths, entries):
    """静态身份台账只用于溯源；缺失入口进入待核，不授权任何语义修改。"""
    manifest = root / LEGACY_IDENTITIES
    if not manifest.is_file():
        return {}, [], []
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("version") != 1 or not isinstance(data.get("bindings"), list):
        raise ValueError("静态功能身份登记格式无效")
    paths = set(leafpaths)
    identities = {}
    known_ids, known_paths, known_symbols = set(), set(), set()
    stale = []
    for item in data["bindings"]:
        if not isinstance(item, dict):
            raise ValueError("静态身份记录格式无效")
        ident, path, source, entry = (
            item.get("id"), item.get("path"), item.get("source"), item.get("entry")
        )
        if not isinstance(ident, str) or not re.fullmatch(r"existing\.\d{4,}", ident):
            raise ValueError("静态身份缺少稳定 existing.NNNN 编号")
        if ident in known_ids or path in known_paths:
            raise ValueError("静态身份重复编号或重复中文功能路径")
        known_ids.add(ident)
        known_paths.add(path)
        if not isinstance(path, str) or not CHINESE.search(parts(path)[-1]):
            raise ValueError("静态身份的产品名称必须为中文")
        if not isinstance(source, str) or not re.fullmatch(
            r"[^./][\w./-]*/src/main/[\w./-]+\.(?:kt|java)", source
        ) or ".." in Path(source).parts:
            raise ValueError("静态身份登记无效的生产源码路径")
        if not isinstance(entry, str) or not re.fullmatch(r"[A-Za-z_]\w*", entry):
            raise ValueError("静态身份登记缺少入口符号")
        if item.get("evidence") != "static_declaration_only":
            raise ValueError("静态身份不能冒充已通过运行验收")
        if item.get("owner") not in OWNER_IDS | {"SHARED"}:
            raise ValueError("静态身份归属标识无效")
        if (source, entry) in known_symbols:
            raise ValueError("同一源码符号对应多个功能，应保持待人工判定")
        known_symbols.add((source, entry))
        candidate = {"id": ident, "path": path, "source": source, "entry": entry,
                     "owner": item["owner"], "verification": "静态源码声明，未授权自动变更"}
        if path not in paths:
            candidate["problem"] = "稳定身份关联的功能路径已不在现行树；可能改名或退役"
        elif not (root / source).is_file():
            candidate["problem"] = "源码文件已消失；须核实迁移"
        elif not re.search(
            SYMBOL % re.escape(entry),
            (root / source).read_text(encoding="utf-8", errors="replace")
        ):
            candidate["problem"] = "具名入口在原文件已消失；须核实替代实现"
        if "problem" in candidate:
            stale.append(candidate)
        identities[path] = candidate
    groups = ambiguous_source_anchors(root, entries)
    ambiguous = sorted({name for group in groups for name in group["features"]})
    return identities, stale, ambiguous


def classify_unproven_features(entries, root):
    # 文件仅为人工定位线索；文件存在不能证明整个产品能力存在。
    clues = {
        "聊天人物与连续性": [
            "app/src/main/java/com/labteto/dshmobile/local/chat/ChatSceneRuntime.kt",
            "app/src/main/java/com/labteto/dshmobile/local/chat/ChatExpressionPreferences.kt",
            "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaCanonicalV4.kt",
        ],
        "语言服务器": [
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/lsp/LspPlugin.kt",
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/lsp/LspClient.kt",
            "app/src/main/java/com/labteto/dshmobile/local/AutomaticLanguageServer.kt",
        ],
        "设备权限与虚拟显示": [
            "harness-device-android/src/main/java/com/labteto/dshmobile/device/AndroidDevicePlugin.kt",
            "harness-device-android/src/main/java/com/labteto/dshmobile/device/AndroidDeviceProvider.kt",
            "harness-device-android/src/main/java/com/labteto/dshmobile/device/vscreen/VirtualDisplayController.kt",
        ],
        "本机终端与执行环境": [
            "harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/PersistentTerminal.kt",
            "harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/AndroidRuntimePlugin.kt",
        ],
        "共享会话身份与恢复": [
            "harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/DurableSessionCatalog.kt",
            "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRepository.kt",
        ],
        "持续集成与测试": [
            ".github/workflows/ci.yml",
            ".github/scripts/check-local-architecture-boundaries.py",
            ".github/scripts/check-test-quality.py",
        ],
        "构建发布": [
            ".github/workflows/release.yml",
            ".github/scripts/check-apk-runtime-layout.py",
        ],
        "Git与版本控制": [
            "app/src/main/java/com/labteto/dshmobile/local/runtime/BundledGitRuntime.kt",
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/github/GitHubConnectorPlugin.kt",
        ],
        "MCP外部互通": [
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt",
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpClient.kt",
        ],
        "共享工具或其他能力": [
            "app/src/main/java/com/labteto/dshmobile/local/LocalToolOutputStore.kt",
            "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt",
        ],
    }
    """55项空证据仅分类和提出需验证的实际领域，不凭词语相似度确认能力。"""
    results = []
    for record in entries:
        if record["verification"] != "没有可确认的现行入口证据":
            continue
        path = record["path"]
        if "构建与持续集成" in path or "测试与语义验证" in path:
            area, route = "持续集成与测试", ".github/workflows/ 与 .github/scripts/、各模块 src/test"
        elif "语言服务器能力" in path:
            area, route = "语言服务器", "app/local/lsp、harness-interop/lsp"
        elif "Android 设备工具" in path or "原生设备能力" in path:
            area, route = "设备权限与虚拟显示", "harness-device-android/src/main"
        elif "终端运行环境" in path or "Android 本机运行时" in path:
            area, route = "本机终端与执行环境", "harness-runtime-android/src/main"
        elif "会话事实源" in path or "历史与投影" in path or "恢复与维护" in path:
            area, route = "共享会话身份与恢复", "harness-core/src/main/session、app/local/session"
        elif "人物状态与关系" in path or "聊天质量" in path:
            area, route = "聊天人物与连续性", "app/local/chat"
        elif "发布与升级" in path:
            area, route = "构建发布", ".github/workflows/release.yml、app/local/update"
        elif "Git 仓库操作" in path:
            area, route = "Git与版本控制", "app/local/runtime/BundledGitRuntime、harness-interop/github"
        elif "外部工具协议" in path or "MCP" in path:
            area, route = "MCP外部互通", "harness-interop/mcp"
        else:
            area, route = "共享工具或其他能力", "按用户实际功能入口检查共享工具模块"
        targeted = {
            "16类人物资料": [
                "app/src/main/java/com/labteto/dshmobile/local/chat/PersonaCanonicalV4.kt",
                "app/src/main/java/com/labteto/dshmobile/local/chat/ChatPersonaStore.kt",
            ],
            "模型温度": [
                "app/src/main/java/com/labteto/dshmobile/local/chat/CharacterBehaviorTuning.kt",
                "app/src/main/java/com/labteto/dshmobile/local/chat/CharacterBehaviorTuningPersistence.kt",
            ],
            "历史调参": [
                "app/src/main/java/com/labteto/dshmobile/local/chat/CharacterBehaviorTuningPersistence.kt",
            ],
            "共同经历": [
                "app/src/main/java/com/labteto/dshmobile/local/chat/ChatMemoryBinding.kt",
            ],
            "表达偏好": [
                "app/src/main/java/com/labteto/dshmobile/local/chat/ChatExpressionPreferences.kt",
            ],
        }
        candidate_files = next((paths for term, paths in targeted.items() if term in path),
                               clues[area])
        results.append({"path": path, "issue": "缺少可确认的现行源码入口",
                        "domain": area, "inspect": route,
                        "needed": "验证真实实现声明、调用方与行为测试后再升级证据等级",
                        "candidate_files": [p for p in candidate_files if (root / p).is_file()],
                        "candidate_role": "仅供排查的真实文件路径，尚未确认业务功能",
                        "existing_candidate_symbols": [x["symbol"] for x in record.get("named_symbols", [])]})
    return results


def observed_code_delta(root, current, source_snapshot):
    """输出本次可确定的路由/界面变化；下次相同快照必须幂等。"""
    file = root / INDEX
    if not file.is_file():
        return {"baseline": "initial", "added_routes": [], "removed_routes": [],
                "added_ui": [], "removed_ui": [], "added_production_files": [],
                "removed_production_files": [], "modified_production_files": [],
                "moved_unchanged_files": []}
    before = json.loads(file.read_text(encoding="utf-8"))
    if before.get("source_snapshot") == source_snapshot:
        return before.get("observed_delta", {
            "baseline": source_snapshot, "added_routes": [], "removed_routes": [],
            "added_ui": [], "removed_ui": [],
            "added_production_files": [], "removed_production_files": [],
            "modified_production_files": [], "moved_unchanged_files": [],
        })
    old_routes = set(before.get("registered_routes", []))
    new_routes = set(current.get("registered_routes", []))
    old_ui = {(x["symbol"], x["source"]) for x in before.get("candidate_ui_screens", [])}
    new_ui = {(x["symbol"], x["source"]) for x in current.get("candidate_ui_screens", [])}
    def format_ui(entries):
        return [{"symbol": symbol, "source": source} for symbol, source in sorted(entries)]
    old_sources = {x["path"]: x["sha256"] for x in before.get("production_file_fingerprints", [])}
    new_sources = {x["path"]: x["sha256"] for x in current["production_file_fingerprints"]}
    tracked = "production_file_fingerprints" in before
    added = sorted(set(new_sources) - set(old_sources)) if tracked else []
    removed = sorted(set(old_sources) - set(new_sources)) if tracked else []
    modified = sorted(x for x in set(old_sources) & set(new_sources)
                      if old_sources[x] != new_sources[x]) if tracked else []
    renames = []
    for old in removed[:]:
        matched = next((p for p in added if new_sources[p] == old_sources[old]), None)
        if matched:
            renames.append({"previous": old, "current": matched, "basis": "文件内容指纹完全相同"})
            removed.remove(old)
            added.remove(matched)
    return {
        "baseline": before.get("source_snapshot", "unknown"),
        "added_production_files": added,
        "removed_production_files": removed,
        "modified_production_files": modified,
        "moved_unchanged_files": renames,
        "added_routes": sorted(new_routes - old_routes),
        "removed_routes": sorted(old_routes - new_routes),
        "added_ui": format_ui(new_ui - old_ui),
        "removed_ui": format_ui(old_ui - new_ui),
    }


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
    test_contents = [
        p.read_text(encoding="utf-8", errors="replace")
        for p in sorted((root / "app").rglob("*"))
        if p.is_file() and p.suffix in {".kt", ".java"}
        and ("/src/test/" in "/" + p.relative_to(root).as_posix()
             or "/src/androidTest/" in "/" + p.relative_to(root).as_posix())
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
        source_history, named_history = set(), set()
        ledger = root / EVIDENCE
        if ledger.is_file():
            for row in ledger.read_text(encoding="utf-8").splitlines():
                cols = [x.strip() for x in row.strip("|").split("|")]
                if len(cols) < 4 or cols[1] != name:
                    continue
                source_history.update(HISTORICAL_LINK.findall(cols[3]))
                for label in re.findall(r"\[([^\]]+)\]\(https://github\.com/[^)]+\)", cols[3]):
                    if " · " in label:
                        named_history.add(label.rsplit(" · ", 1)[-1].strip())
        for old in previous.values():
            if old["path"] == name:
                source_history.add(old["source"])
                if old.get("entry"):
                    named_history.add(old["entry"])
        if source not in source_history or symbol not in named_history:
            pending.append({"path": name, "reason": "缺少旧源码路径与历史具名入口的双重身份依据，无法安全证明已退役"})
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
        if any(re.search(r"\b" + re.escape(symbol) + r"\b", t) for t in test_contents):
            pending.append({"path": name, "reason": "Kotlin/Java 测试源码仍引用旧入口，不能判定已退役"})
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
        # 保持名称路径唯一。撞上现有节点会让两个产品功能误合并。
        target = parts(item["path"])
        source_path = parts(was["path"]) if was else (
            parts(item["previous_path"]) if item.get("previous_path") else None
        )
        exists = find(model, target)
        historical_match = []
        if not was:
            historic_removed, historic_current = historical_code_evidence(root, [])
            historical_match = [x for x in historic_removed + historic_current
                                if x["path"] == item["path"]]
        allowed_adoption = (not was and exists is not None and
                            not exists["children"] and
                            any(item["source"] in x["historical_production_sources"]
                                for x in historical_match))
        if exists is not None and not allowed_adoption and target != source_path and (
            not was or target != parts(was["path"])
        ):
            pending.append({"id": ident, "path": item["path"],
                            "status": "拒绝自动合并现有节点",
                            "reason": "目标功能路径已经存在；请先逐项核实合并/拆分及测试归属"})
            if was and find(model, parts(was["path"])) is None:
                add(model, parts(was["path"]))
            continue
        if not was and source_path is not None:
            historical, existing = historical_code_evidence(root, [])
            matches = [x for x in historical + existing if x["path"] == item["previous_path"]]
            known_sources = {x for e in matches for x in e["historical_production_sources"]}
            if not known_sources or item["source"] not in known_sources:
                pending.append({"id": ident, "path": item["path"],
                                "status": "旧节点身份待核实",
                                "reason": "previous_path 缺少相同源码文件的历史证据，不能接管任意旧功能"})
                continue
        # 前置检查所有破坏性迁移约束，之后才删除旧路径。
        parent = find(model, target[:-1])
        if parent is not None and not parent["children"] and len(target) > 1:
            pending.append({"id": ident, "path": item["path"],
                            "status": "原末级功能不能直接变为分类",
                            "reason": "需审核新父子层级与原功能测试的归属"})
            continue
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
    evidence = legacy_leaf_evidence(root, all_leaves, matrix)
    identities, stale_identities, ambiguous_identities = historical_identity_review(
        root, all_leaves, evidence
    )
    unresolved_features = classify_unproven_features(evidence, root)
    followup = {}
    followup_file = root / EXISTENCE_TRIAGE
    if followup_file.is_file():
        followup_json = json.loads(followup_file.read_text(encoding="utf-8"))
        if followup_json.get("version") != 1 or not isinstance(followup_json.get("items"), list):
            raise ValueError("功能存在性专项核查登记格式错误")
        expected = {x["path"] for x in unresolved_features}
        for entry in followup_json["items"]:
            key = entry.get("path")
            if not isinstance(key, str) or key in followup or key not in expected:
                raise ValueError("存在性核查记录重复或不在源证据待核清单")
            followup[key] = {
                "kind": entry["kind"],
                "availability": entry["availability"],
                "separate_product_entry": entry["independent"],
                "production_sources_confirmed": len(entry["source"]),
                "test_methods_confirmed": len(entry["test"]),
                "explanation": entry["conclusion"],
            }
        if set(followup) != expected:
            raise ValueError(f"存在性核查未对齐现行证据：新增待核={sorted(expected - set(followup))}; 不再待核={sorted(set(followup) - expected)}")
        for item in unresolved_features:
            item["followup"] = followup[item["path"]]
    for record in evidence:
        if record["path"] in followup:
            record["followup"] = followup[record["path"]]
        binding = identities.get(record["path"])
        if binding is not None:
            record["identity_id"] = binding["id"]
            record["identity_status"] = (
                "待复核入口变化" if binding.get("problem") else "静态声明已核，尚未授权自动改树"
            )
    evidence_by_path = {entry["path"]: entry for entry in evidence}
    structure = to_html(model, old, {v["path"]: v for v in valid.values()
                                    if (root / v["source"]).is_file()})
    def annotate(node, stack):
        for child in node["children"]:
            path = stack + [child["name"]]
            full = " / ".join(path)
            if not child["children"]:
                if full in evidence_counts:
                    child["testCount"] = evidence_counts[full]
                if full in evidence_by_path:
                    item = evidence_by_path[full]
                    child["evidenceLevel"] = item["verification"]
                    child["sourceCount"] = len(item["existing_production_sources"])
                    child["missingSourceCount"] = len(item["missing_production_sources"])
                    child["workflowJobCount"] = len(item.get("workflow_evidence", []))
                    child["unrunTestCount"] = len(item.get("explicit_test_candidates", []))
                    if "followup" in item:
                        child["followupKind"] = item["followup"]["kind"]
                        child["followupAvailability"] = item["followup"]["availability"]
                        child["followupSourceCount"] = item["followup"]["production_sources_confirmed"]
                        child["followupTestMethodCount"] = item["followup"]["test_methods_confirmed"]
                    if "identity_id" in item:
                        child["identityId"] = item["identity_id"]
                        child["identityStatus"] = item["identity_status"]
            annotate(child, path)
    annotate(structure, [])
    dataset = json.dumps(structure, ensure_ascii=False, separators=(",", ":"))
    html = html[:html_match.start(2)] + dataset + html[html_match.end(2):]
    # 替换过期的主线/PR 快照说明，避免历史提交号伪装为最新。
    html = re.sub(
        r'(<div class="head">.*?<p class="sub">).*?(</p>)',
        lambda match: match.group(1) + f'按架构 3.0 的实际功能归属展示 · 当前源码内容指纹 {snap}' + match.group(2),
        html, count=1, flags=re.S,
    )
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
    # 未获准的迁移保留旧身份，且不将未写入的新增登记误记为确认。
    rejected_ids = {x.get("id") for x in pending}
    registry_nodes = {
        **{ident: older[ident] for ident in rejected_ids if ident in older},
        **{ident: item for ident, item in valid.items() if ident not in rejected_ids},
    }
    node_count = validate_view(model, html)
    categories = {}
    for e in evidence:
        categories[e["verification"]] = categories.get(e["verification"], 0) + 1
    delta = observed_code_delta(root, index, snap)
    review = {
        "version": 1,
        "snapshot": snap,
        "scope": "全部仓库代码文件、全部生产源码，按产品真实入口复核",
        "code_files_scanned": index["repository_code_file_count"],
        "production_sources_scanned": index["production_source_count"],
        "feature_nodes": node_count,
        "functional_leaves": len(all_leaves),
        "existing_executable_test_mappings": len(matrix["items"]),
        "leaf_evidence": evidence,
        "evidence_grade_summary": categories,
        "workflow_evidence_features": sum(bool(v.get("workflow_evidence")) for v in evidence),
        "declared_unexecuted_test_methods": sum(len(v.get("explicit_test_candidates", [])) for v in evidence),
        "tracked_legacy_identities": len(identities),
        "stale_legacy_identities": stale_identities,
        "ambiguous_named_entry_paths": ambiguous_identities,
        "ambiguous_named_entry_groups": ambiguous_source_anchors(root, evidence),
        "unproven_feature_triage": unresolved_features,
        "followup_investigated_descriptions": len(followup),
        "followup_production_or_compatibility": sum(x["production_sources_confirmed"] > 0 for x in followup.values()),
        "followup_test_only_descriptions": sum(x["production_sources_confirmed"] == 0 for x in followup.values()),
        "observed_code_delta": delta,
        "source_verified_registrations": len(registry_nodes),
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
    index["observed_delta"] = delta
    index["tracked_legacy_identities"] = len(identities)
    index["unproven_feature_count"] = len(unresolved_features)
    index["followup_investigated_descriptions"] = len(followup)
    index["workflow_evidence_features"] = sum(bool(v.get("workflow_evidence")) for v in evidence)
    index["declared_unexecuted_test_methods"] = sum(len(v.get("explicit_test_candidates", [])) for v in evidence)
    index["verified_registrations"] = sorted(registry_nodes)
    index["unverified_changes_count"] = len(pending)
    index["obsolete_historical_evidence_count"] = len(historical_gaps)
    index["current_tree_outdated_source_count"] = len(current_source_gaps)
    normalized = [{k: v for k, v in item.items() if k not in {"previous_path", "action", "reason"}}
                  for item in sorted(registry_nodes.values(), key=lambda v: v["id"])]
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
                              ("\n" if action == "retire" else "\nclass Entry {}\n"),
                              encoding="utf-8")
        def write_outputs(outputs):
            for filename, value in outputs.items():
                (root / filename).write_text(value, encoding="utf-8")

        (root / EVIDENCE).write_text(
            "| 层级 | 功能完整路径 | 等级 | 来源 |\n"
            "| 2 | A / 旧功能 | 具名源码入口已核（静态） | "
            "[原入口 · Entry](https://github.com/example/repo/blob/abc/"
            "app/src/main/java/sample/Feature.kt#L3) |\n",
            encoding="utf-8"
        )
        mark("A / 旧功能")
        write_outputs(generate(root))
        assert json.loads((root / REG).read_text(encoding="utf-8"))["nodes"][0]["source"].endswith("Feature.kt")
        assert generate(root)[MD] == (root / MD).read_text(encoding="utf-8")
        # 模拟把现有两个功能强制改成同一路径，必须拒绝合并。
        mark("B / 已有能力")
        conflicted = generate(root)
        assert any("目标功能路径已经存在" in p["reason"]
                   for p in json.loads(conflicted[REVIEW])["pending_changes"])
        assert find(parse(conflicted[MD])[0], ["A", "旧功能"]) is not None
        assert json.loads(conflicted[REG])["nodes"][0]["path"] == "A / 旧功能"
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
        blocked = generate(root)
        assert find(parse(blocked[MD])[0], ["B", "新功能"]) is not None
        assert any("测试" in p["reason"] for p in json.loads(blocked[REVIEW])["pending_changes"])
        # 退役前相关测试必须退出，并从验收映射解绑。
        test_file.write_text("class FeatureTest {}\n", encoding="utf-8")
        (root / MATRIX).write_text(json.dumps({"version": 2, "items": []}), encoding="utf-8")
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
            "| 层级 | 功能完整路径 | 证据 | 代码 |\n"
            "| 3 | B / 已有能力 | 专属源码路径已核 | "
            "[旧实现 · LegacyDead](" + historical_url + ") |\n", encoding="utf-8"
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
        # 静态身份仅追踪独立真实入口；路径删除时应报警，不能自动改写产品功能。
        binding = {"version": 1, "bindings": [{
            "id": "existing.0001", "path": "B / 试验功能",
            "source": "app/src/main/java/sample/Feature.kt",
            "entry": "Entry", "owner": "CHAT", "evidence": "static_declaration_only",
        }]}
        (root / LEGACY_IDENTITIES).write_text(
            json.dumps(binding, ensure_ascii=False), encoding="utf-8"
        )
        identities, stale, ambiguity = historical_identity_review(
            root, ["B / 试验功能"], [{
                "path": "B / 试验功能",
                "existing_production_sources": ["app/src/main/java/sample/Feature.kt"],
                "named_symbols": [{"symbol": "Entry", "declaration_found": True}],
            }]
        )
        assert len(identities) == 1 and not stale and not ambiguity
        _, stale, _ = historical_identity_review(root, ["B / 已退役"], [])
        assert len(stale) == 1
        assert stale[0]["id"] == "existing.0001"

        # 工程 job 的证据必须在该 job 自己的命令中，而非同文件其他作业。
        automation_file = root / ".github/workflows/sample.yml"
        automation_file.parent.mkdir(parents=True, exist_ok=True)
        automation_file.write_text(
            "name: 核查\n"
            "jobs:\n"
            "  alpha:\n"
            "    steps:\n"
            "      - run: echo ONLY_ALPHA\n"
            "  beta:\n"
            "    steps:\n"
            "      - run: echo ONLY_BETA\n",
            encoding="utf-8"
        )
        job_evidence = {"version": 1, "items": [{
            "path": "B / 试验功能",
            "grade": "CI工作流入口存在（静态）",
            "sources": [{"path": ".github/workflows/sample.yml",
                         "job": "alpha", "literals": ["ONLY_ALPHA"]}],
        }]}
        (root / WORKFLOW_EVIDENCE).write_text(
            json.dumps(job_evidence, ensure_ascii=False), encoding="utf-8"
        )
        assert len(verified_workflow_evidence(root, ["B / 试验功能"])) == 1
        job_evidence["items"][0]["sources"][0]["literals"] = ["ONLY_BETA"]
        (root / WORKFLOW_EVIDENCE).write_text(
            json.dumps(job_evidence, ensure_ascii=False), encoding="utf-8"
        )
        try:
            verified_workflow_evidence(root, ["B / 试验功能"])
            raise AssertionError("不能因为另一个 job 中有命令就承认当前 job 功能")
        except ValueError:
            pass
        # 单个符号仅存在于一个具体文件，禁止在另一文件假造共享入口歧义。
        alternate = root / "app/src/main/java/sample/Other.kt"
        alternate.write_text("class Other {}\n", encoding="utf-8")
        entries = [
            {"path": "B / 一", "existing_production_sources": [
                "app/src/main/java/sample/Feature.kt"
            ], "named_symbols": [{"symbol": "Entry", "declaration_found": True}]},
            {"path": "B / 二", "existing_production_sources": [
                "app/src/main/java/sample/Other.kt"
            ], "named_symbols": [{"symbol": "Entry", "declaration_found": True}]},
        ]
        assert not ambiguous_source_anchors(root, entries)
        entries[1]["existing_production_sources"] = [
            "app/src/main/java/sample/Feature.kt"
        ]
        assert len(ambiguous_source_anchors(root, entries)) == 1


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
            # Give contributors enough deterministic evidence to restore generated
            # documents without disabling drift checks or guessing the source SHA.
            current_index = json.loads(output[INDEX])
            old_index = json.loads((ROOT / INDEX).read_text(encoding="utf-8"))
            old_hashes = {entry["path"]: entry["sha256"] for entry in
                          old_index.get("production_file_fingerprints", [])}
            changed_hashes = [entry for entry in
                              current_index.get("production_file_fingerprints", [])
                              if old_hashes.get(entry["path"]) != entry["sha256"]]
            changed_meta = {key: value for key, value in current_index.items()
                            if key != "production_file_fingerprints" and old_index.get(key) != value}
            current_review = json.loads(output[REVIEW])
            old_review = json.loads((ROOT / REVIEW).read_text(encoding="utf-8"))
            review_changes = {key: value for key, value in current_review.items()
                              if old_review.get(key) != value}
            print("功能树同步差异：" + json.dumps({
                "source_snapshot": current_index.get("source_snapshot"),
                "modified_source_hashes": changed_hashes,
                "index_changed_fields": changed_meta,
                "review_changed_fields": review_changes,
            }, ensure_ascii=False, separators=(",", ":")))
        if args.check and stale:
            sys.exit(1)
    except (OSError, ValueError, AssertionError, KeyError, TypeError) as error:
        import traceback
        traceback.print_exc()
        print("功能树自动维护失败: " + str(error), file=sys.stderr)
        sys.exit(1)
