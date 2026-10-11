#!/usr/bin/env python3
"""独立验证生成结果与仓库事实：校验失败则不得提交功能树。"""
import json
import hashlib
import re
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

    # 独立重读历史台账，确认已移出功能、缺失生产代码和报告结果逐条一致。
    old_evidence = DOCS / "FEATURE-CODE-EVIDENCE.zh-CN.md"
    historical_dropped, suspicious_current = [], []
    if old_evidence.is_file():
        pattern = re.compile(
            r"https://github\.com/[^/]+/[^/]+/blob/[^/]+/"
            r"(app/src/(?:main|test|androidTest)/[^\s)#]+?\.(?:kt|java))(?:#|[)])"
        )
        for line in old_evidence.read_text(encoding="utf-8").splitlines():
            if not line.startswith("| "):
                continue
            cols = [x.strip() for x in line.strip("|").split("|")]
            if len(cols) < 4 or not cols[0].isdigit() or " / " not in cols[1]:
                continue
            candidate = cols[1]
            prod = sorted(set(
                path for path in pattern.findall(cols[3])
                if "/src/main/" in "/" + path
            ))
            absent = [p for p in prod if not (ROOT / p).is_file()]
            if candidate not in leaf_paths:
                historical_dropped.append((candidate, prod, absent))
            elif absent:
                suspicious_current.append((candidate, prod, absent))
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    review = json.loads(REVIEW.read_text(encoding="utf-8"))
    registry = json.loads(REG.read_text(encoding="utf-8"))
    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    digest = hashlib.sha256()
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or any(x in SKIP for x in path.relative_to(ROOT).parts):
            continue
        if path.relative_to(ROOT).as_posix() == ".github/workflows/feature-tree-auto.yml":
            continue
        if path.suffix not in CODE and path.name != "gradlew":
            continue
        digest.update(path.relative_to(ROOT).as_posix().encode("utf-8"))
        digest.update(bytes([0]))
        digest.update(hashlib.sha256(path.read_bytes()).digest())
    sha = digest.hexdigest()[:12]
    if index["source_snapshot"] != sha or review["snapshot"] != sha:
        raise ValueError("生成文件绑定的 SHA 与实际源码不同")
    recorded_historical = [
        (x["path"], x["historical_production_sources"], x["missing_production_sources"])
        for x in review.get("obsolete_historical_evidence", [])
    ]
    recorded_current = [
        (x["path"], x["historical_production_sources"], x["missing_production_sources"])
        for x in review.get("current_tree_outdated_sources", [])
    ]
    if (recorded_historical != historical_dropped
        or recorded_current != suspicious_current
        or review.get("obsolete_historical_evidence_count") != len(historical_dropped)
        or review.get("current_tree_outdated_source_count") != len(suspicious_current)
        or index.get("obsolete_historical_evidence_count") != len(historical_dropped)
        or index.get("current_tree_outdated_source_count") != len(suspicious_current)):
        raise ValueError("历史功能退役疑点与源码现存情况不一致")
    # 经审批自动移除的旧功能，再次检查身份与不存在的生产入口。
    retirements_path = DOCS / "FEATURE-TREE-RETIREMENTS.json"
    config = json.loads(retirements_path.read_text(encoding="utf-8")) if retirements_path.is_file() else {"version": 1, "approvals": []}
    if config.get("version") != 1:
        raise ValueError("功能退役审批记录版本不合法")
    approved_paths = set()
    for entry in review.get("approved_legacy_retirements", []):
        name = entry["path"]
        if name not in [v["path"] for v in config["approvals"]]:
            raise ValueError("报告凭空引入退役审批结果")
        if name in leaf_paths:
            raise ValueError("已批准退役的功能仍留在现行树")
        approved_paths.add(name)
        approval = next(x for x in config["approvals"] if x["path"] == name)
        symbol = approval["symbol"]
        live_entry = re.compile(
            r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
            re.escape(symbol) + r"\b"
        )
        for file in (ROOT / "app").rglob("*"):
            if not file.is_file() or file.suffix not in {".kt", ".java"}:
                continue
            relative = file.relative_to(ROOT).as_posix()
            if ("/src/main/" in "/" + relative and live_entry.search(
                file.read_text(encoding="utf-8", errors="replace")
            )):
                raise ValueError("二次复核发现退役符号仍有现存生产声明: " + name)
            if ("/src/test/" in "/" + relative or "/src/androidTest/" in "/" + relative):
                if re.search(r"\b" + re.escape(symbol) + r"\b",
                             file.read_text(encoding="utf-8", errors="replace")):
                    raise ValueError("二次复核发现退役符号仍在测试中使用: " + name)
        if any(x["path"] == name for x in matrix["items"]):
            raise ValueError("二次复核发现退役功能仍有验收索引: " + name)
    for candidate in review.get("legacy_retirements_requiring_review", []):
        if candidate["path"] not in [v["path"] for v in config["approvals"]]:
            raise ValueError("退役未批准项没有真实审核请求")
    if review["feature_nodes"] != len(html_paths) or review["functional_leaves"] != len(leaf_paths):
        raise ValueError("复核报告记录的功能节点数量有误")
    actual_evidence = {item["path"]: len(item["evidence"]) for item in matrix["items"]}
    reported = review.get("leaf_evidence")
    if not isinstance(reported, list) or [x.get("path") for x in reported] != leaf_paths:
        raise ValueError("逐功能代码证据复核台账不完整或顺序错误")
    for item in reported:
        expected = actual_evidence.get(item["path"], 0)
        if item.get("test_reference_count") != expected:
            raise ValueError("逐功能测试证据数量不一致: " + item["path"])
    # 复查分级来源：测试引用和源码存废是两种独立证据。
    recomputed_grades = {}
    for item in reported:
        for field in ("existing_production_sources", "missing_production_sources",
                      "named_symbols"):
            if not isinstance(item.get(field), list):
                raise ValueError("逐项证据缺少 " + field)
        for src in item["existing_production_sources"]:
            if not (ROOT / src).is_file():
                raise ValueError("声明现存的生产源码已经不存在: " + src)
        for src in item["missing_production_sources"]:
            if (ROOT / src).exists():
                raise ValueError("声明已删除的生产源码实际仍存在: " + src)
        for symbol in item["named_symbols"]:
            actual = any(re.search(
                r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
                re.escape(symbol["symbol"]) + r"\b",
                (ROOT / src).read_text(encoding="utf-8", errors="replace"),
            ) for src in item["existing_production_sources"])
            if actual != symbol["declaration_found"]:
                raise ValueError("历史具名入口核实结果和当前代码不一致")
        grade = item["verification"]
        recomputed_grades[grade] = recomputed_grades.get(grade, 0) + 1
        if grade.startswith("真实测试") and not item["test_reference_count"]:
            raise ValueError("没有真实测试证据却声称有可执行测试")
        if grade.startswith("具名源码声明") and not any(
            x["declaration_found"] for x in item["named_symbols"]
        ):
            raise ValueError("没有源码声明却声称具名入口已经核查")
    if review.get("evidence_grade_summary") != recomputed_grades:
        raise ValueError("全量分级统计与逐功能证据不一致")
    if review.get("observed_code_delta") != index.get("observed_delta"):
        raise ValueError("功能变化报告与扫描索引不一致")
    # 单独复核手工补录的跨模块证据：来源、符号和LSP工具分支必须真实存在。
    explicit_file = DOCS / "FEATURE-TREE-EXPLICIT-EVIDENCE.json"
    explicit = (json.loads(explicit_file.read_text(encoding="utf-8"))
                if explicit_file.is_file() else {"version": 1, "items": []})
    if explicit.get("version") != 1 or not isinstance(explicit.get("items"), list):
        raise ValueError("跨模块人工源码证据格式不正确")
    explicit_keys = set()
    evidence_by_name = {item["path"]: item for item in reported}
    for item in explicit["items"]:
        name = item["path"]
        if name in explicit_keys or name not in evidence_by_name:
            raise ValueError("人工核实重复或涉及不存在的产品功能")
        explicit_keys.add(name)
        review_item = evidence_by_name[name]
        if review_item.get("explicit_source_anchor") is not True:
            raise ValueError("人工核实记录没有进入最终逐功能证据")
        if review_item.get("explicit_static_scope") != item["scope"]:
            raise ValueError("静态审核范围与源码证据不一致")
        for record in item["sources"]:
            source = record["path"]
            file = ROOT / source
            if not file.is_file() or source not in review_item["existing_production_sources"]:
                raise ValueError("人工确认的真实源码在报告中失效")
            code = file.read_text(encoding="utf-8", errors="replace")
            for symbol in record["symbols"]:
                if not re.search(r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
                                 re.escape(symbol) + r"\b", code):
                    raise ValueError("人工确认的源码声明已不存在: " + symbol)
                if not any(x["symbol"] == symbol and x["declaration_found"]
                           for x in review_item["named_symbols"]):
                    raise ValueError("人工确认的具名符号没有保存在证据中")
            for literal in record.get("literals", []):
                if literal not in code:
                    raise ValueError("工具映射分支已经消失: " + literal)
    if {r["path"] for r in reported if r.get("explicit_source_anchor")} != explicit_keys:
        raise ValueError("存在没有真实人工源文件依据的强化证据等级")
    # 工程工作流证据独立从真实YAML job段重算，不能仅凭全文件出现相同词语提升级别。
    workflow_manifest = DOCS / "FEATURE-TREE-WORKFLOW-EVIDENCE.json"
    evidence_file = (json.loads(workflow_manifest.read_text(encoding="utf-8"))
                     if workflow_manifest.is_file() else {"version": 1, "items": []})
    if evidence_file.get("version") != 1 or not isinstance(evidence_file.get("items"), list):
        raise ValueError("工程工作流证据格式不合法")
    seen_workflow_paths = set()
    manual_jobs = {}
    for feature in evidence_file["items"]:
        key = feature["path"]
        if key in seen_workflow_paths or key not in {x["path"] for x in reported}:
            raise ValueError("工程工作流证据重复或引用了现行树外的功能")
        seen_workflow_paths.add(key)
        verified = []
        for record in feature["sources"]:
            file_name, job, phrases = record["path"], record["job"], record["literals"]
            if (not isinstance(file_name, str) or
                    not file_name.startswith(".github/workflows/") or
                    not file_name.endswith((".yml", ".yaml")) or
                    ".." in Path(file_name).parts or not (ROOT / file_name).is_file()):
                raise ValueError("工作流证据路径失效或跨出允许目录")
            if not isinstance(job, str) or not re.fullmatch(r"[a-z][\w-]*", job):
                raise ValueError("工作流任务名称缺失或非法")
            lines = (ROOT / file_name).read_text(encoding="utf-8").splitlines()
            anchors = [i for i, line in enumerate(lines)
                       if re.fullmatch(r"  ([A-Za-z][\w-]*):[ \t]*(?:#.*)?", line)]
            selected = [i for i in anchors if lines[i].split(":", 1)[0].strip() == job]
            if len(selected) != 1:
                raise ValueError("找不到唯一的工程工作流 job: " + file_name + "#" + job)
            start = selected[0]
            end = next((i for i in anchors if i > start), len(lines))
            scoped = "\n".join(lines[start:end])
            if (not isinstance(phrases, list) or not phrases or
                    any(not isinstance(q, str) or not q.strip() or q not in scoped for q in phrases)):
                raise ValueError("工程任务内不存在声明的执行命令：" + file_name + "#" + job)
            verified.append({"path": file_name, "job": job, "literals": phrases})
        manual_jobs[key] = verified
        item = next(x for x in reported if x["path"] == key)
        if (item.get("workflow_evidence") != verified or
                item.get("verification") != feature["grade"]):
            raise ValueError("工程工作流证据与逐功能真实核查结果不一致")
    for record in reported:
        if record.get("workflow_evidence", []) and record["path"] not in manual_jobs:
            raise ValueError("存在未登记的工程工作流证据")
    # 不依赖扫描器的身份声明：重新验证静态 ID、路径、原始文件和真实 Kotlin/Java 声明。
    identities_file = DOCS / "FEATURE-TREE-LEGACY-IDENTITIES.json"
    manifest = (json.loads(identities_file.read_text(encoding="utf-8"))
                if identities_file.is_file() else {"version": 1, "bindings": []})
    if manifest.get("version") != 1 or not isinstance(manifest.get("bindings"), list):
        raise ValueError("现有功能稳定身份文件格式不合法")
    bound, unique_keys, problems = set(), set(), []
    declared_by_path = {r["path"]: r for r in reported}
    for binding in manifest["bindings"]:
        identity, path, source, symbol = (
            binding.get("id"), binding.get("path"),
            binding.get("source"), binding.get("entry")
        )
        if (not isinstance(identity, str) or not re.fullmatch(r"existing\.\d{4,}", identity)
                or not isinstance(path, str) or identity in bound):
            raise ValueError("旧功能身份必须采用唯一 existing.NNNN 标识")
        bound.add(identity)
        if not isinstance(source, str) or not isinstance(symbol, str):
            raise ValueError("旧功能身份缺少真实代码关联")
        pair = (source, symbol)
        if pair in unique_keys:
            raise ValueError("多功能共享同一源码声明，不得假设功能身份唯一")
        unique_keys.add(pair)
        if binding.get("evidence") != "static_declaration_only":
            raise ValueError("静态身份不能标为已经完成运行验收")
        reason = None
        if path not in declared_by_path:
            reason = "路径不在当前末级树中"
        elif not (ROOT / source).is_file():
            reason = "源码文件不存在"
        elif not re.search(r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
                           re.escape(symbol) + r"\b",
                           (ROOT / source).read_text(encoding="utf-8", errors="replace")):
            reason = "源码具名声明已消失"
        evidence = declared_by_path.get(path)
        if evidence is not None:
            if evidence.get("identity_id") != identity:
                raise ValueError("功能报告没有保持正确的稳定源码身份")
            if not reason and evidence.get("identity_status") != "静态声明已核，尚未授权自动改树":
                raise ValueError("稳定身份状态与当前源码不符")
        if reason:
            problems.append(identity)
    stale_ids = {x["id"] for x in review.get("stale_legacy_identities", [])}
    if set(problems) != stale_ids:
        raise ValueError("旧功能身份已过期名单与源码不符")
    if (review.get("tracked_legacy_identities") != len(bound)
            or index.get("tracked_legacy_identities") != len(bound)):
        raise ValueError("稳定身份登记数量与全量扫描报告不一致")
    ambiguous_keys = {}
    cached_code = {}
    for record in reported:
        for src in record.get("existing_production_sources", []):
            if src not in cached_code:
                cached_code[src] = (ROOT / src).read_text(encoding="utf-8", errors="replace")
            for declaration in record.get("named_symbols", []):
                if not declaration.get("declaration_found"):
                    continue
                symbol = declaration["symbol"]
                if re.search(r"(?:fun|class|object|interface|val|var|enum\s+class)\s+" +
                             re.escape(symbol) + r"\b", cached_code[src]):
                    ambiguous_keys.setdefault((src, symbol), set()).add(record["path"])
    groups = [
        {"source": source, "symbol": symbol, "features": sorted(members)}
        for (source, symbol), members in sorted(ambiguous_keys.items())
        if len(members) > 1
    ]
    ambiguous_paths = sorted({path for group in groups for path in group["features"]})
    if (review.get("ambiguous_named_entry_paths") != ambiguous_paths
            or review.get("ambiguous_named_entry_groups") != groups):
        raise ValueError("共享入口歧义的真实源码文件、声明符号或关联功能不一致")
    missing = [r["path"] for r in reported
               if r.get("verification") == "没有可确认的现行入口证据"]
    pending = review.get("unproven_feature_triage", [])
    if [r["path"] for r in pending] != missing or index.get("unproven_feature_count") != len(missing):
        raise ValueError("逐项无源码证据功能的待核实登记不完整")
    for item in pending:
        if item.get("candidate_role") != "仅供排查的真实文件路径，尚未确认业务功能":
            raise ValueError("弱证据功能的源码候选必须明确不能代表功能通过")
        links = item.get("candidate_files")
        if not isinstance(links, list) or len(links) != len(set(links)):
            raise ValueError("弱证据源码候选格式或唯一性不正确")
        for link in links:
            if not isinstance(link, str) or ".." in link.split("/") or not (ROOT / link).is_file():
                raise ValueError("源码候选指向不存在的文件，不能误导审核")
    by_path = {x["path"]: x for x in reported}
    def check_display(node, stack):
        for child in node["children"]:
            path = stack + [child["name"]]
            if not child["children"]:
                current = " / ".join(path)
                expected = actual_evidence.get(current, 0)
                info = by_path[current]
                if child.get("testCount", 0) != expected:
                    raise ValueError("HTML 逐项测试证据标注不一致")
                if (child.get("evidenceLevel") != info["verification"] or
                    child.get("sourceCount") != len(info["existing_production_sources"]) or
                    child.get("missingSourceCount") != len(info["missing_production_sources"])):
                    raise ValueError("HTML 展示的源码证据状态与复核台账不一致")
            check_display(child, path)
    check_display(data, [])
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

    # 全生产源码清单必须完整，内容指纹须由实际磁盘字节重新计算。
    recorded_sources = index.get("production_file_fingerprints", [])
    actual_sources = []
    for file in sorted(ROOT.rglob("*")):
        if not file.is_file() or any(x in SKIP for x in file.relative_to(ROOT).parts):
            continue
        rel = file.relative_to(ROOT).as_posix()
        if "/src/main/" in "/" + rel and file.suffix in {".kt", ".java"}:
            actual_sources.append({
                "path": rel, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()[:16]
            })
    if recorded_sources != actual_sources or len(actual_sources) != sources:
        raise ValueError("生产源码文件清单或内容指纹与真实代码不一致")
    catalog = ROOT / "app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt"
    if not catalog.is_file():
        raise ValueError("真实产品功能路由登记目录丢失")
    catalog_text = catalog.read_text(encoding="utf-8")
    match = re.search(r"enum class LocalFeatureRoute\s*\{([^}]*)\}", catalog_text, re.S)
    if not match:
        raise ValueError("现行 Kotlin 路由枚举无法识别")
    actual_routes = re.findall(r"^\s*([A-Z][A-Z_0-9]+)\s*,?\s*$", match.group(1), re.M)
    if index["registered_routes"] != actual_routes:
        raise ValueError("索引路由与实际 Kotlin 功能目录不一致")
    found_ui = []
    for entry in actual_sources:
        source_path = entry["path"]
        if "/ui/" not in source_path:
            continue
        code = (ROOT / source_path).read_text(encoding="utf-8", errors="replace")
        for match in re.finditer(
            r"@Composable\s+(?:(?:internal|private|public)\s+)?fun\s+(\w*Screen\w*)\s*\(",
            code,
        ):
            found_ui.append({"symbol": match.group(1), "source": source_path})
    found_ui.sort(key=lambda x: (x["symbol"], x["source"]))
    if index.get("candidate_ui_screens") != found_ui:
        raise ValueError("界面候选与当前 Compose 代码不一致")
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
    print(f"历史功能核对：现行树外旧项 {len(historical_dropped)} 项，现行功能中失效源码来源 {len(suspicious_current)} 项。")
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
