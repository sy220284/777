import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("prune_ui_artifacts.py")
spec = importlib.util.spec_from_file_location("prune_ui_artifacts", SCRIPT)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


def item(name, ident, timestamp):
    return {"name": name, "id": ident, "created_at": timestamp,
            "expired": False, "size_in_bytes": 100}


class PruneMediaTests(unittest.TestCase):
    def test_keep_newest_in_each_media_family(self):
        values = [
            item("777-ui-videos-and-results-36", 1, "2026-11-17T01:00:00Z"),
            item("777-ui-videos-and-results-36", 2, "2026-11-18T01:00:00Z"),
            item("777-ui-videos-and-results-37.0", 3, "2026-11-18T01:00:00Z"),
            item("extension-market-device-test-videos-and-results", 4, "2026-11-17T01:00:00Z"),
            item("extension-market-device-test-videos-and-results", 5, "2026-11-18T01:00:00Z"),
        ]
        keep, remove = tool.plan_cleanup(values)
        self.assertEqual({x["id"] for x in keep.values()}, {2, 3, 5})
        self.assertEqual({x["id"] for x in remove}, {1, 4})

    def test_toolchain_and_reports_are_untouched(self):
        values = [
            item("777-toolchain-emulator-latest", 8, "2026-11-17T01:00:00Z"),
            item("architecture3-full-callgraph", 9, "2026-11-17T01:00:00Z"),
            item("session-storage-v7-android-tests", 10, "2026-11-17T01:00:00Z"),
        ]
        self.assertEqual(tool.plan_cleanup(values), ({}, []))

    def test_alias_and_timestamped_names(self):
        values = [
            item("777-ui-videos-and-results-37", 11, "2026-11-17T01:00:00Z"),
            item("777-ui-videos-and-results-37.0", 12, "2026-11-18T01:00:00Z"),
            item("device-screenshots-run-1234567890", 13, "2026-11-17T01:00:00Z"),
            item("device-screenshots-run-1234567891", 14, "2026-11-18T01:00:00Z"),
        ]
        keep, remove = tool.plan_cleanup(values)
        self.assertEqual({x["id"] for x in keep.values()}, {12, 14})
        self.assertEqual({x["id"] for x in remove}, {11, 13})

    def test_dry_run_then_real_delete(self):
        class Fake:
            def __init__(self):
                self.deleted = []
            def list_all(self):
                return [
                    item("ui-screenshots", 20, "2026-11-17T01:00:00Z"),
                    item("ui-screenshots", 21, "2026-11-18T01:00:00Z"),
                    item("777-toolchain-jdk-latest", 22, "2026-11-17T01:00:00Z"),
                ]
            def delete(self, ident):
                self.deleted.append(ident)

        fake = Fake()
        self.assertEqual(tool.main([], fake), 0)
        self.assertEqual(fake.deleted, [])
        self.assertEqual(tool.main(["--apply"], fake), 0)
        self.assertEqual(fake.deleted, [20])

    def test_missing_id_fails_loudly(self):
        with self.assertRaises(ValueError):
            tool.plan_cleanup([{"name": "test-video", "created_at": "2026-11-18T00:00:00Z"}])


if __name__ == "__main__":
    unittest.main()
