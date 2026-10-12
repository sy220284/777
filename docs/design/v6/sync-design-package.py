#!/usr/bin/env python3
"""Synchronise reviewed production design sources back into a complete V6 package."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument('--source', type=Path, required=True, help='Unpacked authoritative V5 package')
p.add_argument('--output', type=Path, required=True, help='New V6 directory')
args = p.parse_args()
source, out = args.source.resolve(), args.output.resolve()
if out.exists():
    raise SystemExit('Output already exists; use a new directory to preserve reviewed material')
shutil.copytree(source, out)
native = out / 'compose-v6/src/main/kotlin'
shutil.copytree(REPO / 'app/src/main/java/com/shenyan/designsystem', native / 'com/shenyan/designsystem')
shutil.copytree(REPO / 'app/src/main/java/com/labteto/dshmobile/ui/shenyan', native / 'com/labteto/dshmobile/ui/shenyan')
icons = native / 'com/labteto/dshmobile/ui/components'
icons.mkdir(parents=True)
shutil.copy2(REPO / 'app/src/main/java/com/labteto/dshmobile/ui/components/FeatherIcons.kt', icons)
resources = out / 'android-v6/res'
for folder in ['drawable', 'font']:
    (resources / folder).mkdir(parents=True, exist_ok=True)
    for asset in (REPO / 'app/src/main/res' / folder).iterdir():
        if asset.is_file() and (folder == 'font' or asset.name.startswith('ic_ui_')):
            shutil.copy2(asset, resources / folder / asset.name)
for font in (REPO / 'app/build/generated/uiFontRes/font').glob('*.ttf'):
    shutil.copy2(font, resources / 'font' / font.name)
(resources / 'values').mkdir()
shutil.copy2(REPO / 'app/src/main/res/values/strings.xml', resources / 'values/strings.xml')
shutil.copytree(HERE / 'assets', out / 'assets/v6')
shutil.copy2(HERE / 'file-icons.json', out / 'assets/v6/file-icons.json')
shutil.copy2(HERE / 'README.zh-CN.md', out / 'V6-DELIVERY.zh-CN.md')
shutil.copy2(HERE / 'verification.json', out / 'V6-VERIFICATION.json')
shutil.copy2(REPO / 'THIRD_PARTY_NOTICES.md', out / 'THIRD-PARTY-NOTICES.md')
font_dir = out / 'assets/fonts'
shutil.copytree(resources / 'font', font_dir)
shutil.copy2(REPO / 'tools/fonts/source/OFL.txt', font_dir / 'NotoSansSC-OFL.txt')
from fontTools.ttLib import TTFont
from fontTools.subset import Options, Subsetter
from fontTools.varLib.instancer import instantiateVariableFont
import io
# Design samples include names/prose beyond the fixed native menu subset.
# Bundle a separate subset of every supplied design character, without changing the APK fonts.
font = TTFont(REPO / 'tools/fonts/source/NotoSansSC-wght.ttf', recalcTimestamp=False)
requested = {ord(c) for f in out.rglob('*') if f.suffix in ['.html', '.js', '.md', '.json'] for c in f.read_text(errors='strict') if ord(c) >= 32}
missing = {c for c in requested - set(font.getBestCmap()) if 0x3400 <= c <= 0x9fff}
if missing:
    raise SystemExit('Design font missing CJK glyphs: ' + ''.join(chr(c) for c in sorted(missing)))
subset = Subsetter(options=Options())
subset.populate(unicodes=sorted(requested & set(font.getBestCmap())))
subset.subset(font)
data = io.BytesIO()
font.save(data)
for weight in [400, 500, 600, 700, 800]:
    static = instantiateVariableFont(TTFont(io.BytesIO(data.getvalue()), recalcTimestamp=False), {'wght': weight}, inplace=True)
    static.save(font_dir / f'shenyan_design_cjk_{weight}.ttf')
notices = []
for font in sorted(font_dir.glob('*')):
    if font.suffix not in ['.ttf', '.otf']:
        continue
    names = TTFont(font)['name']
    info = {i: sorted({r.toUnicode() for r in names.names if r.nameID == i}) for i in [0, 13, 14]}
    notices.append({'file': font.name, 'copyright': info[0], 'license': info[13], 'licenseURL': info[14]})
(font_dir / 'FONT-NOTICES.json').write_text(json.dumps(notices, ensure_ascii=False, indent=2) + '\n')
css = []
for w in [400, 500, 600, 700, 800]:
    css.append(f"@font-face{{font-family:'Outfit';font-style:normal;font-weight:{w};font-display:swap;src:url('fonts/fluid_outfit_{w}.ttf') format('truetype')}}")
    css.append(f"@font-face{{font-family:'Shenyan UI CJK';font-style:normal;font-weight:{w};font-display:swap;src:url('fonts/shenyan_design_cjk_{w}.ttf') format('truetype')}}")
css.append("@font-face{font-family:'JetBrains Mono';font-style:normal;font-weight:400;font-display:swap;src:url('fonts/fluid_mono_regular.ttf') format('truetype')}")
css.append("@font-face{font-family:'JetBrains Mono';font-style:italic;font-weight:400;font-display:swap;src:url('fonts/fluid_mono_italic.ttf') format('truetype')}")
css.append(":root{--sans:'Outfit','Shenyan UI CJK',-apple-system,'PingFang SC','Microsoft YaHei',sans-serif}")
css.append("body{font-family:var(--sans)}")
(out / 'assets/shenyan-v6-fonts.css').write_text('\n'.join(css) + '\n')
for html in out.glob('*.html'):
    text = html.read_text()
    text = re.sub(r'<link[^>]+https://fonts\.googleapis\.com[^>]*>', '', text)
    if html.name == 'app.html':
        text = text.replace('V5 完整场景设计原型', 'V6 完整场景设计原型')
    if html.name == 'index.html':
        text = text.replace('V5 场景设计交付', 'V6 场景设计交付')
        text = text.replace('CURRENT DESIGN · V5', 'CURRENT DESIGN · V6').replace('打开 V5 交互原型', '打开 V6 交互原型').replace('神言神语 · V5 场景设计<br>', '神言神语 · V6 场景设计<br>')
        banner = '<section class="card" style="margin-bottom:28px"><h2>V6 · 素材补齐与原生同步</h2><p>继承 V5 的完整 38 屏与三主题设计，新增媒体文件图标、离线字体及修复后的原生源码。旧版本预览是历史资料；当前构建验证与限制请读 V6 交付说明。</p><p><a href="app.html">打开 38 屏场景</a> · <a href="assets/v6/file-icons-preview.svg">查看新增素材</a> · <a href="V6-DELIVERY.zh-CN.md">V6 交付说明</a> · <a href="V6-VERIFICATION.json">当前验证记录</a> · <a href="compose-v6/README.md">当前原生源码</a></p></section>'
        text = text.replace('<div class="wrap">', '<div class="wrap">' + banner)
    text = text.replace('</head>', '<link rel="stylesheet" href="assets/shenyan-v6-fonts.css">\n</head>')
    html.write_text(text)
# V5 filenames remain stable for inherited scene links; V6 owns the current edition and tokens.
js = out / 'assets/shenyan-v5-app.js'
js.write_text(js.read_text().replace("dataset.shenyanPageVersion='v5'", "dataset.shenyanPageVersion='v6'"))
tokens = json.loads((out / 'semantic-design-tokens.json').read_text())
tokens['schema'] = 'shenyan.design-tokens.v6'
tokens['motion'].update(pushMs=360, morphMs=360)
tokens['material']['light']['L4']['fill'] = 'rgba(250,247,242,.88)'
tokens['pageShellV5']['deviceSupport']['androidBuildVerified'] = True
tokens['pageShellV5']['state'] = 'V6 production sources compiled in PR #654; device/scene visual acceptance pending'
tokens['v6'] = {'source': 'compose-v6/', 'resources': 'android-v6/res/', 'newFileGlyphs': ['audio', 'video', 'archive'], 'fontFallback': 'Packaged fixed UI subsets; dynamic Chinese prose uses host system fallback'}
(out / 'semantic-design-tokens.json').write_text(json.dumps(tokens, ensure_ascii=False, indent=2) + '\n')
(out / '00-README.txt').write_text('神言神语 V6\n先读 V6-DELIVERY.zh-CN.md；完整页面入口 app.html；原生当前源码 compose-v6/。\nV5 与更早验证文件只作为历史记录，当前结果以 V6-VERIFICATION.json 为准。\n新增图标预览 assets/v6/file-icons-preview.svg。\n')
(out / 'compose-v6/README.md').write_text('当前 777 原生设计源码快照，替代 compose-v5/ 作为接入参考。\n已在 777 的真实 Gradle 工程构建，未提供独立 Gradle 模块。业务适配层依赖宿主 Android、R 资源及既有回调。\n将 android-v6/res/ 中资源与对应业务适配一起集成；不要直接复制网页演示数据。\n')
snapshot = {'version': 'v6', 'sourceBase': 'V5 full-scene package', 'integratedMainRevision': subprocess.check_output(['git', 'rev-parse', 'origin/main'], cwd=REPO, text=True).strip(), 'pullRequest': 654, 'files': {}}
for asset in sorted((out / 'compose-v6').rglob('*.kt')):
    snapshot['files'][asset.relative_to(out).as_posix()] = hashlib.sha256(asset.read_bytes()).hexdigest()
(out / 'V6-SOURCE-SNAPSHOT.json').write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n')
# Keep historical manifests explicitly named; the current manifest covers the whole V6 payload.
(out / 'MANIFEST.sha256').rename(out / 'V5-MANIFEST.history.sha256')
rows = [hashlib.sha256(f.read_bytes()).hexdigest() + '  ' + f.relative_to(out).as_posix() for f in sorted(out.rglob('*')) if f.is_file() and f.name != 'MANIFEST.sha256']
(out / 'MANIFEST.sha256').write_text('\n'.join(rows) + '\n')
archive = out.with_suffix('.zip')
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for asset in sorted(out.rglob('*')):
        if asset.is_file():
            z.write(asset, asset.relative_to(out))
print(json.dumps({'directory': str(out), 'archive': str(archive), 'size': archive.stat().st_size, 'nativeFiles': len(snapshot['files'])}, ensure_ascii=False))
