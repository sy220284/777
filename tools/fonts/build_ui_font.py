#!/usr/bin/env python3
"""Build fixed-UI subsets from the licensed full Noto Sans SC variable font."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SOURCE_GIT_BLOB = "fb0637bafbcd804fe32152370a1225990745b4bc"
WEIGHTS = (400, 500, 600, 700, 800)
BASE = "".join(chr(c) for c in range(32, 127)) + "\u00a0\u00b7\u00a5\u2013\u2014\u2018\u2019\u201c\u201d\u2022\u2026\u3000\u3001\u3002\u300a\u300b\u3010\u3011\uff01\uff08\uff09\uff0c\uff0e\uff1a\uff1b\uff1f"

def git_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def kotlin_literals(text):
    i, depth = 0, 0
    while i < len(text):
        if depth:
            if text.startswith("/*", i):
                depth += 1; i += 2
            elif text.startswith("*/", i):
                depth -= 1; i += 2
            else: i += 1
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end + 1
            continue
        if text.startswith("/*", i):
            depth = 1; i += 2; continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            if end < 0: raise ValueError("Unclosed raw Kotlin string")
            yield text[i + 3:end]
            i = end + 3; continue
        if text[i] == '"':
            j, chars = i + 1, []
            while j < len(text):
                if text[j] == "\\" and j + 1 < len(text):
                    chars.append(text[j:j+2]); j += 2
                elif text[j] == '"': break
                else:
                    chars.append(text[j]); j += 1
            if j >= len(text): raise ValueError("Unclosed Kotlin string")
            literal = "".join(chars)
            yield re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), literal)
            i = j + 1; continue
        if text[i] == "'":
            i += 1
            while i < len(text):
                if text[i] == "\\": i += 2
                elif text[i] == "'":
                    i += 1; break
                else: i += 1
            continue
        i += 1

def used_characters(res, ui):
    out = {ord(c) for c in BASE}
    for file in sorted(res.glob("values*/**/*.xml")):
        for el in ET.parse(file).iter():
            if el.text: out.update(map(ord, el.text))
            if el.tail: out.update(map(ord, el.tail))
    for file in sorted(ui.rglob("*.kt")):
        for literal in kotlin_literals(file.read_text(encoding="utf-8")):
            out.update(ord(c) for c in literal if ord(c) > 126 and c not in "\r\n\t")
    return {c for c in out if c >= 32 and not 0xD800 <= c <= 0xDFFF}

def build(font_file, res, ui, directory):
    from fontTools.ttLib import TTFont
    from fontTools.subset import Options, Subsetter
    from fontTools.varLib.instancer import instantiateVariableFont
    raw = font_file.read_bytes()
    origin = git_sha(raw)
    if origin != SOURCE_GIT_BLOB: raise ValueError("Font source does not match upstream-pinned Git blob")
    font = TTFont(io.BytesIO(raw), recalcTimestamp=False)
    axes = {a.axisTag: a for a in font["fvar"].axes} if "fvar" in font else {}
    if "wght" not in axes or axes["wght"].minValue > 400 or axes["wght"].maxValue < 800:
        raise ValueError("Noto Sans SC mother must support real 400-800 weights")
    requested = used_characters(res, ui)
    available = set(font.getBestCmap())
    missing_han = sorted(c for c in requested - available if 0x3400 <= c <= 0x9fff or 0x20000 <= c <= 0x3134f)
    if missing_han: raise ValueError("Fixed UI missing Chinese glyphs: " + "".join(chr(c) for c in missing_han))
    # Unsupported emoji and symbols are rendered by Android's font fallback.
    chars = requested & available
    key = hashlib.sha256((origin + "|" + ",".join(map(str, sorted(chars))) + "|v2").encode()).hexdigest()
    directory.mkdir(parents=True, exist_ok=True)
    manifest = directory.parents[1] / "ui-font-subset-manifest.json"
    targets = [directory / f"ui_noto_sc_{w}.ttf" for w in WEIGHTS]
    if all(p.is_file() for p in targets) and manifest.is_file():
        if json.loads(manifest.read_text(encoding="utf-8")).get("fingerprint") == key:
            print(f"UI font unchanged: {len(chars)} glyphs; using cached resources")
            return
    opts = Options()
    opts.recalc_timestamp = False
    opts.layout_features = ["*"]
    subset = Subsetter(options=opts)
    subset.populate(unicodes=sorted(chars))
    subset.subset(font)
    buf = io.BytesIO()
    font.save(buf)
    data = buf.getvalue()
    sizes = {}
    for weight, path in zip(WEIGHTS, targets):
        variable = TTFont(io.BytesIO(data), recalcTimestamp=False)
        static = instantiateVariableFont(variable, {"wght": weight}, inplace=True)
        output = io.BytesIO()
        static.save(output)
        value = output.getvalue()
        if not path.exists() or path.read_bytes() != value:
            path.write_bytes(value)
        missing = chars - set(TTFont(io.BytesIO(value)).getBestCmap())
        if missing: raise ValueError(f"Subset {weight} lost {len(missing)} fixed UI characters")
        sizes[weight] = len(value)
    manifest.write_text(json.dumps({"fingerprint": key, "source_git_blob": origin, "glyphs": len(chars), "weights": WEIGHTS}, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Generated {len(chars)} UI glyphs: {sizes}, full source {len(raw)} bytes")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, required=True)
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--ui", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    build(args.font, args.resources, args.ui, args.out)
