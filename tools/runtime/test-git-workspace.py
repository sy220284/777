"""Run the production workspace implementation against real local Git repositories."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

SOURCE = Path(__file__).resolve().parents[2] / (
    "harness-runtime-android/src/main/resources/com/labteto/dshmobile/runtime/git_workspace.py"
)
spec = importlib.util.spec_from_file_location("git_workspace", SOURCE)
workspace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workspace)


class GitWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "source"
        self.root.mkdir()
        self.store = Path(self.temporary.name) / "agents"
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@localhost")
        (self.root / "code.txt").write_text("first\nsecond\nthird\n")
        self.git("add", ".")
        self.git("commit", "-qm", "initial")
        self.identity = "a" * 32

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args])

    def call(self, action, **arguments):
        return workspace.operate(self.root, self.store, "session", action,
                                 dict(workspace_id=self.identity, **arguments))

    def fork(self):
        return Path(self.call("fork")["root"])

    def test_dirty_and_untracked_baseline_preserves_head_and_index(self):
        (self.root / "code.txt").write_text("staged\nsecond\nthird\n")
        self.git("add", "code.txt")
        (self.root / "code.txt").write_text("unstaged\nsecond\nthird\n")
        (self.root / "new.bin").write_bytes(bytes(range(256)))
        head, index = self.git("rev-parse", "HEAD"), self.git("write-tree")
        child = self.fork()
        self.assertEqual((self.root / "code.txt").read_bytes(), (child / "code.txt").read_bytes())
        self.assertEqual(bytes(range(256)), (child / "new.bin").read_bytes())
        self.assertFalse(self.call("status")["changed"])
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual(index, self.git("write-tree"))
        self.assertEqual(str(child), self.call("fork")["root"])

    def test_merge_independent_parent_and_child_changes_is_repeatable(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (self.root / "parent.txt").write_text("parent keeps working")
        (child / "binary.dat").write_bytes(b"\0" * 200 + bytes(range(256)))
        expected = self.call("status")["tree"]
        index = self.git("write-tree")
        result = self.call("merge", expected_tree=expected)
        self.assertEqual("merged", result["state"])
        self.assertEqual("CHILD\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertEqual("parent keeps working", (self.root / "parent.txt").read_text())
        self.assertEqual((child / "binary.dat").read_bytes(), (self.root / "binary.dat").read_bytes())
        self.assertEqual(index, self.git("write-tree"))
        self.assertEqual("already_present", self.call("merge", expected_tree=expected)["state"])

    def test_conflict_never_modifies_shared_files_or_index(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        (child / "extra.txt").write_text("must not partially merge")
        before = (self.root / "code.txt").read_bytes()
        index = self.git("write-tree")
        result = self.call("merge", expected_tree=self.call("status")["tree"])
        self.assertEqual("conflict", result["state"])
        self.assertEqual(before, (self.root / "code.txt").read_bytes())
        self.assertFalse((self.root / "extra.txt").exists())
        self.assertEqual(index, self.git("write-tree"))

    def test_changed_review_token_cannot_merge_or_discard(self):
        child = self.fork()
        reviewed = self.call("status")["tree"]
        (child / "code.txt").write_text("late change")
        for action in ("merge", "discard"):
            with self.assertRaisesRegex(ValueError, "changed since review"):
                self.call(action, expected_tree=reviewed)
        self.assertTrue(child.is_dir())
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())

    def test_cross_session_and_repository_escape_are_rejected(self):
        self.fork()
        with self.assertRaisesRegex(ValueError, "another session"):
            workspace.operate(self.root, self.store, "foreign", "status", dict(workspace_id=self.identity))
        self.identity = "b" * 32
        with self.assertRaisesRegex(ValueError, "outside workspace"):
            self.call("fork", repository="..")

    def test_discard_removes_only_child_and_keeps_receipt(self):
        child = self.fork()
        expected = self.call("status")["tree"]
        self.assertEqual("discarded", self.call("discard", expected_tree=expected)["state"])
        self.assertFalse(child.exists())
        self.assertTrue((self.root / "code.txt").is_file())
        self.assertEqual("discarded", self.call("discard", expected_tree=expected)["state"])
        with self.assertRaisesRegex(ValueError, "discarded"):
            self.call("fork")

    def test_interrupted_creation_recovers_only_unadmitted_child(self):
        original_save = workspace.save
        def fail_ready(path, value):
            if value["state"] == "ready":
                raise OSError("simulated process death before ready persisted")
            original_save(path, value)
        with patch.object(workspace, "save", fail_ready):
            with self.assertRaisesRegex(OSError, "simulated process death"):
                self.fork()
        child = self.fork()
        self.assertEqual("first\nsecond\nthird\n", (child / "code.txt").read_text())
        self.assertFalse(self.call("status")["changed"])
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())

    def test_source_edit_during_merge_preflight_is_preserved_and_merge_stops(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        reviewed = self.call("status")["tree"]
        original_git = workspace.git
        def concurrent_edit(root, *args, **kwargs):
            result = original_git(root, *args, **kwargs)
            if args[:2] == ("apply", "--check"):
                (self.root / "new-user-file.txt").write_text("user edit")
            return result
        with patch.object(workspace, "git", concurrent_edit):
            with self.assertRaisesRegex(ValueError, "source changed during merge"):
                self.call("merge", expected_tree=reviewed)
        self.assertEqual("user edit", (self.root / "new-user-file.txt").read_text())
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())


if __name__ == "__main__":
    unittest.main()
