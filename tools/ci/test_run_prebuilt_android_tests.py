import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("run_prebuilt_android_tests.py")
spec = importlib.util.spec_from_file_location("run_prebuilt_android_tests", SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def instrumentation(classes):
    parts = []
    for index, name in enumerate(classes, 1):
        parts += [
            f"INSTRUMENTATION_STATUS: class={name}",
            f"INSTRUMENTATION_STATUS: current={index}",
            "INSTRUMENTATION_STATUS: test=works",
            "INSTRUMENTATION_STATUS_CODE: 1",
            f"INSTRUMENTATION_STATUS: class={name}",
            f"INSTRUMENTATION_STATUS: current={index}",
            "INSTRUMENTATION_STATUS: test=works",
            "INSTRUMENTATION_STATUS_CODE: 0",
        ]
    return "\n".join(parts + [f"OK ({len(classes)} tests)", "INSTRUMENTATION_CODE: -1"])


class SharedApkDeviceTests(unittest.TestCase):
    def test_all_requested_classes_really_passed(self):
        classes = ["example.FirstTest", "example.SecondTest"]
        self.assertEqual(len(tool.parse_results(instrumentation(classes), classes)), 2)

    def test_missing_class_must_fail(self):
        with self.assertRaisesRegex(ValueError, "未执行"):
            tool.parse_results(instrumentation(["example.FirstTest"]), ["example.FirstTest", "example.SecondTest"])

    def test_incomplete_instrumentation_must_fail(self):
        with self.assertRaisesRegex(ValueError, "未正常结束"):
            tool.parse_results("OK (1 test)", ["example.FirstTest"])

    def test_zero_cases_must_fail(self):
        with self.assertRaisesRegex(ValueError, "非零"):
            tool.parse_results("OK (0 tests)\nINSTRUMENTATION_CODE: -1", ["example.FirstTest"])

    def test_summary_count_mismatch_must_fail(self):
        with self.assertRaisesRegex(ValueError, "数量"):
            tool.parse_results(instrumentation(["example.FirstTest"]).replace("OK (1 test)", "OK (2 tests)"), ["example.FirstTest"])

    def test_apk_digest_and_manifest_enforced(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            names = ["app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
            for i, name in enumerate(names):
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(f"apk{i}".encode())
            manifest = root / "app/build/outputs/apk/SHA256SUMS"
            manifest.write_text("".join(f"{hashlib.sha256((root / p).read_bytes()).hexdigest()}  {p}\n" for p in names))
            with patch.object(tool, "ROOT", root):
                self.assertEqual(len(tool.verify_apks(manifest.parent)), 2)
                (root / names[0]).write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "校验失败"):
                    tool.verify_apks(manifest.parent)

    def test_invalid_manifest_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest = Path(folder) / "SHA256SUMS"
            manifest.write_text("untrusted\n")
            with self.assertRaisesRegex(ValueError, "无效"):
                tool.verify_apks(Path(folder))

    def test_report_writes_real_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            tool.save_results(Path(folder), "extension-market", "device returned failure", error=RuntimeError("失效"))
            self.assertTrue((Path(folder) / "TEST-extension-market.xml").exists())
            self.assertIn("失效", (Path(folder) / "TEST-extension-market.xml").read_text())


if __name__ == "__main__":
    unittest.main()
