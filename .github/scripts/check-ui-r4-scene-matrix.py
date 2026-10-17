#!/usr/bin/env python3
"""R4 S0 guard: Scene catalog and every action must remain traceable, without false passes.

This is a structural gate, not a claim that the Android journey has been tested.
"""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs/UI-SCENE-MATRIX.json"
CATALOG = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/host/LocalUiSceneCatalog.kt"
CATALOG_LINE = re.compile(
    r'UiSceneRequirement\(UiSceneId\("([^"]+)"\),\s*UiSceneKind\.([A-Z]+),\s*([^,\n]+),\s*hostProvided\s*=\s*(true|false)\)'
)

def errors(matrix, catalog):
    issues = []
    scenes = matrix.get("scenes", [])
    # The 2026-10-16 scope decision is authoritative: never silently resurrect
    # a retired Next pack, legacy migration stage or mandatory device signoff.
    if "CODE_ONLY" not in str(matrix.get("scope", "")):
        issues.append("R4 acceptance scope drift: CODE_ONLY must remain explicit")
    for scene in scenes:
        sid = scene.get("id", "<unknown>")
        if "待设备验收" in str(scene.get("acceptance", "")):
            issues.append(f"retired mandatory-device acceptance: {sid}")
        implementation = str(scene.get("implementation", ""))
        if "Next" in implementation or "独立 Pack 待迁移" in implementation:
            issues.append(f"retired UI migration implementation claim: {sid}")
    actual = {}
    for match in CATALOG_LINE.finditer(catalog):
        identity, kind, owner, host = match.groups()
        if identity in actual:
            issues.append(f"duplicate catalog scene: {identity}")
        actual[identity] = (kind, owner.strip(), host == "true")
    # Every named Scene must point to a real, in-repository source, including
    # hidden overlays and SYSTEM entry points. A plausible Owner string is not proof
    # of an existing implementation file.
    documented = {}
    for scene in scenes:
        sid = scene.get("id")
        if not sid or sid in documented:
            issues.append(f"duplicate/missing matrix scene: {sid}")
            continue
        documented[sid] = scene
        definition = actual.get(sid)
        if not definition:
            issues.append(f"matrix scene missing in code: {sid}")
            continue
        kind, owner, host = definition
        if scene.get("kind") != kind:
            issues.append(f"scene kind mismatch: {sid}")
        if owner.startswith('"') and owner.endswith('"') and scene.get("owner") != owner[1:-1]:
            issues.append(f"scene owner mismatch: {sid}")
        actions = scene.get("actions")
        proof = scene.get("actionProof")
        if not isinstance(actions, list) or not actions or len(actions) != len(set(actions)):
            issues.append(f"missing/duplicate actions: {sid}")
            continue
        if not isinstance(proof, dict) or set(proof) != set(actions):
            issues.append(f"missing/stale action evidence keys: {sid}")
            continue
        if not scene.get("source") or not scene.get("recovery") or not scene.get("acceptance"):
            issues.append(f"missing source/recovery/acceptance: {sid}")
        source = scene.get("source")
        if isinstance(source, str) and source.strip():
            source_file = (ROOT / source).resolve()
            if not source_file.is_relative_to(ROOT) or not source_file.is_file():
                issues.append(f"missing/out-of-repository Scene source: {sid}/{source}")
        for name, record in proof.items():
            if not isinstance(record, dict):
                issues.append(f"invalid action evidence: {sid}/{name}")
                continue
            status = record.get("status")
            if status not in ("PENDING_EVIDENCE", "VERIFIED"):
                issues.append(f"unknown status: {sid}/{name}")
            if status == "VERIFIED" and any(
                not isinstance(record.get(key), str) or not record[key].strip()
                for key in ("ownerPort", "receiptType", "restoreTest")
            ):
                issues.append(f"claimed verified without owner/receipt/recovery proof: {sid}/{name}")
    issues.extend(f"undocumented required scene: {sid}" for sid in sorted(actual.keys() - documented.keys()))
    # R4 final scope: only V6, seven owned routes, 21 Settings destinations,
    # 78 scene contracts, 75 Pack renderers and three Host-native system scenes.
    if len(scenes) != 78 or len(actual) != 78:
        issues.append(f"scene count drift: matrix={len(scenes)}, catalog={len(actual)} (expected 78)")
    if sum(len(scene.get("actions", [])) for scene in scenes) != 173:
        issues.append("action count drift: required 173")
    if len({scene.get("route") for scene in scenes}) != 7:
        issues.append("top-level route drift: required 7")
    if sum(scene.get("id", "").startswith("settings.") and
           scene.get("kind") == "PAGE" for scene in scenes) != 21:
        issues.append("settings destination drift: required 21")
    if sum(host for _, _, host in actual.values()) != 3:
        issues.append("host-only scene drift: required 3")
    # Current route and settings inventories are sourced from native Feature
    # owners. Checking only counts could let a renamed/retired entry silently pass.
    features = (ROOT / "app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt").read_text(encoding="utf-8")
    settings = (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/settings/SettingsScreen.kt").read_text(encoding="utf-8")
    native_routes = re.search(r"internal enum class LocalFeatureRoute\s*\{([^}]+)\}", features, re.S)
    native_settings = re.search(r"enum class SettingsDestination\s*\{([^}]+)\}", settings, re.S)
    def names(match):
        return set(re.findall(r"^\s*([A-Z][A-Z_]+)\s*(?:,|$)", match.group(1), re.M)) if match else set()
    route_ids = {"route." + name.lower() for name in names(native_routes)}
    settings_ids = {"settings." + name.lower() for name in names(native_settings)}
    documented_routes = {sid for sid in documented if sid.startswith("route.")}
    documented_settings = {sid for sid, scene in documented.items()
                           if sid.startswith("settings.") and scene.get("kind") == "PAGE"}
    if len(route_ids) != 7 or documented_routes != route_ids:
        issues.append("native route identities differ from the Scene matrix")
    if len(settings_ids) != 21 or documented_settings != settings_ids:
        issues.append("native SettingsDestination identities differ from the Scene matrix")
    for scene in scenes:
        route = scene.get("route")
        if route not in {name.upper() for name in names(native_routes)}:
            issues.append(f"Scene routed to retired/unknown feature: {scene.get('id')}/{route}")
    for scene in scenes:
        for name, record in scene.get("actionProof", {}).items():
            if not isinstance(record, dict):
                continue
            for field in ("ownerPort", "receiptType"):
                if not isinstance(record.get(field), str) or not record[field].strip():
                    issues.append(f"missing source mapped {field}: {scene.get('id')}/{name}")
            if "[Next]" in str(record.get("ownerPort", "")):
                issues.append(f"retired Next owner mapping: {scene.get('id')}/{name}")
    return issues

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    catalog = CATALOG.read_text(encoding="utf-8")
    data = json.loads(MATRIX.read_text(encoding="utf-8"))
    if args.self_test:
        assert not errors(data, catalog), errors(data, catalog)
        import copy
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["actionProof"].pop(tampered["scenes"][0]["actions"][0])
        assert any("missing/stale" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        first = tampered["scenes"][0]["actions"][0]
        tampered["scenes"][0]["actionProof"][first]["status"] = "VERIFIED"
        assert any("claimed verified" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["actionProof"][first]["ownerPort"] = ""
        assert any("missing source mapped ownerPort" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["actionProof"][first]["ownerPort"] = "Old [Next]"
        assert any("retired Next owner" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scope"] = "historical device-only"
        assert any("CODE_ONLY" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["acceptance"] = "待设备验收"
        assert any("mandatory-device" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["implementation"] = "V6 现行入口；独立 Pack 待迁移"
        assert any("retired UI migration" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"].pop()
        assert any("scene count drift" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["source"] = "/invalid/outside/project.kt"
        assert any("missing/out-of-repository Scene source" in x for x in errors(tampered, catalog))
        tampered = copy.deepcopy(data)
        tampered["scenes"][0]["route"] = "RUN_CENTER"
        assert any("retired/unknown feature" in x for x in errors(tampered, catalog))
        print("UI R4 S0 scene guard self-tests OK")
        return
    issues = errors(data, catalog)
    if issues:
        for problem in issues:
            print(f"::error::{problem}")
        raise SystemExit(1)
    pending = sum(
        item["status"] == "PENDING_EVIDENCE"
        for scene in data["scenes"] for item in scene["actionProof"].values()
    )
    print(f"Scene matrix structure valid: {len(data['scenes'])} scenes; {pending} actions still lack verification evidence.")

if __name__ == "__main__":
    main()
