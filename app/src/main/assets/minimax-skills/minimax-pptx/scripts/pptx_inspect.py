#!/usr/bin/env python3
"""Inspect Office presentations using the mobile Python standard library."""
import argparse
import json
import re
import zipfile
import xml.etree.ElementTree as ET
p = argparse.ArgumentParser(); p.add_argument('file'); args = p.parse_args()
with zipfile.ZipFile(args.file) as z:
    if z.testzip():
        raise ValueError('PPTX ZIP 校验失败')
    for required in ('[Content_Types].xml', '_rels/.rels', 'ppt/presentation.xml'):
        if required not in z.namelist():
            raise ValueError('缺少必需部件：' + required)
    for name in z.namelist():
        if name.endswith(('.xml', '.rels')):
            ET.fromstring(z.read(name))
    slides = sorted((n for n in z.namelist() if re.fullmatch(r'ppt/slides/slide\d+\.xml', n)), key=lambda n:int(re.search(r'slide(\d+)', n).group(1)))
    if not slides:
        raise ValueError('演示文稿没有页面')
    print(json.dumps({'valid': True, 'slides': [{'part':n, 'text':[t.text or '' for t in ET.fromstring(z.read(n)).findall('.//{http://schemas.openxmlformats.org/drawingml/2006/main}t')]} for n in slides]}, ensure_ascii=False))
