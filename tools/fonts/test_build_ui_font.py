"""Font subsetting: lexical coverage, resource extraction, and pinned source integrity."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("build_ui_font", HERE / "build_ui_font.py")
font = importlib.util.module_from_spec(spec)
spec.loader.exec_module(font)

class UiFontTests(unittest.TestCase):
    def test_kotlin_fixed_literals_ignore_comments(self):
        source = 'val x="文字" // "忽略"\n/* nested /* "跳过" */ */ val y="""人物日记"""\n'
        self.assertEqual(list(font.kotlin_literals(source)), ["文字", "人物日记"])

    def test_xml_and_kotlin_cjk_extraction(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            res = base / "res" / "values"
            res.mkdir(parents=True)
            (res / "strings.xml").write_text('<resources><string name="a">设置按钮</string><string-array name="b"><item>工作</item></string-array></resources>', encoding="utf-8")
            ui = base / "ui"
            ui.mkdir()
            (ui / "Demo.kt").write_text('val x="人物日记"', encoding="utf-8")
            needed = font.used_characters(base / "res", ui)
            self.assertTrue(set(map(ord, "设置按钮工作人物日记")).issubset(needed))
            self.assertNotIn(ord("忽"), needed)

    def test_git_blob_hash_and_font_source(self):
        self.assertEqual(font.git_sha(b"test"), "30d74d258442c7c65512eafab474568dd706c430")
        data = (HERE / "source" / "NotoSansSC-wght.ttf").read_bytes()
        self.assertEqual(len(data), 17_772_300)
        self.assertEqual(font.git_sha(data), font.SOURCE_GIT_BLOB)

if __name__ == "__main__":
    unittest.main()
