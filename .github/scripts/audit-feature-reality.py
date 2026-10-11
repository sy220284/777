#!/usr/bin/env python3
"""Independent code-first functionality audit; no import from the tree scanner."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs"
OUT = DOC / "FEATURE-REALITY-AUDIT.auto.json"
NOTE = DOC / "FEATURE-REALITY-AUDIT.zh-CN.md"
SKIP = {".git", ".gradle", "build", "node_modules", ".venv", "__pycache__"}
DECL = r"(?:fun|class|object|interface|val|var|enum\s+class)\s+%s\b"
GRADES = {
    "真实测试映射（仅局部）": "test_method_mapping",
    "具名源码声明存在（静态，待调用链复核）": "named_code_declaration",
    "具名源码及仪表测试声明存在（静态，未执行）": "unexecuted_instrumentation",
    "CI或发布工作流中存在对应任务和命令（静态，执行结果另核）": "ci_workflow_configuration",
    "历史生产文件仍存在（待入口复核）": "historical_file_only",
    "候选生产文件仍存在（不足以证明功能）": "candidate_file_only",
    "没有可确认的现行入口证据": "no_code_entry",
}
PROVIDERS = {
    "device": "harness-device-android/src/main/java/com/labteto/dshmobile/device/AndroidDevicePlugin.kt",
    "runtime": "harness-runtime-android/src/main/kotlin/com/labteto/dshmobile/runtime/AndroidRuntimePlugin.kt",
    "github": "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/github/GitHubConnectorPlugin.kt",
    "lsp": "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/lsp/LspPlugin.kt",
    "local_builtin_catalog": "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolCatalog.kt",
    "automation": "app/src/main/java/com/labteto/dshmobile/automation/AutomationPlugin.kt",
    "webhook": "app/src/main/java/com/labteto/dshmobile/automation/HarnessWebhook.kt",
    "vision": "app/src/main/java/com/labteto/dshmobile/local/vision/LocalVisionPlugin.kt",
    "mcp_management": "harness-interop/src/main/kotlin/com/labteto/dshmobile/interop/mcp/McpToolBridgePlugin.kt",
}

def read(path):
    return (ROOT / path).read_text(encoding="utf-8", errors="replace")

def declares(code, symbol):
    return bool(re.search(DECL % re.escape(symbol), code))

def parse_markdown():
    fence = re.escape(chr(96) * 3)
    m = re.search(fence + r"text\s*\n(.*?)\n" + fence, read("docs/FEATURE-TREE.zh-CN.md"), re.S)
    if not m:
        raise ValueError("中文功能树不存在")
    all_paths, stack = [], []
    for line in m.group(1).splitlines()[1:]:
        if not line.strip():
            continue
        match = re.match(r"^([│ ]*)([├└])─ (.+)$", line)
        if not match or len(match.group(1)) % 3:
            raise ValueError("功能树文本层级无效：" + line)
        depth = len(match.group(1)) // 3
        if depth > len(stack):
            raise ValueError("功能树发生层级跳跃")
        name = re.sub(r"\s+\[(?:来源 )?PR #\d+[^\]]*\]$", "", match.group(3))
        stack = stack[:depth] + [name]
        all_paths.append(" / ".join(stack))
    leaves = [name for i, name in enumerate(all_paths)
              if i + 1 == len(all_paths) or not all_paths[i + 1].startswith(name + " / ")]
    return all_paths, leaves

def parse_html():
    m = re.search(r"const data=(\{.*?\});const tree=", read("docs/FEATURE-TREE.zh-CN.html"), re.S)
    if not m:
        raise ValueError("交互树真实结构不存在")
    all_paths, leaves = [], []
    def visit(node, stack):
        for child in node["children"]:
            path = stack + [child["name"]]
            name = " / ".join(path)
            all_paths.append(name)
            if not child["children"]:
                leaves.append(name)
            visit(child, path)
    visit(json.loads(m.group(1)), [])
    return all_paths, leaves

def source_files():
    return sorted(file for file in ROOT.rglob("*") if file.is_file()
                  and not any(v in SKIP for v in file.relative_to(ROOT).parts)
                  and "/src/main/" in "/" + file.relative_to(ROOT).as_posix()
                  and file.suffix in (".kt", ".java"))

def routes():
    code = read("app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt")
    def values(name):
        match = re.search(r"enum class " + name + r"\s*\{([^}]*)\}", code, re.S)
        if not match:
            raise ValueError("没有真实枚举：" + name)
        return re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$", match.group(1), re.M)
    mods, route = values("LocalFeatureModuleId"), values("LocalFeatureRoute")
    scoped = code.split("val modules: List<LocalFeatureModule>", 1)[1].split("private val ownerByRoute", 1)[0]
    assigned = re.findall(r"LocalFeatureRoute\.([A-Z0-9_]+)", scoped)
    if Counter(assigned) != Counter({v: 1 for v in route}):
        raise ValueError("路由归属源码不一致")
    return {"module_names": mods, "route_names": route}

def builtin_tools():
    result = []
    for provider, path in PROVIDERS.items():
        code = read(path)
        if provider == "device":
            names = re.findall(r'\bSpec\(\s*"([a-z][a-z0-9_]*)"\s*,\s*"([a-z][a-z0-9_]*)"', code)
            names = [v[0] for v in names]
        elif provider == "lsp":
            block = re.search(r"private val names\s*=\s*listOf\((.*?)\)", code, re.S)
            if not block:
                raise ValueError("LSP注册表消失")
            names = re.findall(r'"(lsp_[a-z_]+)"', block.group(1))
        elif provider == "local_builtin_catalog":
            # LocalBuiltinPlugin iterates the 64 LocalToolCatalog.specs entries.
            if "LocalToolCatalog.specs" not in read("app/src/main/java/com/labteto/dshmobile/local/tools/LocalBuiltinPlugin.kt"):
                raise ValueError("本机工具目录未真正接入插件注册")
            names = re.findall(r'\badd\(\s*tool\(\s*"([a-z][a-z0-9_]*)"', code)
        else:
            names = re.findall(r'\bname\s*=\s*"([a-z][a-z0-9_]*)"', code)
        # 同一工具在 HarnessTool 与工具 schema 中可能重复声明其名称。
        # 这类元数据重复不能误算为两个实际工具入口。
        if not names:
            raise ValueError("具名工具注册为空：" + provider)
        if provider == "device" and len(set(names)) != len(names):
            raise ValueError("Android 工具 Spec 重复注册")
        result += [{"provider": provider, "name": n, "source": path} for n in sorted(set(names))]
    return sorted(result, key=lambda x: (x["provider"], x["name"]))

def screen_candidates(sources):
    result = []
    for filename, code in sources.items():
        if "/ui/" not in "/" + filename:
            continue
        for m in re.finditer(r"@Composable\s+(?:(?:internal|private|public)\s+)?fun\s+(\w*Screen\w*)\s*\(", code):
            result.append({"symbol": m.group(1), "source": filename,
                           "is_named_screen": m.group(1).endswith("Screen")})
    return sorted(result, key=lambda x: (x["symbol"], x["source"]))

def audit():
    nodes, leaves = parse_markdown()
    htmlnodes, htmlleaves = parse_html()
    if nodes != htmlnodes or leaves != htmlleaves or len(nodes) != len(set(nodes)):
        raise ValueError("功能树两种展示的层级及叶节点不一致")
    files = source_files()
    sources = {p.relative_to(ROOT).as_posix(): p.read_text(encoding="utf-8", errors="replace") for p in files}
    idx = json.loads(read("docs/FEATURE-SOURCE-INDEX.auto.json"))
    review = json.loads(read("docs/FEATURE-REVIEW.auto.json"))
    matrix = json.loads(read("docs/ACCEPTANCE-MATRIX.json"))
    identities = json.loads(read("docs/FEATURE-TREE-LEGACY-IDENTITIES.json"))
    if len(files) != idx["production_source_count"]:
        raise ValueError("独立扫描生产文件数量与工作流结果不一致")
    hashes = [(p.relative_to(ROOT).as_posix(), hashlib.sha256(p.read_bytes()).hexdigest()[:16]) for p in files]
    if hashes != [(x["path"], x["sha256"]) for x in idx["production_file_fingerprints"]]:
        raise ValueError("全生产文件真实指纹与索引不一致")
    if [r["path"] for r in review["leaf_evidence"]] != leaves:
        raise ValueError("逐项证据台账和树叶数量或顺序不符")
    by_grade = Counter()
    crosswalk = []
    for item in review["leaf_evidence"]:
        path, label = item["path"], GRADES.get(item["verification"])
        if label is None:
            raise ValueError("出现未知证据等级：" + item["verification"])
        for source in item["existing_production_sources"]:
            if source not in sources:
                raise ValueError("工作流宣称的真实源码不存在：" + path)
        for symbol in item["named_symbols"]:
            if symbol["declaration_found"] and not any(declares(sources[src], symbol["symbol"]) for src in item["existing_production_sources"]):
                raise ValueError("功能具名声明在真实源码中不存在：" + path)
        if label in ("named_code_declaration", "unexecuted_instrumentation") and not any(x["declaration_found"] for x in item["named_symbols"]):
            raise ValueError("具名实现证据没有真实入口：" + path)
        if label == "ci_workflow_configuration" and not item.get("workflow_evidence"):
            raise ValueError("工程CI证据实际上无作业：" + path)
        by_grade[label] += 1
        crosswalk.append({"path": path, "layer": path.split(" / ", 1)[0], "evidence_level": label,
                          "static_identity": item.get("identity_id"),
                          "source_files": item["existing_production_sources"],
                          "test_references": item["test_reference_count"],
                          "workflow_job_count": len(item.get("workflow_evidence", [])),
                          "unrun_instrumentation_tests": len(item.get("explicit_test_candidates", []))})
    real_route = routes()
    if real_route["route_names"] != idx["registered_routes"]:
        raise ValueError("真实源码路由枚举与原扫描器报告不符")
    screens = screen_candidates(sources)
    if [{k: x[k] for k in ("symbol", "source")} for x in screens] != idx["candidate_ui_screens"]:
        raise ValueError("独立Compose入口扫描与现行工作流矛盾")
    tools = builtin_tools()
    # Find all production HarnessPlugin implementers without assuming the four
    # enumerated providers are the entire tool system.
    plugins = sorted(
        [{"class": match.group(1), "source": file}
         for file, body in sources.items() if "HarnessPlugin" in body
         for match in re.finditer(r"\bclass\s+([A-Za-z_]\w*Plugin)\s*\(", body)],
        key=lambda item: (item["source"], item["class"]),
    )
    audited_providers = set(PROVIDERS.values()) | {"app/src/main/java/com/labteto/dshmobile/local/tools/LocalBuiltinPlugin.kt"}
    other_plugins = [item for item in plugins if item["source"] not in audited_providers]
    all_methods = set()
    for item in matrix["items"]:
        if item["path"] not in leaves:
            raise ValueError("测试矩阵引用树外功能")
        for ref in item["evidence"]:
            file, sep, method = ref.rpartition("#")
            if not sep or not (ROOT / file).is_file() or not re.search(r"\bfun\s+" + re.escape(method) + r"\s*\(", read(file)):
                raise ValueError("测试矩阵引用的真实方法不存在：" + ref)
            all_methods.add(ref)
    if len(matrix["items"]) != review["existing_executable_test_mappings"]:
        raise ValueError("矩阵中有测试方法映射的功能数不一致")
    if len(identities["bindings"]) != review["tracked_legacy_identities"]:
        raise ValueError("静态功能ID数量不一致")
    for item in identities["bindings"]:
        if item["path"] not in leaves or item["source"] not in sources or not declares(sources[item["source"]], item["entry"]):
            raise ValueError("只读功能ID失效：" + item["id"])
    layers = Counter(v.split(" / ", 1)[0] for v in leaves)
    if not set(layers).issubset({"第一层｜产品功能与界面", "第二层｜共享运行能力", "第三层｜平台与工程保障"}):
        raise ValueError("功能树架构层级无效")
    unproven = [r["path"] for r in crosswalk if r["evidence_level"] == "no_code_entry"]
    if unproven != [r["path"] for r in review["unproven_feature_triage"]]:
        raise ValueError("旧工作流无证据功能清单遗漏")
    specific = sum(by_grade[k] for k in ("test_method_mapping", "named_code_declaration",
                                        "unexecuted_instrumentation", "ci_workflow_configuration"))
    weak = by_grade["historical_file_only"] + by_grade["candidate_file_only"]
    if specific + weak + len(unproven) != len(leaves):
        raise ValueError("各项证据数量没有完整覆盖全部树叶")
    return {
        "version": 1, "type": "independent_code_first_static_audit",
        "source_snapshot": idx["source_snapshot"],
        "tree": {"nodes_excluding_root": len(nodes), "leaf_descriptions": len(leaves),
                 "leaves_by_layer": dict(sorted(layers.items()))},
        "code": {"production_files": len(files), "module_count": len(real_route["module_names"]),
                 "module_names": real_route["module_names"], "route_count": len(real_route["route_names"]),
                 "route_names": real_route["route_names"], "compose_candidates": screens,
                 "compose_named_screens": sum(x["is_named_screen"] for x in screens),
                 "compose_support_components": sum(not x["is_named_screen"] for x in screens),
                 "registered_builtin_tools": tools,
                 "builtin_tool_count": len(tools),
                 "plugin_counts": dict(sorted(Counter(x["provider"] for x in tools).items())),
                 "production_harness_plugin_implementations": plugins,
                 "plugin_implementation_count": len(plugins),
                 "other_plugin_implementations_not_tool_enumerated": other_plugins,
                 "enumeration_scope": "Nine actual HarnessPlugin implementations: eight direct/static registration sites and the LocalBuiltinPlugin->LocalToolCatalog spec catalog; dynamically obtained MCP remote tools excluded",
                 "third_party_dynamic_mcp_tools_not_included": True},
        "tests": {"mapped_tree_leaves": len(matrix["items"]), "unique_test_method_refs": len(all_methods),
                  "android_test_methods_in_matrix": sum("/src/androidTest/" in x for x in all_methods),
                  "unit_test_methods_in_matrix": sum("/src/test/" in x for x in all_methods),
                  "source_present_does_not_mean_executed": True},
        "comparison": {"categories": dict(sorted(by_grade.items())),
                       "concrete_static_source_or_test_or_job_evidence": specific,
                       "weak_file_only_evidence": weak, "no_confirmed_entry": len(unproven),
                       "stable_static_identities": len(identities["bindings"]),
                       "unproven_paths": unproven, "all_444_paths_crosschecked": True,
                       "runtime_verified_independent_feature_count": None,
                       "runtime_count_limitation": "未逐功能证明调用可达、真实设备行为和模型效果；其中有工程及共享服务条目"},
        "per_leaf": crosswalk,
        "interpretation": "444是功能树末级描述，不是已验收的独立产品功能总数。路由、工具和页面数量口径不同不能相加。",
    }

def render_md(data):
    tree, code, tests, comp = data["tree"], data["code"], data["tests"], data["comparison"]
    lines = [
        "# 777 独立代码级功能审计",
        "",
        "独立读取仓库源码、工具注册、路由、Compose入口、测试映射及功能树，与原扫描器结果逐项对照。",
        "",
        f"源码快照：{data['source_snapshot']}；生产Kotlin/Java：{code['production_files']}个。",
        "",
        "## 独立识别的代码入口",
        "",
        f"- 产品所有权：{code['module_count']}个模块，{code['route_count']}条具名路由。",
        f"- Compose入口候选：{len(code['compose_candidates'])}个，其中以Screen结尾的{code['compose_named_screens']}个，辅助组件{code['compose_support_components']}个。",
        f"- 内置插件与本机工具目录中直接可列举的具名工具：{code['builtin_tool_count']}个注册声明（按提供者和名称去重），动态MCP远端工具不在此数。",
        f"- 实际发现HarnessPlugin实现：{code['plugin_implementation_count']}个，其中目录代理工具通过LocalBuiltinPlugin间接注册；未枚举的实现：{len(code['other_plugin_implementations_not_tool_enumerated'])}个。",
        "",
        "## 与实际功能树对照",
        "",
        f"中文结构树共有{tree['nodes_excluding_root']}个节点，末级功能描述{tree['leaf_descriptions']}项。",
        *[f"- {name}：{count}项" for name, count in tree["leaves_by_layer"].items()],
        "",
        f"具有具体静态源码、测试方法或CI作业证据：{comp['concrete_static_source_or_test_or_job_evidence']}项；"
        f"只有生产文件存在线索：{comp['weak_file_only_evidence']}项；"
        f"缺少可确认入口：{comp['no_confirmed_entry']}项。三类相加覆盖全部功能树叶节点。",
        "",
        "## 精确证据分层",
        "",
        "| 证据类别 | 末级描述数 |",
        "|---|---:|",
        *[f"| {name} | {count} |" for name, count in comp["categories"].items()],
        "",
        "## 测试和真实性限制",
        "",
        f"验收矩阵映射{tests['mapped_tree_leaves']}项描述和{tests['unique_test_method_refs']}条唯一测试方法，"
        f"其中Android仪表测试源码{tests['android_test_methods_in_matrix']}条、单元测试源码{tests['unit_test_methods_in_matrix']}条。",
        "",
        "代码级结论：444是产品与工程功能描述数量；具备具体静态证据的条目另行计数。"
        "现阶段无法把444认定为独立、可达、已运行的功能总数；部分条目为共享代码或工程门禁。"
        "对于缺少证据的条目，只列待核，不能仅凭扫描器删功能。",
        "",
        "## 未核实入口的功能描述",
        "",
        *[f"- {path}" for path in comp["unproven_paths"]],
        "",
        "逐项代码证据和四类内置插件工具入口清单见 FEATURE-REALITY-AUDIT.auto.json。",
        "",
    ]
    return "\n".join(lines)

def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--check", action="store_true")
    cli.add_argument("--self-test", action="store_true")
    cli.add_argument("--preview", action="store_true")
    args = cli.parse_args()
    assert declares("class Hello() {}", "Hello")
    assert not declares("class Hello() {}", "World")
    if args.self_test:
        print("独立功能审计自测通过")
        return
    data = audit()
    outputs = {OUT: json.dumps(data, ensure_ascii=False, indent=2) + "\n", NOTE: render_md(data)}
    if args.check:
        missing = [path.name for path, value in outputs.items()
                   if not path.is_file() or path.read_text(encoding="utf-8") != value]
        if missing:
            raise SystemExit("独立审计产物尚未同步：" + ", ".join(missing))
    elif not args.preview:
        for path, value in outputs.items():
            path.write_text(value, encoding="utf-8")
    print("独立实况盘点：树叶", data["tree"]["leaf_descriptions"],
          "路由", data["code"]["route_count"], "具名插件工具", data["code"]["builtin_tool_count"],
          "明确静态证据", data["comparison"]["concrete_static_source_or_test_or_job_evidence"],
          "仅文件线索", data["comparison"]["weak_file_only_evidence"],
          "无确认入口", data["comparison"]["no_confirmed_entry"])

if __name__ == "__main__":
    main()
