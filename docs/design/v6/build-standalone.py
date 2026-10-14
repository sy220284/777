#!/usr/bin/env python3
"""Rebuild the only V6 mobile/app.html design projection from checked-in editable sources."""
import argparse
import base64
import json
from pathlib import Path
R = Path(__file__).resolve().parent
S = R / "mobile"
parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true", help="Fail when committed offline HTML differs from editable source")
args = parser.parse_args()
tokens = json.loads((R / "current-ui.json").read_text(encoding="utf-8"))
h = (S / "index.html").read_text(encoding="utf-8")
css = (S / "v6.css").read_text(encoding="utf-8")
js = (S / "v6.js").read_text(encoding="utf-8")
def svg(path):
    return "data:image/svg+xml;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
icons = {p.stem: svg(p) for p in sorted((R / "assets/icons").glob("*.svg"))}
if len(icons) != tokens["iconInventory"]["activeCount"]:
    parser.error("Unexpected active icon count")
people = {i: svg(R / "assets/portraits" / f"persona-{i+1}.svg") for i in range(4)}
archive = svg(R / "assets/file-icons/archive-light.svg")
boot = ('<script>\nconst V6_ROUTES=' + json.dumps([x["id"] for x in tokens["routes"]], ensure_ascii=False) +
    ';\nwindow.V6_ASSET_DATA=' + json.dumps(icons, ensure_ascii=False) +
    ';\nwindow.V6_PORTRAIT_DATA=' + json.dumps(people, ensure_ascii=False) +
    ';\nwindow.V6_FILE_ICON_DATA=' + json.dumps(archive) + ';\n</script>')
h = h.replace('<html lang="zh-CN">', '<html lang="zh-CN" data-v6-authoritative="compose-3cdd12f">')
h = h.replace('<link rel="stylesheet" href="v6.css">', "<style>" + css + "</style>")
h = h.replace('<script src="v6.js"></script>', boot + "<script>" + js + "</script>")
target = S / "app.html"
if args.check:
    if not target.exists() or target.read_text(encoding="utf-8") != h:
        parser.error("V6 compiled offline page drifted from editable source; regenerate")
    print("V6 standalone source parity passed")
else:
    target.write_text(h, encoding="utf-8")
    print("Generated", target, len(h), "characters")
