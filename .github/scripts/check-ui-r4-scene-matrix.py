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
    actual = {}
    for match in CATALOG_LINE.finditer(catalog):
        identity, kind, owner, host = match.groups()
        if identity in actual:
            issues.append(f"duplicate catalog scene: {identity}")
        actual[identity] = (kind, owner.strip(), host == "true")
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
