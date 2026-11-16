#!/usr/bin/env python3
"""从 app.html 编译「三版设计稿画廊」。

产出：
  docs/design/v8/mobile/gallery.html   可切换变体 / 主题 / 路由的单文件画廊
  docs/design/v8/mobile/app-A.html     变体 A 固化版（直接双击打开）
  docs/design/v8/mobile/app-B.html     变体 B 固化版
  docs/design/v8/mobile/app-C.html     变体 C 固化版

`--check` 校验产物与可编辑源一致。
"""
import argparse
import json
import re
from pathlib import Path

R = Path(__file__).resolve().parent
S = R / "mobile"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()

app = (S / "app.html").read_text(encoding="utf-8")
variants_css = (R / "variants.css").read_text(encoding="utf-8")

SWITCHER = """
<script>
(function(){
  var V = ['A','B','C'];
  var THEMES = [['light','暖纸'],['dark','夜墨'],['matte','哑光']];
  var ROUTES = [['HOME','聊天'],['PERSONA_GALLERY','人物'],['DIARY','日记'],['WORKSPACE','工作区'],['TASKS','任务'],['TOOLS','能力'],['SETTINGS','设置']];
  var app = document.getElementById('app');
  var state = { variant: 'A', theme: 'dark', route: 'HOME', mode: 'CHAT' };
  var bar = document.createElement('div');
  bar.className = 'gallery-bar';
  bar.innerHTML =
    '<span class="tag">版本</span>' +
    V.map(function(v){return '<button data-v="'+v+'">'+v+'</button>';}).join('') +
    '<span class="sep"></span>' +
    THEMES.map(function(t){return '<button data-theme="'+t[0]+'">'+t[1]+'</button>';}).join('') +
    '<span class="sep"></span>' +
    ROUTES.map(function(r){return '<button data-route="'+r[0]+'">'+r[1]+'</button>';}).join('');
  document.body.appendChild(bar);
  function paint(){
    document.documentElement.setAttribute('data-variant', state.variant);
    document.body.dataset.theme = state.theme;
    var url = new URL(location.href);
    url.searchParams.set('route', state.route);
    url.searchParams.set('theme', state.theme);
    url.searchParams.set('mode', state.mode);
    history.replaceState(null, '', url);
    if (window.V8_APP) { window.V8_APP.state.route = state.route; window.V8_APP.state.theme = state.theme; window.V8_APP.state.mode = state.mode; window.V8_APP.render(); }
    bar.querySelectorAll('button').forEach(function(b){
      var on = (b.dataset.v && b.dataset.v === state.variant)
            || (b.dataset.theme && b.dataset.theme === state.theme)
            || (b.dataset.route && b.dataset.route === state.route);
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
  }
  bar.addEventListener('click', function(e){
    var b = e.target.closest('button'); if(!b) return;
    if (b.dataset.v) state.variant = b.dataset.v;
    if (b.dataset.theme) state.theme = b.dataset.theme;
    if (b.dataset.route) state.route = b.dataset.route;
    paint();
  });
  var q = new URLSearchParams(location.search);
  if (q.get('variant') && V.indexOf(q.get('variant')) >= 0) state.variant = q.get('variant');
  if (q.get('theme')) state.theme = q.get('theme');
  if (q.get('route')) state.route = q.get('route');
  window.addEventListener('load', paint);
  document.addEventListener('DOMContentLoaded', function(){ setTimeout(paint, 60); });
})();
</script>
"""


def compose(variant: str | None) -> str:
    html = app.replace("</head>", "<style id=\"v8-variants\">\n" + variants_css + "\n</style>\n</head>")
    if variant:
        html = html.replace('<html lang="zh-CN">', f'<html lang="zh-CN" data-variant="{variant}">')
    html = html.replace("</body>", SWITCHER + "</body>")
    return html


gallery = compose(None)
for key in ("A", "B", "C"):
    single = compose(key)
    single = single.replace(SWITCHER, "")
    single = single.replace('<html lang="zh-CN" data-variant="%s">' % key,
                            '<html lang="zh-CN" data-variant="%s">' % key)
    (S / f"app-{key}.html").write_text(single, encoding="utf-8")

outputs = {"mobile/gallery.html": gallery}
for key in ("A", "B", "C"):
    outputs[f"mobile/app-{key}.html"] = (S / f"app-{key}.html").read_text(encoding="utf-8")

if args.check:
    bad = [name for name, text in outputs.items() if (R / name).read_text(encoding="utf-8") != text]
    if bad:
        parser.error("gallery outputs drifted: " + ", ".join(bad))
    print("V8 gallery source parity passed")
else:
    for name, text in outputs.items():
        (R / name).write_text(text, encoding="utf-8")
        print(f"Generated {name} ({len(text)} characters)")
