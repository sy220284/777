import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("select_android_checks.py")
spec = importlib.util.spec_from_file_location("select_android_checks", SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class CiSelectChecksTest(unittest.TestCase):
    def test_general_jvm_test_only(self):
        self.assertEqual(tool.select_checks(["app/src/test/java/example/NetworkTest.kt"]),
                         {"ui": False, "market": False, "migration": False})

    def test_visual_ui_only(self):
        result = tool.select_checks(["app/src/main/java/com/labteto/dshmobile/ui/screens/tasks/TasksScreen.kt"])
        self.assertEqual(result, {"ui": True, "market": False, "migration": False})

    def test_plugin_changes_also_check_user_journey(self):
        self.assertEqual(tool.select_checks(["app/src/main/java/com/labteto/dshmobile/local/tools/LocalTool.kt"]),
                         {"ui": True, "market": True, "migration": False})

    def test_database_changes_check_migration_and_ui(self):
        self.assertEqual(tool.select_checks(["app/src/main/java/com/labteto/dshmobile/local/session/SqliteRecord.kt"]),
                         {"ui": True, "market": False, "migration": True})

    def test_shared_change_checks_every_device(self):
        self.assertEqual(tool.select_checks(["harness-core/src/main/kotlin/Agent.kt"]),
                         {"ui": True, "market": True, "migration": True})

    def test_ci_source_changes_check_every_device(self):
        self.assertEqual(tool.select_checks(["tools/ci/run_prebuilt_android_tests.py"]),
                         {"ui": True, "market": True, "migration": True})

    def test_ui_audit_requires_ui_and_market(self):
        self.assertEqual(tool.select_checks(["tools/ui/run-native-ui-audit.py"]),
                         {"ui": True, "market": True, "migration": False})

    def test_mixed_pr_cannot_hide_ordinary_app_device_checks(self):
        paths = [
            "app/src/test/java/com/labteto/dshmobile/local/tools/ToolTest.kt",
            "app/src/main/AndroidManifest.xml",
        ]
        self.assertEqual(tool.select_checks(paths),
                         {"ui": True, "market": True, "migration": False})

    def test_deleted_source_must_still_be_diffed(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "pull_request",
            "CI_BASE_SHA": "a" * 40,
            "CI_HEAD_SHA": "b" * 40,
        }):
            with patch.object(tool.subprocess, "check_output", return_value=b"app/src/main/AndroidManifest.xml\0") as diff:
                self.assertEqual(tool.changed_files(), ["app/src/main/AndroidManifest.xml"])
                self.assertIn("D", diff.call_args.args[0][2])

    def test_manual_runs_full(self):
        self.assertEqual(tool.select_checks([], manual=True),
                         {"ui": True, "market": True, "migration": True})

    def test_missing_changes_fail_closed(self):
        with self.assertRaises(ValueError):
            tool.select_checks([])


if __name__ == "__main__":
    unittest.main()
