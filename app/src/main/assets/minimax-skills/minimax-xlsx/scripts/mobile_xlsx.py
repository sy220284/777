#!/usr/bin/env python3
"""Create and inspect XLSX using only Python's standard library."""
import argparse
import json
from pathlib import Path
import posixpath
import zipfile
import xml.etree.ElementTree as ET

S = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
P = 'http://schemas.openxmlformats.org/package/2006/relationships'
ET.register_namespace('', S)
def column(index):
    result = ''
    while index:
        index, digit = divmod(index-1, 26); result = chr(65+digit)+result
    return result

def create(source, output):
    rows = json.loads(Path(source).read_text())['rows']
    template = Path(__file__).resolve().parents[1] / 'upstream/templates/minimal_xlsx'
    members = {}
    for path in template.rglob('*'):
        if path.is_file():
            data = path.read_bytes()
            # XML comments in workbook child collections confuse some Office readers.
            if path.suffix in ('.xml', '.rels'):
                data = ET.tostring(ET.fromstring(data), encoding='utf-8', xml_declaration=True)
            members[path.relative_to(template).as_posix()] = data
    root = ET.fromstring(members['xl/worksheets/sheet1.xml'])
    body = root.find('{%s}sheetData' % S)
    for child in list(body): body.remove(child)
    for i, values in enumerate(rows, 1):
        row = ET.SubElement(body, '{%s}row' % S, {'r':str(i)})
        for j, value in enumerate(values, 1):
            cell = ET.SubElement(row, '{%s}c' % S, {'r':column(j)+str(i)})
            if isinstance(value, bool):
                cell.set('t','b'); ET.SubElement(cell,'{%s}v'%S).text = '1' if value else '0'
            elif isinstance(value,(int,float)):
                ET.SubElement(cell,'{%s}v'%S).text = str(value)
            else:
                cell.set('t','inlineStr'); inline=ET.SubElement(cell,'{%s}is'%S)
                ET.SubElement(inline,'{%s}t'%S,{'{http://www.w3.org/XML/1998/namespace}space':'preserve'}).text = '' if value is None else str(value)
    members['xl/worksheets/sheet1.xml'] = ET.tostring(root,encoding='utf-8',xml_declaration=True)
    Path(output).parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
        for name,data in members.items(): z.writestr(name,data)
    return inspect(output)

def inspect(path):
    sheets=[]
    with zipfile.ZipFile(path) as z:
        if z.testzip(): raise ValueError('ZIP 校验失败')
        for name in z.namelist():
            if name.endswith(('.xml','.rels')): ET.fromstring(z.read(name))
        workbook=ET.fromstring(z.read('xl/workbook.xml'))
        rels={x.get('Id'):x.get('Target') for x in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
        strings=[]
        if 'xl/sharedStrings.xml' in z.namelist():
            strings=[''.join(x.itertext()) for x in ET.fromstring(z.read('xl/sharedStrings.xml'))]
        for sheet in workbook.findall('{%s}sheets/{%s}sheet'%(S,S)):
            target=rels[sheet.get('{%s}id'%R)]
            name=posixpath.normpath(target.lstrip('/') if target.startswith('/') else 'xl/'+target)
            cells=[]
            for c in ET.fromstring(z.read(name)).findall('.//{%s}sheetData/{%s}row/{%s}c'%(S,S,S)):
                kind=c.get('t'); value=c.findtext('{%s}v'%S)
                if kind=='s': value=strings[int(value)]
                elif kind=='inlineStr': value=''.join(c.find('{%s}is'%S).itertext())
                formula=c.findtext('{%s}f'%S)
                cells.append({'address':c.get('r'),'type':kind,'value':value,'formula':formula})
            sheets.append({'name':sheet.get('name'),'cells':cells})
    return {'valid':True,'sheets':sheets}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['create','read']);parser.add_argument('input');parser.add_argument('output',nargs='?');args=parser.parse_args()
    if args.command=='create' and not args.output: parser.error('create 需要输出路径')
    result=create(args.input,args.output) if args.command=='create' else inspect(args.input)
    print(json.dumps(result,ensure_ascii=False))
