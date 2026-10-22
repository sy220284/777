#!/usr/bin/env python3
"""777 全仓静态调用关系扫描：输出逐文件索引；仅静态证据，不替代 Kotlin 编译器解析。"""
from __future__ import annotations
import ast
import collections
import hashlib
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "callgraph-audit"
OUT.mkdir(parents=True, exist_ok=True)
paths = [p.decode() for p in subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).split(b"\0") if p]
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()

def kind(path):
    if re.search(r"/src/main/.*\.(kt|java)$", path): return "production"
    if re.search(r"/src/(test|androidTest)/.*\.(kt|java)$", path): return "test"
    if re.search(r"\.(py|sh|bash|kts|js|ts)$", path) or path.endswith((".gradle", ".yml", ".yaml")): return "support-code"
    return "non-code"

def expected_package(path):
    match = re.search(r"/src/(?:main|test|androidTest)/(?:kotlin|java)/(.*)/[^/]+$", path)
    return match.group(1).replace("/", ".") if match else None

records = []
by_symbol = collections.defaultdict(set)
by_pkg = collections.defaultdict(set)
for path in paths:
    file = ROOT / path
    if not file.is_file():
        continue
    type_ = kind(path)
    row = {"path": path, "kind": type_, "module": path.split("/")[0],
           "size": file.stat().st_size, "package": None, "imports": [],
           "declarations": [], "import_edges": [], "call_edges": [],
           "potential_package_mismatch": False, "support_references": []}
    records.append(row)
    if type_ == "non-code":
        continue
    content = file.read_text(encoding="utf-8", errors="replace")
    row["sha256"] = hashlib.sha256(content.encode()).hexdigest()
    if type_ in ("production", "test"):
        package = re.search(r"(?m)^\s*package\s+([\w.]+)", content)
        row["package"] = package.group(1) if package else ""
        row["potential_package_mismatch"] = (expected_package(path) is not None and expected_package(path) != row["package"])
        row["imports"] = re.findall(r"(?m)^\s*import\s+([\w.*]+)", content)
        row["aliases"] = dict(re.findall(r"(?m)^\s*import\s+([\w.]+)\s+as\s+(\w+)", content))
        row["declarations"] = sorted(set(
            re.findall(r"\b(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)", content) +
            re.findall(r"\bfun\s+(?:<[^>\n]+>\s*)?(?:[\w?.<>]+\.)?([A-Za-z_]\w*)\s*\(", content) +
            re.findall(r"\b(?:enum|annotation|data|sealed|value)\s+class\s+([A-Za-z_]\w*)", content)
        ))
        # 仅登记零缩进 Kotlin 顶层属性/扩展属性，避免把局部变量误认作外部符号。
        row["properties"] = sorted(set(re.findall(
            r"(?m)^(?:(?:internal|private|public|protected|lateinit|const)\\s+)*(?:val|var)\\s+(?:[\\w?.<>]+\\.)?([A-Za-z_]\\w*)\\b",
            content,
        )))
        row["call_tokens"] = collections.Counter(re.findall(r"\b([A-Za-z_]\w*)\s*\(", content))
        by_pkg[row["package"]].add(path)
        for name in row["declarations"] + row["properties"]:
            by_symbol[row["package"] + "." + name].add(path)
        # Kotlin/Java 文件名对应的常规顶层类型（含 Java 类和跨文件构造），作为补充候选。
        by_symbol[row["package"] + "." + pathlib.Path(path).stem].add(path)
    elif path.endswith(".py"):
        try:
            tree = ast.parse(content)
            row["support_references"] = sorted({n.module or "" for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)} |
                                                {a.name for n in ast.walk(tree) if isinstance(n, ast.Import) for a in n.names})
        except SyntaxError as e:
            row["parse_warning"] = str(e)
    elif path.endswith((".sh", ".bash")):
        row["support_references"] = re.findall(r"(?m)^\s*(?:source|\.)\s+([^\s#]+)", content)
    elif path.endswith(".kts"):
        row["support_references"] = re.findall(r"project\([\"'](:[\w-]+)[\"']\)", content)

file_index = {r["path"]: r for r in records}
unresolved = []
incoming = collections.Counter()
edges = collections.defaultdict(set)
call_edges = collections.defaultdict(set)
for row in records:
    if row["kind"] not in ("production", "test"):
        continue
    import_names = {}
    for imp in row["imports"]:
        if imp.endswith(".*"):
            for target in by_pkg.get(imp[:-2], []):
                if target != row["path"]:
                    edges[row["path"]].add(target)
            continue
        candidates = by_symbol.get(imp, set()) - {row["path"]}
        if not candidates and imp.startswith("com.labteto.dshmobile."):
            # 类成员、扩展方法、生成类型等无法用词法声明唯一定位；只记候选。
            parts = imp.split(".")
            for n in range(len(parts)-1, 3, -1):
                candidates |= by_symbol.get(".".join(parts[:n]), set()) - {row["path"]}
        if candidates:
            import_names[imp.split(".")[-1]] = candidates
            edges[row["path"]].update(candidates)
        elif imp.startswith("com.labteto.dshmobile."):
            unresolved.append({"path": row["path"], "import": imp, "reason": "静态词法索引未定位"})
    for token, targets in import_names.items():
        if row["call_tokens"].get(token):
            for t in targets:
                call_edges[row["path"]].add(t)
    for name, count in row["call_tokens"].items():
        if not count or name in import_names:
            continue
        candidates = by_symbol.get(row["package"] + "." + name, set()) - {row["path"]}
        if len(candidates) == 1:
            call_edges[row["path"]].update(candidates)
    row["import_edges"] = sorted(edges[row["path"]])
    row["call_edges"] = sorted(call_edges[row["path"]])
    row.pop("call_tokens", None)
    for to in edges[row["path"]]:
        incoming[to] += 1

# SCC：文件级显式 import 关系。Kotlin 单文件声明/通配 import 可能造成候选边，SCC 只能表示静态环。
index = 0
stack = []
onstack = set()
indices = {}
low = {}
components = []
def strong(v):
    global index
    indices[v] = low[v] = index
    index += 1
    stack.append(v)
    onstack.add(v)
    for w in edges.get(v, ()):
        if w not in indices:
            strong(w)
            low[v] = min(low[v], low[w])
        elif w in onstack:
            low[v] = min(low[v], indices[w])
    if low[v] == indices[v]:
        block = []
        while True:
            w = stack.pop()
            onstack.remove(w)
            block.append(w)
            if w == v:
                break
        if len(block) > 1:
            components.append(sorted(block))

sys.setrecursionlimit(max(10000, len(records) * 5))
for row in records:
    if row["kind"] in ("production", "test") and row["path"] not in indices:
        strong(row["path"])
components.sort(key=lambda g: (-len(g), g))
counts = dict(collections.Counter(r["kind"] for r in records))
modules = {k: dict(v) for k, v in
           ((mod, collections.Counter(r["kind"] for r in records if r["module"] == mod))
            for mod in sorted({r["module"] for r in records}))}
crossmodule = collections.Counter()
for frm, tos in edges.items():
    for to in tos:
        a, b = frm.split("/")[0], to.split("/")[0]
        if a != b:
            crossmodule[(a, b)] += 1
hot = sorted(((incoming[r["path"]], r["path"]) for r in records if r["kind"] == "production"), reverse=True)
orphans = sorted(r["path"] for r in records if r["kind"] == "production" and incoming[r["path"]] == 0)
mismatches = sorted(r["path"] for r in records if r["potential_package_mismatch"])
# 跨产品域内部引用只作为复核候选，不能以文件夹名判断违约。
crossfeature = []
for frm, tos in edges.items():
    if "/src/main/" not in frm:
        continue
    a = re.search(r"/local/(chat|work|tools|automation)/", frm)
    if not a:
        continue
    for to in tos:
        b = re.search(r"/local/(chat|work|tools|automation)/", to)
        if b and a.group(1) != b.group(1):
            crossfeature.append({"from": frm, "to": to})

result = {
    "schema": "777-static-callgraph-v1",
    "commit": revision,
    "method": "全 tracked 文件遍历；Kotlin/Java 词法 import 与调用标识符候选；非编译器/运行时精确解析",
    "counts": counts, "modules": modules,
    "import_edges": sum(map(len, edges.values())),
    "call_candidate_edges": sum(map(len, call_edges.values())),
    "cross_module": [{"from": a, "to": b, "edges": n} for (a,b),n in sorted(crossmodule.items())],
    "package_mismatch": mismatches,
    "unresolved_internal_imports": unresolved,
    "cross_feature_review_candidates": crossfeature,
    "strongly_connected_components": components,
    "unreferenced_production_candidates": orphans,
    "top_import_targets": [{"incoming": n, "path": p} for n,p in hot[:50]],
    "files": records,
}
(OUT/"callgraph.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
md = [
    "# 777 全仓逐文件静态调用关系审计",
    "",
    f"- Git 提交：\x60{revision}\x60",
    f"- Git 跟踪文件数：{len(paths)}；实际可读取文件：{len(records)}",
    f"- 生产源码：{counts.get('production',0)}；测试源码：{counts.get('test',0)}；支撑代码：{counts.get('support-code',0)}；非代码：{counts.get('non-code',0)}",
    f"- 静态 import 关系：{result['import_edges']}；直接标识符调用候选关系：{result['call_candidate_edges']}",
    "",
    "**证据边界**：这是逐文件文本索引，不是 Kotlin/Java 编译器调用图；重载、扩展函数、反射、依赖注入、动态分派与运行时入口无法仅由词法解析证明。所有未引用、跨域、循环、未解析结果均是复核候选，不直接断言缺陷。",
    "",
    "## 模块分布",
    "",
    "| 模块 | 生产 | 测试 | 支撑 | 非代码 |",
    "| --- | ---: | ---: | ---: | ---: |",
]
for module, cnt in modules.items():
    md.append(f"| {module} | {cnt.get('production',0)} | {cnt.get('test',0)} | {cnt.get('support-code',0)} | {cnt.get('non-code',0)} |")
md += ["", "## 跨 Gradle 模块直接引用", "", "| 来源 | 目标 | 静态边数 |", "| --- | --- | ---: |"]
for x in result["cross_module"]:
    md.append(f"| {x['from']} | {x['to']} | {x['edges']} |")
md += ["", "## 静态风险候选数量", "",
       f"- 包声明与目录不一致：{len(mismatches)}",
       f"- 内部 import 无法词法定位：{len(unresolved)}（生成代码和顶层符号可能产生误报）",
       f"- 跨产品域内部引用：{len(crossfeature)}",
       f"- import 强连通分量：{len(components)}",
       f"- 生产源码没有被本次 import 发现的入口：{len(orphans)}（不能作为死代码依据）",
       "", "## 入度最高的生产文件", "",
       "| 入度 | 文件 |", "| ---: | --- |"]
for n,p in hot[:35]:
    md.append(f"| {n} | \x60{p}\x60 |")
for title, values in [
    ("包目录不匹配候选", mismatches[:100]),
    ("跨产品域依赖候选", [f"{x['from']} → {x['to']}" for x in crossfeature[:100]]),
    ("无法词法定位的内部 import（前100）", [f"{x['path']} → {x['import']}" for x in unresolved[:100]]),
    ("静态循环引用（前30个分量）", [" ↔ ".join(g[:12]) + (f" ... 共{len(g)}文件" if len(g)>12 else "") for g in components[:30]]),
]:
    md.extend(["", "## " + title, ""])
    md.extend(["- " + v for v in values] or ["未发现"])
md += ["", "## 逐文件详细结果", "",
       "完整 2,000+ 文件明细、每个 Kotlin/Java 文件的包名、声明、imports、import_edges、call_edges、源文件 SHA256 与非代码分类均见 \x60callgraph.json\x60。",
       "人工验收需针对候选继续检查真实调用/DI 装配和可执行测试，不能把词法边等同于有效运行路径。"]
(OUT/"callgraph.md").write_text("\n".join(md)+"\n", encoding="utf-8")
print("CALLGRAPH_AUDIT_OK")
print(json.dumps({k:result[k] for k in ("commit","counts","import_edges","call_candidate_edges","cross_module")}, ensure_ascii=False))
print(json.dumps({"package_mismatch":len(mismatches),"unresolved_internal_imports":len(unresolved),
    "cross_feature_candidates":len(crossfeature),"cycles":len(components),"unreferenced_production_candidates":len(orphans)}, ensure_ascii=False))
