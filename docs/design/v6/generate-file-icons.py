#!/usr/bin/env python3
"""Export the same V5 vector paths to Android XML and editable SVG assets."""
from pathlib import Path
import json
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
spec = json.loads((HERE / 'file-icons.json').read_text())
drawables = ROOT / 'app/src/main/res/drawable'
exports = HERE / 'assets/file-icons'
exports.mkdir(parents=True, exist_ok=True)
paper = 'M10,1H29L37,9V32C37,35.9 33.9,39 30,39H10C6.1,39 3,35.9 3,32V8C3,4.1 6.1,1 10,1Z'
fold = 'M29,1V6C29,7.7 30.3,9 32,9H37'
for kind, glyph in spec['glyphs'].items():
    for theme, colors in spec['themes'].items():
        name = f'ic_ui_file{kind}_{theme}'
        paths = [(paper, colors['paper'], colors['edge'], 1.75),
                 (fold, 'none', colors['edge'], 1.75),
                 (glyph, 'none', colors[kind], 1.75)]
        xml = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               '    android:width="40dp" android:height="40dp" android:viewportWidth="40" android:viewportHeight="40">']
        svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">', f'<title>{escape(spec["labels"][kind])} · {theme}</title>']
        for path, fill, stroke, width in paths:
            xml.append(f'    <path android:pathData="{path}" android:fillColor="{"#00000000" if fill == "none" else fill}" android:strokeColor="{stroke}" android:strokeWidth="{width}" android:strokeLineCap="round" android:strokeLineJoin="round" />')
            svg.append(f'<path d="{path}" fill="{fill}" stroke="{stroke}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')
        xml.append('</vector>'); svg.append('</svg>')
        (drawables / f'{name}.xml').write_text('\n'.join(xml) + '\n')
        (exports / f'{name}.svg').write_text('\n'.join(svg) + '\n')
print('Exported 6 Android vectors and 6 matching SVG originals')
