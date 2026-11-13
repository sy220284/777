#!/usr/bin/env python3
"""Fast Android security-boundary checks for manifests and FileProvider exposure."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
violations: list[str] = []


def attr(node: ET.Element, name: str) -> str | None:
    return node.attrib.get(ANDROID + name)


app_manifest = ET.parse(ROOT / "app" / "src" / "main" / "AndroidManifest.xml").getroot()
application = app_manifest.find("application")
if application is None:
    violations.append("app manifest is missing <application>")
else:
    if attr(application, "allowBackup") != "false":
        violations.append("app backup must remain disabled")
    if attr(application, "fullBackupContent") != "false":
        violations.append("fullBackupContent must remain disabled")
    if attr(application, "networkSecurityConfig") != "@xml/network_security_config":
        violations.append("app must keep the reviewed network security config")
    if attr(application, "debuggable") == "true":
        violations.append("main manifest must never make the app debuggable")
    if attr(application, "testOnly") == "true":
        violations.append("main manifest must never make the app testOnly")

    allowed_exported_activities = {".MainActivity"}
    for activity in application.findall("activity"):
        if attr(activity, "exported") == "true" and attr(activity, "name") not in allowed_exported_activities:
            violations.append(f"unexpected exported main activity: {attr(activity, 'name')}")

    for tag in ("service", "receiver", "provider"):
        for node in application.findall(tag):
            if attr(node, "exported") == "true" and not attr(node, "permission"):
                violations.append(
                    f"exported {tag} lacks an explicit permission boundary: {attr(node, 'name')}"
                )

paths_root = ET.parse(ROOT / "app" / "src" / "main" / "res" / "xml" / "file_paths.xml").getroot()
for child in paths_root:
    local = child.tag.split("}")[-1]
    if local in {"root-path", "external-path"}:
        violations.append(f"FileProvider broad path is forbidden: {local}")
    value = child.attrib.get("path", "")
    if value in {"", ".", "/"}:
        violations.append(f"FileProvider path must be scoped, got {value!r} for {local}")

device_manifest = ET.parse(
    ROOT / "harness-device-android" / "src" / "main" / "AndroidManifest.xml"
).getroot()
device_app = device_manifest.find("application")
if device_app is not None:
    for tag in ("service", "receiver", "provider"):
        for node in device_app.findall(tag):
            if attr(node, "exported") != "true":
                continue
            permission = attr(node, "permission") or ""
            if not permission.startswith("android.permission.BIND_"):
                violations.append(
                    f"platform exported {tag} must use an Android BIND permission: "
                    f"{attr(node, 'name')} -> {permission or '<none>'}"
                )

# Cleartext remains available for reviewed local/MCP boundaries, so model endpoints need a
# separate HTTPS-or-loopback policy and regression tests.
endpoint_policy = (
    ROOT / "app" / "src" / "main" / "java" / "com" / "labteto" / "dshmobile"
    / "local" / "model" / "LocalModelEndpointPolicy.kt"
)
endpoint_test = (
    ROOT / "app" / "src" / "test" / "java" / "com" / "labteto" / "dshmobile"
    / "local" / "LocalModelEndpointPolicyTest.kt"
)
if not endpoint_policy.exists() or not endpoint_test.exists():
    violations.append("model endpoint HTTPS/loopback policy and its tests must remain present")
else:
    policy = endpoint_policy.read_text(encoding="utf-8")
    tests = endpoint_test.read_text(encoding="utf-8")
    if re.search(r"\bfun\s+normalizeModelBaseUrl\s*\(", policy) is None:
        violations.append("model endpoint policy lost its normalization entrypoint")
    for required in ("URI(", ".scheme", ".host", ".userInfo", "loopback"):
        if required not in policy:
            violations.append("model endpoint policy lost a security decision input: " + required)

    # Actual JVM @Test methods prove HTTPS, loopback, remote HTTP and embedded
    # credentials; method/variable renames and rearrangements are harmless.
    methods = re.findall(
        r"@Test\s+fun\s+\w+\s*\([^)]*\)\s*\{[\s\S]*?(?=\n\s*@Test|\n\})",
        tests,
    )
    categories = {
        "remote HTTPS accepted": lambda case: "assertEquals(" in case and
            "normalizeModelBaseUrl(" in case and '"https://' in case,
        "loopback HTTP accepted": lambda case: "assertEquals(" in case and
            "normalizeModelBaseUrl(" in case and re.search(r'"http://(?:localhost|127\.0\.0\.1|\[::1\])', case),
        "remote HTTP rejected": lambda case: "assertThrows(" in case and
            "normalizeModelBaseUrl(" in case and '"http://example.com' in case,
        "embedded credentials rejected": lambda case: "assertThrows(" in case and
            "normalizeModelBaseUrl(" in case and "user:pass@" in case,
    }
    for scenario, predicate in categories.items():
        if not any(predicate(case) for case in methods):
            violations.append("model endpoint JVM behavior test missing: " + scenario)

if violations:
    print("Android security boundary violations:")
    for item in violations:
        print(f" - {item}")
    sys.exit(1)

print("Android security boundary checks passed")
