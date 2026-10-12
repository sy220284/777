#!/usr/bin/env python3
"""Editable, exact vector asset sheet; this is a design preview, not a device screenshot."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
themes = [
    ('Light', '#EDE6DA', '#F6F1E9', '#3A342D', ['#FFFFFF']*3 + ['#FAF7F2']*2, [.30, .46, .64, .88, 1], 'light'),
    ('Dark', '#24211E', '#292521', '#F5F0E8', ['#FFF5E4']*3 + ['#312B26', '#35312D'], [.055, .085, .12, .88, 1], 'dark'),
    ('Matte', '#0F0F10', '#18181A', '#F5F0E8', ['#181819', '#1E1E20', '#232324', '#29292B', '#29292B'], [1]*5, 'dark'),
]
svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="530" viewBox="0 0 1200 530">',
       '<title>Shenyan V6 · File assets and material tiers</title>',
       '<rect width="1200" height="530" fill="#EDE6DA"/>',
       '<text x="24" y="35" fill="#3A342D" font-family="sans-serif" font-size="22" font-weight="600">Shenyan V6 · File assets and material tiers</text>']
for i, (name, bg, bar, ink, fills, alpha, icon_theme) in enumerate(themes):
    x = 18 + i*398
    svg.append(f'<g transform="translate({x},55)"><rect width="386" height="455" rx="22" fill="{bg}"/>')
    if name != 'Matte':
        svg.append('<defs><radialGradient id="glow'+str(i)+'"><stop stop-color="#E8A87C" stop-opacity=".32"/><stop offset="1" stop-color="#E8A87C" stop-opacity="0"/></radialGradient></defs>')
        svg.append(f'<ellipse cx="70" cy="205" rx="160" ry="150" fill="url(#glow{i})"/>')
    svg.append(f'<rect x="14" y="14" width="358" height="48" rx="12" fill="{bar}"/><text x="30" y="45" fill="{ink}" font-family="sans-serif" font-size="18" font-weight="600">{name}</text>')
    svg.append(f'<text x="26" y="91" fill="{ink}" font-family="sans-serif" font-size="13">Audio / Video / Archive</text>')
    for j, kind in enumerate(['audio', 'video', 'archive']):
        icon = ET.parse(root / f'assets/file-icons/ic_ui_file{kind}_{icon_theme}.svg').getroot()
        paths = ''.join(ET.tostring(child, encoding='unicode') for child in icon if child.tag.endswith('path'))
        svg.append(f'<g transform="translate({32+j*119},110) scale(1.7)">{paths}</g>')
        svg.append(f'<text x="{32+j*119}" y="201" fill="{ink}" font-family="sans-serif" font-size="13">{kind.title()}</text>')
    for j, (fill, a) in enumerate(zip(fills, alpha)):
        y = 222+j*40
        svg.append(f'<rect x="22" y="{y}" width="342" height="33" rx="9" fill="{fill}" fill-opacity="{a}" stroke="{ink}" stroke-opacity=".10"/>')
        svg.append(f'<text x="36" y="{y+22}" fill="{ink}" font-family="sans-serif" font-size="12">L{j+1} · {round(a*100,1)}% opacity</text>')
    svg.append(f'<text x="26" y="439" fill="{ink}" font-family="sans-serif" font-size="11">40dp vector grid · opaque top bar · 48dp touch target</text></g>')
svg.append('</svg>')
(root / 'assets/file-icons-preview.svg').write_text('\n'.join(svg)+'\n')
print('Created editable three-theme asset preview')
