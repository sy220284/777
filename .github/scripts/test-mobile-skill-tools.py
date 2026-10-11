#!/usr/bin/env python3
"""Exercise the shipped mobile document executors, including real Office archives."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/minimax-skills'
WORD = ASSETS / 'minimax-docx/scripts/mobile_docx.py'
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
class WordTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.input = self.path / 'input.json'
        self.input.write_text(json.dumps({'title':'中文报告', 'paragraphs':[{'text':'背景 & <测试>', 'style':'Heading1'}, {'text':'承诺甲承诺甲'}], 'tables':[[['项目','结果'],['校验','通过']]]}, ensure_ascii=False))
        self.doc = self.path / 'test.docx'
    def run_tool(self, *args):
        return subprocess.run([sys.executable, str(WORD), *map(str, args)], text=True, capture_output=True)
    def test_creates_chinese_docx_and_escaped_phone_preview(self):
        result = self.run_tool('create', self.input, self.doc)
        self.assertEqual(0, result.returncode, result.stderr)
        with zipfile.ZipFile(self.doc) as z:
            body = ET.fromstring(z.read('word/document.xml'))
            self.assertEqual('中文报告', body.find('.//{%s}t' % W).text)
            self.assertEqual(1, len(body.findall('.//{%s}tbl' % W)))
            self.assertIn('word/styles.xml', z.namelist())
        preview = self.doc.with_suffix('.html').read_text()
        self.assertIn('&amp; &lt;测试&gt;', preview)
        self.assertIn('viewport', preview)
    def test_replaces_across_runs_without_dropping_media_or_headers(self):
        self.assertEqual(0, self.run_tool('create', self.input, self.doc).returncode)
        with zipfile.ZipFile(self.doc) as z:
            members = {n:z.read(n) for n in z.namelist()}
        body = ET.fromstring(members['word/document.xml'])
        p = body.findall('.//{%s}p' % W)[2]
        for child in list(p): p.remove(child)
        for text in ['承','诺甲承','诺甲']:
            run = ET.SubElement(p, '{%s}r' % W); ET.SubElement(run, '{%s}t' % W).text = text
        members['word/document.xml'] = ET.tostring(body)
        members['word/media/user.bin'] = b'user-data'
        members['word/header1.xml'] = ('<w:hdr xmlns:w="%s"><w:p/></w:hdr>' % W).encode()
        with zipfile.ZipFile(self.doc, 'w') as z:
            for n,v in members.items(): z.writestr(n,v)
        output = self.path / 'edited.docx'
        result = self.run_tool('replace', self.doc, output, '--old','承诺甲','--new','承诺乙更新')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, json.loads(result.stdout)['replacements'])
        with zipfile.ZipFile(output) as z:
            text = ''.join(ET.fromstring(z.read('word/document.xml')).itertext())
            self.assertIn('承诺乙更新承诺乙更新', text)
            self.assertEqual(b'user-data',z.read('word/media/user.bin'))
            self.assertEqual(members['word/header1.xml'],z.read('word/header1.xml'))
    def test_missing_text_never_produces_a_false_success_file(self):
        self.assertEqual(0,self.run_tool('create',self.input,self.doc).returncode)
        output = self.path / 'absent.docx'
        self.assertNotEqual(0,self.run_tool('replace',self.doc,output,'--old','不存在','--new','新内容').returncode)
        self.assertFalse(output.exists())

class SpreadsheetTest(unittest.TestCase):
    def test_real_xlsx_with_chinese_and_numeric_cells(self):
        tool = ASSETS / 'minimax-xlsx/scripts/mobile_xlsx.py'
        with tempfile.TemporaryDirectory() as d:
            source = Path(d)/'data.json'; output = Path(d)/'sheet.xlsx'
            source.write_text(json.dumps({'rows':[['项目','数量'],['测试 & 中文',2],['合计',2]]}))
            run = subprocess.run([sys.executable,str(tool),'create',str(source),str(output)],capture_output=True,text=True)
            self.assertEqual(0,run.returncode,run.stderr)
            result = subprocess.run([sys.executable,str(tool),'read',str(output)],capture_output=True,text=True)
            self.assertEqual(0,result.returncode,result.stderr)
            cells = json.loads(result.stdout)['sheets'][0]['cells']
            self.assertEqual('测试 & 中文',cells[2]['value']); self.assertEqual('2',cells[5]['value'])
            with zipfile.ZipFile(output) as z:
                self.assertIn('_rels/.rels',z.namelist())
                self.assertNotIn(b'<!--',z.read('xl/workbook.xml'))

class PresentationTest(unittest.TestCase):
    def test_real_pptx_generation_and_mobile_preview(self):
        module = os.environ['MOBILE_PPTX_MODULE']
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); source = root/'slides.json'; output = root/'slides.pptx'
            source.write_text(json.dumps({'title':'中文演示','slides':[{'title':'概览','body':['中文 & <内容>','第二条']},{'title':'结束','body':['完成']}]}))
            run = subprocess.run(['node',str(ASSETS/'minimax-pptx/scripts/mobile_pptx.cjs'),str(source),str(output),module],text=True,capture_output=True)
            self.assertEqual(0,run.returncode,run.stderr)
            inspected = subprocess.run([sys.executable,str(ASSETS/'minimax-pptx/scripts/pptx_inspect.py'),str(output)],text=True,capture_output=True)
            self.assertEqual(0,inspected.returncode,inspected.stderr)
            slides = json.loads(inspected.stdout)['slides']
            self.assertEqual(2,len(slides)); self.assertIn('概览',slides[0]['text'])
            self.assertIn('中文 & <内容>',slides[0]['text'])
            self.assertIn('&amp; &lt;内容&gt;',output.with_suffix('.html').read_text())

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--word-only',action='store_true'); args = parser.parse_args()
    loader = unittest.TestLoader(); suite = loader.loadTestsFromTestCase(WordTest)
    suite.addTests(loader.loadTestsFromTestCase(SpreadsheetTest))
    if not args.word_only: suite.addTests(loader.loadTestsFromTestCase(PresentationTest))
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful(): sys.exit(1)
