#!/usr/bin/env python3
"""Trace every unproven and file-only feature into declarations, integrations and tests.

Read-only source analysis; *never* treat text matches, test declarations or CI
configuration as proof that a user-facing behavior runs successfully.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs"
REVIEW = DOC / "FEATURE-REVIEW.auto.json"
EXISTENCE = DOC / "FEATURE-EXISTENCE-TRIAGE.json"
OUTPUT = DOC / "FEATURE-EXISTENCE-AUDIT.auto.json"
SUMMARY = DOC / "FEATURE-EXISTENCE-AUDIT.zh-CN.md"
SKIP = {".git", ".gradle", "build", "node_modules", "__pycache__", ".venv"}
SRC_EXT = (".kt", ".java")
DECL = r"(?:fun|class|object|interface|val|var|enum\s+class)\s+%s\b"
DECL_CLASS = r"\b(?:class|object|interface|enum\s+class)\s+%s\b"
TEST_METHOD = r"@Test\s*(?:\n\s*)?fun\s+%s\s*\("

def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8", errors="replace")

def files_under(part: str) -> dict[str, str]:
    records = {}
    for file in ROOT.rglob("*"):
        if not file.is_file() or file.suffix not in SRC_EXT:
            continue
        path = file.relative_to(ROOT).as_posix()
        if any(x in SKIP for x in Path(path).parts) or part not in "/" + path:
            continue
        records[path] = file.read_text(encoding="utf-8", errors="replace")
    return dict(sorted(records.items()))

def first_declared_source(owned_files: list[str], contents: dict[str, str]) -> str | None:
    for source in owned_files:
        stem = Path(source).stem
        if source in contents and re.search(DECL_CLASS % re.escape(stem), contents[source]):
            return stem
    return None

def source_symbols(source: str) -> list[str]:
    """Include Kotlin top-level/extension functions; no claim of reachability."""
    declared = re.findall(
        r"\b(?:class|object|interface|enum\s+class)\s+([A-Za-z_]\w*)\b", source
    )
    declared += re.findall(
        r"\bfun\s+(?:(?:[A-Za-z_]\w*|[A-Za-z_]\w*<[^>]+>)\.)?([A-Za-z_]\w*)\s*\(",
        source,
    )
    excluded = {"invoke", "get", "set", "read", "write", "open", "close",
                "list", "save", "load", "status", "update", "copy",
                "create", "delete", "run", "start", "stop", "init"}
    return sorted({value for value in declared if len(value) >= 7 and value not in excluded})

def analyze():
    baseline = json.loads(REVIEW.read_text(encoding="utf-8"))
    manifest = json.loads(EXISTENCE.read_text(encoding="utf-8"))
    if manifest.get("version") != 1 or not isinstance(manifest.get("items"), list):
        raise ValueError("人工逐项核实台账格式无效")
    evidence = {r["path"]: r for r in baseline["leaf_evidence"]}
    expected = [r["path"] for r in baseline["leaf_evidence"]
                if r["verification"] == "没有可确认的现行入口证据"]
    recorded = [r["path"] for r in manifest["items"]]
    if len(expected) != 14 or len(recorded) != 14 or sorted(expected) != sorted(recorded):
        raise ValueError("14条既有缺口未完整核实，禁止以模糊名称代替路径")
    prod = files_under("/src/main/")
    tests = {p: body for p, body in files_under("/src/test/").items()}
    tests.update(files_under("/src/androidTest/"))
    # Token postings are built once for the whole repository: avoiding an O(features * symbols * files)
    # nested regexp scan is important for CI latency as the codebase grows.
    production_postings = defaultdict(set)
    test_postings = defaultdict(set)
    token_pattern = re.compile(r"\b[A-Za-z_]\w*\b")
    for path, body in prod.items():
        for token in set(token_pattern.findall(body)):
            production_postings[token].add(path)
    for path, body in tests.items():
        if "@Test" in body:
            for token in set(token_pattern.findall(body)):
                test_postings[token].add(path)
    grouped_source = defaultdict(list)
    for r in baseline["leaf_evidence"]:
        for source in r.get("existing_production_sources", []):
            grouped_source[source].append(r["path"])
    resolved = []
    for item in manifest["items"]:
        src, test = item.get("source"), item.get("test")
        if not isinstance(src, list) or not isinstance(test, list) or not test:
            raise ValueError("人工功能状态必须至少有一个实际测试方法")
        if not isinstance(item.get("independent"), bool) or not item.get("conclusion"):
            raise ValueError("必须明确独立入口判断及功能边界说明")
        for candidate in src:
            path, symbol = candidate["path"], candidate["symbol"]
            if path not in prod or not re.search(DECL % re.escape(symbol), prod[path]):
                raise ValueError("人工判断中的具名生产源码已失效：" + item["path"] + ":" + symbol)
        for candidate in test:
            path, method = candidate["path"], candidate["method"]
            if path not in tests or not re.search(TEST_METHOD % re.escape(method), tests[path]):
                raise ValueError("声称存在的JUnit测试方法找不到：" + path + "#" + method)
        if item["kind"] == "test_suite_not_product_feature" and src:
            raise ValueError("只表示测试范围的节点不能冒充自己的产品实现")
        if item["kind"] != "test_suite_not_product_feature" and not src:
            raise ValueError("存在/兼容/部分子功能必须有明确生产源码实现")
        resolved.append({
            "path": item["path"],
            "structural_kind": item["kind"],
            "separate_product_entry": item["independent"],
            "availability": item["availability"],
            "production_declarations": src,
            "test_methods_declared": test,
            "can_use_without_runtime_test": None,
            "conclusion": item["conclusion"],
            "verification": "真实生产声明或测试方法通过再次读取核实；没有设备/模型执行结论",
        })
    weak_evidence = [r for r in baseline["leaf_evidence"]
                     if r["verification"] in (
                         "历史生产文件仍存在（待入口复核）",
                         "候选生产文件仍存在（不足以证明功能）"
                     )]
    if len(weak_evidence) != 76:
        raise ValueError("原报告弱文件证据数量已变化，需更新人工溯源基线")
    weak = []
    for r in weak_evidence:
        sources = r["existing_production_sources"]
        for path in sources:
            if path not in prod:
                raise ValueError("弱证据指向已经消失的源码文件：" + path)
        main_symbol = first_declared_source(sources, prod)
        owned_symbols = sorted({name for src in sources for name in source_symbols(prod[src])})
        # Named class has priority; for Kotlin extension/file-level code also
        # sample distinctive functions. Every hit is only a text reference.
        candidates = ([main_symbol] if main_symbol else []) + [
            name for name in owned_symbols if name != main_symbol
        ]
        traces = []
        for symbol in candidates[:90]:
            used_by = sorted(production_postings.get(symbol, set()).difference(sources))
            tested_by = sorted(test_postings.get(symbol, set()))
            if used_by or tested_by:
                traces.append((symbol, used_by, tested_by))
        traces.sort(key=lambda x: (len(x[1]) > 0, len(x[2]) > 0, len(x[1]) + len(x[2]), len(x[0])), reverse=True)
        chosen = traces[0] if traces else (main_symbol, [], [])
        chosen_name, used_by, tested_by = chosen
        shared_paths = sorted({path for src in sources for path in grouped_source[src]})
        weak.append({
            "path": r["path"],
            "file_sources": sources,
            "primary_declaration": main_symbol,
            "other_named_declarations_in_source": owned_symbols[:24],
            "best_static_cross_file_symbol": chosen_name,
            "source_file_shared_by_tree_descriptions": len(shared_paths),
            "source_is_shared_in_feature_tree": len(shared_paths) > 1,
            "other_production_symbol_reference_count": len(used_by),
            "other_production_reference_samples": used_by[:7],
            "test_file_symbol_reference_count": len(tested_by),
            "test_reference_samples": tested_by[:7],
            "separate_product_entry": None,
            "static_integration_hint": (
                "candidate_external_source_reference" if used_by
                else "only_test_source_reference" if tested_by
                else "no_external_symbol_reference_found"
            ),
            "source_uniqueness_hint": (
                "tree_leaves_share_source" if len(shared_paths) > 1
                else "source_associated_with_one_tree_leaf"
            ),
            "availability": "source_code_present_actual_invocation_not_proven",
            "verification": "源码中类/对象及Kotlin具名方法已读取；跨文件文本引用仍非编译解析调用链或实际运行验证",
        })
    summary = {
        "previously_unproven_investigated": len(resolved),
        "resolved_existing_code_or_compatibility": sum(bool(x["production_declarations"]) for x in resolved),
        "resolved_test_only_coverage_descriptions": sum(not x["production_declarations"] for x in resolved),
        "explicitly_retired_controller_compatibility": sum(x["structural_kind"] == "retired_controller_compatibility" for x in resolved),
        "partial_scope_descriptions": sum(x["structural_kind"] == "partial_expression_guidance" for x in resolved),
        "weak_source_entries_traced": len(weak),
        "weak_source_with_named_class_or_object": sum(x["primary_declaration"] is not None for x in weak),
        "weak_source_with_distinctive_named_declarations": sum(bool(x["other_named_declarations_in_source"]) for x in weak),
        "weak_source_referenced_by_other_production": sum(x["other_production_symbol_reference_count"] > 0 for x in weak),
        "weak_source_referenced_by_tests": sum(x["test_file_symbol_reference_count"] > 0 for x in weak),
        "weak_source_reused_across_tree_leaves": sum(x["source_file_shared_by_tree_descriptions"] > 1 for x in weak),
        "weak_source_without_external_or_test_symbol_reference": sum(
            not x["other_production_symbol_reference_count"]
            and not x["test_file_symbol_reference_count"] for x in weak
        ),
        "runtime_verified_usable_count": None,
    }
    return {
        "version": 1,
        "source_snapshot": baseline["snapshot"],
        "scope": "previously_14_unproven_plus_76_file_only_description_traces",
        "interpretation": "独立实现：是否有独立产品入口；能用：必须证明执行路径和实际行为。源码和测试存在无法自动证明可用。",
        "summary": summary,
        "unproven_manual_investigation": resolved,
        "weak_evidence_code_traces": weak,
        "limits": [
            "本检查只证明源码声明、其他代码的符号引用、测试文件与@Test方法确实存在",
            "变量名、类引用和源码文件共享无法独立证明行为可达或用户确实能调用",
            "所有涉及设备、模型、账号与第三方服务的功能必须保留真实验收步骤",
            "旧版行为控制已退出，新版温度采样仍可作为人物设置子功能",
            "工程回归测试节点应与产品功能数量分开统计",
        ],
    }

def render(data: dict) -> str:
    s = data["summary"]
    lines = [
        "# 777 功能独立存在与可用性调查",
        "",
        "对上一轮14项无确认入口和76项仅有文件级线索进行重新检查。"
        "这是代码级证据报告，运行验收不足时将状态标为待验证。",
        "",
        "## 概览",
        "",
        f"- 原14项不明：{s['resolved_existing_code_or_compatibility']}项具有具名生产实现/兼容入口，"
        f"{s['resolved_test_only_coverage_descriptions']}项实质为工程回归测试范围。",
        f"- 已确认退出主动使用的旧控制器：{s['explicitly_retired_controller_compatibility']}项，仅保留数据兼容；"
        f"部分实现范围尚不完整的描述：{s['partial_scope_descriptions']}项。",
        f"- 文件级弱证据：{s['weak_source_entries_traced']}项；有与文件名对应的主要类/对象声明"
        f"{s['weak_source_with_named_class_or_object']}项，能够发现其他具名声明的"
        f"{s['weak_source_with_distinctive_named_declarations']}项；"
        f"在其他生产文件发现同名符号引用{s['weak_source_referenced_by_other_production']}项、"
        f"在测试文件发现同名符号{s['weak_source_referenced_by_tests']}项。",
        f"- {s['weak_source_reused_across_tree_leaves']}项弱证据条目与其他树叶共用同一生产文件。"
        f"{s['weak_source_without_external_or_test_symbol_reference']}项未找到外部源码或测试符号引用，应优先核实是否纯内部实现、入口断链或待退役代码。"
        "共用源码本身不能证明某项功能独立或失效。",
        "",
        "## 原14项逐项独立性与使用判断",
        "",
        "| 功能描述 | 独立产品入口 | 代码层判断 |",
        "|---|---|---|",
    ]
    for item in data["unproven_manual_investigation"]:
        name = item["path"].split(" / ")[-1].replace("|", "｜")
        kind = item["structural_kind"]
        independent = "有" if item["separate_product_entry"] else "未发现单独入口"
        lines.append(f"| {name} | {independent} | {kind}；{item['availability']} |")
    lines += [
        "",
        "明确的源码引用和测试方法，以及各项中文结论见 JSON 明细。"
        "以上“存在测试”只证明可执行测试代码的存在，未核实本次设备/模型运行通过。",
        "",
        "## 仍需调查的76项",
        "",
        "所有76项均逐项记录真实生产文件、具名类或函数、跨文件引用及测试关联。"
        "多功能共用源码时，独立入口仍须看真实调用和用户行为。",
        "",
        "| 功能描述 | 源码入口线索 | 其他生产引用文件 | 测试引用文件 | 共用源文件 |",
        "|---|---|---:|---:|---|",
        *[
            "| " + item["path"].split(" / ")[-1].replace("|", "｜") +
            " | " + (item["best_static_cross_file_symbol"] or "仅发现源文件").replace("|", "｜") +
            " | " + str(item["other_production_symbol_reference_count"]) +
            " | " + str(item["test_file_symbol_reference_count"]) +
            " | " + ("是" if item["source_is_shared_in_feature_tree"] else "尚未发现") + " |"
            for item in data["weak_evidence_code_traces"]
        ],
        "",
        "其中“尚未发现共用源文件”不意味着拥有独立产品入口，其他源码/测试文件含同名符号也不证明调用链真正可达。",
        "",
        "## 最终边界",
        "",
        "当前可以确认部分功能是现行子能力、部分是历史兼容、部分只是回归测试范围。"
        "真实独立可用功能总数仍需要交互路径、设备/服务及模型执行结果支持。"
        "旧树中的14项和76项在具备充分退役依据前都保留，不自动删除。",
        "",
    ]
    return "\n".join(lines)

def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--check", action="store_true")
    cli.add_argument("--self-test", action="store_true")
    args = cli.parse_args()
    assert re.search(TEST_METHOD % "sample", "@Test\n    fun sample() {}")
    assert re.search(DECL % "Example", "class Example()")
    assert "canonicalV4" in source_symbols("internal fun PersonaProfile.canonicalV4(): PersonaProfile = this")
    if args.self_test:
        print("功能存在性溯源自测通过")
        return
    data = analyze()
    outputs = {OUTPUT: json.dumps(data, ensure_ascii=False, indent=2) + "\n",
               SUMMARY: render(data)}
    if args.check:
        stale = [file.name for file, expected in outputs.items()
                 if not file.is_file() or file.read_text(encoding="utf-8") != expected]
        if stale:
            raise SystemExit("存在性调查产物与实际源码不一致：" + ", ".join(stale))
    else:
        for file, content in outputs.items():
            file.write_text(content, encoding="utf-8")
    print("存在性调查：14项人工归类、76项弱证据引用溯源；"
          "分类不等于运行成功", data["summary"])

if __name__ == "__main__":
    main()
