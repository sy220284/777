#!/usr/bin/env python3
"""Mobile DOCX creation, inspection and text editing; Python standard library only."""
import argparse
import html
import json
import os
from pathlib import Path
import tempfile
import zipfile
import xml.etree.ElementTree as ET

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/package/2006/relationships'
ET.register_namespace('w', W)
def el(name, attrs=None):
    return ET.Element('{%s}%s' % (W, name), {'{%s}%s' % (W, k): str(v) for k, v in (attrs or {}).items()})
def paragraph(text, style=None):
    p = el('p')
    if style:
        prop = ET.SubElement(p, '{%s}pPr' % W)
        prop.append(el('pStyle', {'val': style}))
    run = ET.SubElement(p, '{%s}r' % W)
    t = ET.SubElement(run, '{%s}t' % W, {'{http://www.w3.org/XML/1998/namespace}space': 'preserve'})
    t.text = text
    return p

def validate(path):
    with zipfile.ZipFile(path) as z:
        if z.testzip():
            raise ValueError('DOCX ZIP 校验失败')
        for name in ('[Content_Types].xml', '_rels/.rels', 'word/document.xml'):
            if name not in z.namelist():
                raise ValueError('缺少必需部件：' + name)
        for name in z.namelist():
            if name.endswith(('.xml', '.rels')):
                ET.fromstring(z.read(name))
        root = ET.fromstring(z.read('word/document.xml'))
        if root.tag != '{%s}document' % W or root.find('w:body', {'w': W}) is None:
            raise ValueError('缺少 Word 正文')
    return {'valid': True, 'paragraphs': len(root.findall('.//w:p', {'w': W}))}

def write_package(output, members):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.docx-', suffix='.docx', dir=output.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, data in members.items():
                z.writestr(name, data)
        validate(temporary)
        os.replace(temporary, output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

def create(data, output):
    document = el('document')
    body = ET.SubElement(document, '{%s}body' % W)
    title = str(data.get('title', ''))
    preview = ['<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:16px/1.7 sans-serif;max-width:850px;margin:auto;padding:24px;overflow-wrap:anywhere}table{border-collapse:collapse;width:100%}td{border:1px solid #ccc;padding:8px}h1{font-size:26px}</style><body>']
    if title:
        body.append(paragraph(title, 'Title'))
        preview.append('<h1>' + html.escape(title) + '</h1>')
    for part in data.get('paragraphs', []):
        if isinstance(part, str):
            part = {'text': part}
        text = str(part.get('text', ''))
        style = part.get('style')
        if style not in (None, 'Heading1', 'Heading2', 'Title'):
            raise ValueError('不支持的段落样式：' + str(style))
        body.append(paragraph(text, style))
        tag = {'Heading1': 'h2', 'Heading2': 'h3', 'Title': 'h1'}.get(style, 'p')
        preview.append('<%s>%s</%s>' % (tag, html.escape(text).replace('\n', '<br>'), tag))
    for rows in data.get('tables', []):
        table = el('tbl')
        preview.append('<table>')
        for row in rows:
            tr = el('tr')
            preview.append('<tr>')
            for value in row:
                cell = el('tc')
                cell.append(paragraph(str(value)))
                tr.append(cell)
                preview.append('<td>' + html.escape(str(value)) + '</td>')
            table.append(tr)
            preview.append('</tr>')
        body.append(table)
        preview.append('</table>')
    section = el('sectPr')
    section.append(el('pgSz', {'w': 11906, 'h': 16838}))
    section.append(el('pgMar', {'top': 1440, 'right': 1440, 'bottom': 1440, 'left': 1440}))
    body.append(section)
    styles = el('styles')
    for name, size in [('Normal', 24), ('Title', 40), ('Heading1', 32), ('Heading2', 28)]:
        style = el('style', {'type': 'paragraph', 'styleId': name})
        style.append(el('name', {'val': name}))
        props = el('rPr')
        props.append(el('sz', {'val': size}))
        if name != 'Normal':
            props.append(el('b'))
        style.append(props)
        styles.append(style)
    members = {
        '[Content_Types].xml': '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>',
        '_rels/.rels': '<Relationships xmlns="%s"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>' % R,
        'word/_rels/document.xml.rels': '<Relationships xmlns="%s"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>' % R,
        'word/document.xml': ET.tostring(document, encoding='utf-8', xml_declaration=True),
        'word/styles.xml': ET.tostring(styles, encoding='utf-8', xml_declaration=True),
    }
    write_package(output, members)
    Path(output).with_suffix('.html').write_text('\n'.join(preview) + '</body></html>', encoding='utf-8')
    return validate(output)

def replace(source, output, old, new):
    if not old:
        raise ValueError('原文不能为空')
    with zipfile.ZipFile(source) as z:
        members = {name: z.read(name) for name in z.namelist()}
    root = ET.fromstring(members['word/document.xml'])
    changes = 0
    for p in root.findall('.//w:p', {'w': W}):
        nodes = p.findall('.//w:t', {'w': W})
        text = ''.join(n.text or '' for n in nodes)
        start = text.rfind(old)
        while start >= 0:
            end = start + len(old)
            offset = 0
            inserted = False
            for node in nodes:
                value = node.text or ''
                stop = offset + len(value)
                if offset < end and stop > start:
                    prefix = value[:max(0, start - offset)]
                    suffix = value[max(0, end - offset):]
                    node.text = prefix + (new if not inserted else '') + suffix
                    node.set('{http://www.w3.org/XML/1998/namespace}space', 'preserve')
                    inserted = True
                offset = stop
            changes += 1
            # Work backwards so earlier character positions retain their original offsets.
            text = text[:start]
            start = text.rfind(old)
    if not changes:
        raise ValueError('未找到原文，未创建输出文件')
    members['word/document.xml'] = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    write_package(output, members)
    return {'replacements': changes, **validate(output)}

def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    c = sub.add_parser('create'); c.add_argument('input'); c.add_argument('output')
    r = sub.add_parser('read'); r.add_argument('input')
    v = sub.add_parser('validate'); v.add_argument('input')
    e = sub.add_parser('replace'); e.add_argument('input'); e.add_argument('output'); e.add_argument('--old', required=True); e.add_argument('--new', required=True)
    args = parser.parse_args()
    if args.command == 'create':
        result = create(json.loads(Path(args.input).read_text(encoding='utf-8')), args.output)
    elif args.command == 'replace':
        result = replace(args.input, args.output, args.old, args.new)
    elif args.command == 'validate':
        result = validate(args.input)
    else:
        validate(args.input)
        with zipfile.ZipFile(args.input) as z:
            root = ET.fromstring(z.read('word/document.xml'))
        result = {'paragraphs': [''.join(p.itertext()) for p in root.findall('.//w:p', {'w': W})]}
    print(json.dumps(result, ensure_ascii=False))
if __name__ == '__main__':
    main()
