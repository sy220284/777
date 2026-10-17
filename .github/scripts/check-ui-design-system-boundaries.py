#!/usr/bin/env python3
"""Keep high-level Compose screens on shared overlay/card primitives.

This is intentionally a narrow ratchet: full-screen Dialog surfaces remain allowed, while the
small modal/menu/card primitives that already have Design System wrappers may not be reintroduced
directly in feature screens.
"""

from pathlib import Path
import re
import json
import sys
import subprocess
import tempfile
import zipfile
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
V6_VISUAL = ROOT / "ui-pack-v6/src/main/kotlin/com/labteto/dshmobile/ui"

RULES = (
    (re.compile(r"\bAlertDialog\s*\("), set(), "use SyDialog"),
    (
        re.compile(r"\bDialog\s*\("),
        {"shenyan/SyResponsiveSheet.kt", "shenyan/SyOverlays.kt"},
        "use SyResponsiveSheet/SyConfirmDialog",
    ),
    (
        re.compile(r"\bModalBottomSheet\s*\("),
        {"shenyan/SyResponsiveSheet.kt"},
        "use SyResponsiveSheet",
    ),
    (
        re.compile(r"\bDropdownMenu\s*\("),
        {"shenyan/SyOverlays.kt"},
        "use SyPopupMenu/SyMenu",
    ),
    (
        re.compile(r"\bPopup\s*\("),
        {"shenyan/SyOverlays.kt"},
        "use SyPopupMenu/SyContextActionMenu or another shared popup primitive",
    ),
    (
        re.compile(r"\bOutlinedCard\s*\("),
        set(),
        "use SyMaterialFrame/SyGlassCard",
    ),
)

def mask_non_code(source: str) -> str:
    """Mask comments, Kotlin strings and character literals, retaining line positions."""
    output = list(source)
    i, n, depth = 0, len(source), 0
    while i < n:
        if depth:
            if source.startswith("/*", i):
                output[i:i+2] = [" ", " "]
                depth += 1
                i += 2
            elif source.startswith("*/", i):
                output[i:i+2] = [" ", " "]
                depth -= 1
                i += 2
            else:
                if source[i] != "\n":
                    output[i] = " "
                i += 1
            continue
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end < 0:
                end = n
            output[i:end] = " " * (end - i)
            i = end
            continue
        if source.startswith("/*", i):
            output[i:i+2] = [" ", " "]
            depth = 1
            i += 2
            continue
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            for j in range(i, end):
                if source[j] != "\n":
                    output[j] = " "
            i = end
            continue
        if source[i] in ('"', "'"):
            quote = source[i]
            j = i + 1
            while j < n:
                if source[j] == chr(92):
                    j += 2
                    continue
                if source[j] == quote:
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                if source[k] != "\n":
                    output[k] = " "
            i = j
            continue
        i += 1
    return "".join(output)

def self_test() -> None:
    sample = '// Dialog(ignored)\nval text = "Popup(fake)"\n/* AlertDialog(no) */\nDialog(real)'
    result = mask_non_code(sample)
    assert "Dialog(real)" in result
    assert "Dialog(ignored)" not in result
    assert "Popup(fake)" not in result
    assert "AlertDialog(no)" not in result

self_test()

violations = []
# Modal primitives in App-owned screens use the App's wrappers; UI Packs are
# independently designed and may implement their own dialogs (UI 3.0 §7).
# Do not accidentally impose App-only composition rules on independent Packs.
V6_SCENES = ROOT / "ui-pack-v6/src/main/kotlin/com/labteto/dshmobile/uipack/v6"
for path in UI.rglob("*.kt"):
    relative = path.relative_to(UI).as_posix()
    text = mask_non_code(path.read_text(encoding="utf-8"))
    for pattern, allowed, hint in RULES:
        if relative in allowed:
            continue
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            violations.append(f"{relative}:{line}: {match.group(0).strip()} -> {hint}")


# Keep the main V6 transition owners on one SyMotion vocabulary. Other feature
# delays (network, debounce, accessibility speech) are intentionally not animation tokens.
CANONICAL_MOTION_FILES = (
    "theme/Animation.kt",
    "screens/local/LocalFeatureAnimatedHost.kt",
    "screens/local/FluidDesignNavigation.kt",
    "shenyan/SyResponsiveSheet.kt",
)
INLINE_MOTION_DURATION = re.compile(r"\btween\s*(?:<[^>]+>)?\s*\(\s*[1-9][0-9]{1,3}\b")
for relative in CANONICAL_MOTION_FILES:
    file = (V6_VISUAL if relative.startswith(("theme/", "shenyan/SyResponsiveSheet.kt")) else UI) / relative
    source = mask_non_code(file.read_text(encoding="utf-8"))
    for match in INLINE_MOTION_DURATION.finditer(source):
        line = source.count("\n", 0, match.start()) + 1
        violations.append(f"{relative}:{line}: inline animation duration; use SyMotion semantic token")

# The app and overlays must not drift into separate wallpaper/glow drawing algorithms.
background_host = (V6_VISUAL / "theme/AppBackground.kt").read_text(encoding="utf-8")
if "FluidGlassBackdrop(" in mask_non_code(background_host):
    violations.append("theme/AppBackground.kt: duplicate root background painter; use SySceneBackgroundSurface")
if "SySceneBackgroundSurface(" not in mask_non_code(background_host):
    violations.append("theme/AppBackground.kt: root is no longer using the shared scene painter")

# V6 hardening: prevent deleted visual APIs and retired design references from returning.
RETIRED_DRAWING_CALL = re.compile(
    r"\bDs(?:Button|Card|Segmented|Checkbox|BottomSheet|SearchField|ToastHost|TopBar|ProgressBar)\s*\("
)
baseline_path = ROOT / "docs/design/v6/legacy-theme-consumers.json"
baseline = set(json.loads(baseline_path.read_text(encoding="utf-8"))["remainingProductionFiles"])
actual = set()
for path in list(UI.rglob("*.kt")) + list(V6_SCENES.rglob("*.kt")):
    relative = path.relative_to(ROOT).as_posix()
    source = mask_non_code(path.read_text(encoding="utf-8"))
    for match in RETIRED_DRAWING_CALL.finditer(source):
        violations.append(f"{relative}: retired Ds drawing component {match.group(0)}")
    if re.search(r"\bDsTheme\.colors\b", source):
        actual.add(relative)
    if re.search(r"\bDsSpacing\b", source):
        violations.append(f"{relative}: retired DsSpacing token; use canonical SyMetric")

# Debug previews must follow the same spacing tokens as production Compose UI.
for path in (ROOT / "app/src/debug/java").rglob("*.kt"):
    source = mask_non_code(path.read_text(encoding="utf-8"))
    if re.search(r"\bDsSpacing\b", source):
        violations.append(f"{path.relative_to(ROOT)}: retired DsSpacing token; use canonical SyMetric")

for new in sorted(actual - baseline):
    violations.append(f"{new}: new legacy DsTheme.colors consumer; use canonical LocalSyColors")
for stale in sorted(baseline - actual):
    violations.append(f"{stale}: remove migrated file from legacy-theme-consumers.json")

for relative in ["docs/UI-UX.zh-CN.md","docs/design/mobile-ui.md","docs/design/v6/README.zh-CN.md","docs/design/v6/STATUS.zh-CN.md","docs/README.md"]:
    file = ROOT / relative
    source = file.read_text(encoding="utf-8")
    for link in re.findall(r"\]\(([^)]+)\)", source):
        url = link.split("#")[0].split("?")[0].strip()
        if not url or re.match(r"^(?:https?:|mailto:|data:)", url):
            continue
        target = (file.parent / url).resolve()
        if not target.is_relative_to(ROOT) or not target.is_file():
            violations.append(f"{relative}: broken local design link -> {url}")

for relative in ("docs/design/v5-p0p1", "docs/design/clear-realm-theme.md",
                 "docs/design/v6/verification.json", "docs/design/v6/PR664-VISUAL-ACCEPTANCE.zh-CN.md",
                 "app/src/main/java/com/shenyan/designsystem/layout/SyAdaptiveShell.kt"):
    if (ROOT / relative).exists():
        violations.append(f"{relative}: retired V5 visual asset or obsolete component returned")


# V6 design-source ratchet: the current route and settings inventories must match native owners.
# Old 38-screen prototypes are not a license to restore retired UI into design materials.
v6_root = ROOT / "docs/design/v6"
v6_manifest = json.loads((v6_root / "current-ui.json").read_text(encoding="utf-8"))
v6_html = (v6_root / "mobile/app.html").read_text(encoding="utf-8")
v6_spec = (v6_root / "V6-CURRENT-IMPLEMENTATION.zh-CN.md").read_text(encoding="utf-8")
route_catalog = (ROOT / "app/src/main/java/com/labteto/dshmobile/local/feature/LocalFeatureCatalog.kt").read_text(encoding="utf-8")
settings_code = (UI / "screens/settings/SettingsScreen.kt").read_text(encoding="utf-8")
routes_match = re.search(r"internal enum class LocalFeatureRoute\s*\{([^}]+)\}", route_catalog, re.S)
settings_match = re.search(r"enum class SettingsDestination\s*\{([^}]+)\}", settings_code, re.S)
def enum_items(match):
    return re.findall(r"^\s*([A-Z][A-Z_]+)\s*(?:,|$)", match.group(1), re.M) if match else []
code_routes = enum_items(routes_match)
code_settings = enum_items(settings_match)
manifest_routes = [route["id"] for route in v6_manifest["routes"]]
if not code_routes or code_routes != manifest_routes:
    violations.append("V6 current-ui.json: current routes differ from native LocalFeatureRoute")
if not code_settings or code_settings != v6_manifest["settingsDestinations"]:
    violations.append("V6 current-ui.json: settings destinations differ from SettingsDestination")
for route in v6_manifest["routes"]:
    path = ROOT / route["source"]
    if not path.is_file():
        violations.append(f"V6 current-ui.json: missing native source -> {route['source']}")
v6_proto_match = re.search(r"const\s+V6_ROUTES\s*=\s*(\[[^\]]+\])", v6_html)
if not v6_proto_match or json.loads(v6_proto_match.group(1)) != manifest_routes:
    violations.append("V6 prototype route list differs from native routes")
if "data-v6-authoritative=\"compose-" not in v6_html:
    violations.append("V6 prototype missing source-of-truth marker")
# Retired UI must never be reinstated in the active interactive prototype.
for marker in ("S03Board", "S02v2Work", "mobile-screen-tabs", "38 场景", "目标 / 计划 / 审批 / 执行 / 成果"):
    if marker in v6_html:
        violations.append(f"V6 prototype reintroduced obsolete reference: {marker}")
if "Figma" not in v6_spec or "#711" not in v6_spec:
    violations.append("V6 spec omitted Figma or pending PR #711 state")

# Exact Compose -> HTML token parity; labels alone are not enough to prevent reintroducing old white glass.
v6_foundation = (ROOT / "ui-pack-v6/src/main/kotlin/com/shenyan/designsystem/foundation/ShenyanFoundation.kt").read_text(encoding="utf-8")
v6_preview = (v6_root / "assets/file-icons-preview.svg").read_text(encoding="utf-8")
def read_primitive_style(css, selector, name):
    m = re.search(re.escape(selector) + r"\s*\{([^}]+)\}", css)
    if not m:
        return None
    val = re.search(r"--" + re.escape(name) + r"\s*:\s*([^;]+)\s*;", m.group(1))
    return val.group(1).strip() if val else None
for theme, selector in (("Light", ":root"), ("Dark", 'body[data-theme="dark"]'), ("Matte", 'body[data-theme="matte"]')):
    palette = re.search(r"private val " + theme + r" = SyColors\((.*?)\n\)", v6_foundation, re.S)
    if not palette:
        violations.append(f"V6 palette missing from Compose: {theme}")
        continue
    def native_argb(name):
        m = re.search(r"\b" + name + r"\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", palette.group(1))
        return m.group(1).upper() if m else None
    for name, css_key in (("canvas", "canvas"), ("materialL1","l1"), ("materialL2","l2"), ("materialL3","l3"), ("materialL4","l4"), ("materialL5","l5")):
        value = native_argb(name)
        css_value = read_primitive_style(v6_html, selector, css_key)
        if not value or not css_value:
            violations.append(f"V6 {theme} {name}: source token missing")
            continue
        expected_rgb = "#" + value[2:]
        alpha = int(value[:2], 16)/255
        if css_value.upper() == expected_rgb and alpha == 1:
            pass
        else:
            rgba = re.fullmatch(r"rgba\(\s*(\d+),\s*(\d+),\s*(\d+),\s*([0-9.]+)\s*\)", css_value)
            if not rgba:
                violations.append(f"V6 {theme} {name}: invalid CSS token -> {css_value}")
                continue
            vals = tuple(int(x) for x in rgba.groups()[:3])
            expected_vals = tuple(int(value[i:i+2],16) for i in (2,4,6))
            if vals != expected_vals or abs(float(rgba.group(4))-alpha) > .003:
                violations.append(f"V6 {theme} {name}: CSS drifted from Compose 0x{value}")
        if name.startswith("materialL") and value not in v6_preview:
            violations.append(f"V6 material SVG preview outdated: {theme} {name} 0x{value}")

# Ensure active HTML icons and editable SVG deliverables match the manifest.
v6_svg = (v6_root / "sy-entry-icons.svg").read_text(encoding="utf-8")
labels = re.findall(r'<text\b[^>]*>([A-Za-z][A-Za-z0-9]+)</text>', v6_svg)
icon_map_match = re.search(r'window\.V6_ASSET_DATA\s*=\s*(\{.*?\})\s*;', v6_html, re.S)
active_count = v6_manifest.get("iconInventory", {}).get("activeCount", 24)
retired_icons = set(v6_manifest.get("iconInventory", {}).get("retired", []))
if len(labels) != active_count or len(set(labels)) != active_count:
    violations.append("V6 SVG sheet must have 24 distinct active icon glyphs")
if retired_icons.intersection(labels):
    violations.append("V6 SVG sheet still includes a retired entry icon")
if not icon_map_match:
    violations.append("V6 offline HTML missing embedded icon assets")
else:
    try:
        offline_names = set(json.loads(icon_map_match.group(1)))
        if offline_names != set(labels):
            violations.append("V6 offline HTML icon inventory differs from current editable SVG")
    except json.JSONDecodeError:
        violations.append("V6 offline HTML icon map invalid")

# Run the actual exporter in CI; a passing manifest/parser check alone cannot prove
# that the user's ZIP will omit retired icons or preserve file hashes.
try:
    with tempfile.TemporaryDirectory(prefix="v6-export-") as tmp:
        archive = Path(tmp) / "v6-current.zip"
        process = subprocess.run(
            [sys.executable, str(v6_root / "sync-design-package.py"),
             "--source", str(v6_root), "--output", str(archive)],
            capture_output=True, text=True, timeout=40, check=False,
        )
        if process.returncode:
            violations.append("V6 ZIP exporter failed: " + (process.stderr or process.stdout).strip()[-900:])
        else:
            with zipfile.ZipFile(archive) as exported:
                if exported.testzip() is not None:
                    violations.append("V6 ZIP CRC validation failed")
                sheet = ElementTree.fromstring(exported.read("sy-entry-icons.svg"))
                ns = "{http://www.w3.org/2000/svg}"
                groups = sheet.findall(f"{ns}g")
                names = [t.text for t in sheet.iter(f"{ns}text")
                         if t.text and re.fullmatch(r"[A-Za-z][A-Za-z0-9]+", t.text)]
                if len(names) != active_count or len(set(names)) != active_count or retired_icons.intersection(names):
                    violations.append("V6 exported ZIP contains an incorrect/retired icon inventory")
except (OSError, KeyError, ValueError, TimeoutError, zipfile.BadZipFile) as exc:
    violations.append(f"V6 ZIP exporter validation failed: {exc}")

# Check the standalone mobile HTML is reproducible from the committed editable source and 24 SVG assets.
try:
    generated = subprocess.run(
        [sys.executable, str(v6_root / "build-standalone.py"), "--check"],
        capture_output=True, text=True, timeout=20, check=False,
    )
    if generated.returncode != 0:
        violations.append("V6 offline preview drifted from editable source: " + (generated.stderr.strip() or generated.stdout.strip())[:360])
except (OSError, subprocess.TimeoutExpired) as exc:
    violations.append("V6 offline generator check failed: " + str(exc))

# Also validate local source links in the actual V6 authority.
for link in re.findall(r"\]\(([^)]+)\)", v6_spec):
    url = link.split("#")[0].split("?")[0].strip()
    if not url or re.match(r"^(?:https?:|mailto:|data:)", url):
        continue
    dest = (v6_root / url).resolve()
    if not dest.is_relative_to(ROOT) or not dest.is_file():
        violations.append(f"V6 spec: broken local link -> {url}")


# Verify every utility/action/file/standalone glyph actually matches its Compose or Android owner.
try:
    icon_result = subprocess.run([sys.executable, str(v6_root / "audit-all-icons.py")],
                                 capture_output=True, text=True, timeout=20, check=False)
    if icon_result.returncode != 0:
        violations.append("V6 complete icon audit failed: " + (icon_result.stdout.strip() or icon_result.stderr.strip())[:1600])
except (OSError, subprocess.TimeoutExpired) as exc:
    violations.append("V6 icon audit failed: " + str(exc))

if violations:
    print("UI Design System boundary violations:")
    print("\n".join(f"  - {item}" for item in violations))
    sys.exit(1)

print("UI Design System boundary guard passed")
