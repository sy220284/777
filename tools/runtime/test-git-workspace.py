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

    def quality_decision(self, resolution="auto"):
        return dict(resolution=resolution, reviewed_both=True,
                    reason="Test fixture: both implementations were compared before choosing the reviewed tree.",
                    criteria=dict(correctness="compare the asserted final file contents",
                                  experience="no device UX evidence is claimed by the fixture",
                                  maintainability="keep unrelated parent and child modifications"))

    def call(self, action, **arguments):
        if action == "merge":
            # Legacy lifecycle cases use a fully reviewed preview; production itself never
            # invents these tokens, as asserted in test_merge_requires_full_review_receipt.
            pending = self.call("status").get("integration", {})
            if pending.get("state") not in ("staging", "applying", "needs_inspection"):
                expected = arguments["expected_tree"]
                mode = arguments.get("resolution", "auto")
                if "expected_source_tree" not in arguments:
                    proposal = self.call("preview_merge", expected_tree=expected,
                                         resolution=mode, path_choices=arguments.get("path_choices", {}))
                    if proposal["state"] != "ready":
                        return proposal
                    arguments.update(expected_source_tree=proposal["source_tree"],
                                     expected_merged_tree=proposal["merged_tree"])
                arguments.setdefault("resolution", mode)
                arguments.setdefault("quality_decision", self.quality_decision(mode))
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

    def test_preview_locks_parent_tree_and_exact_merge_result(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        reviewed = self.call("status")["tree"]
        first = self.call("preview_merge", expected_tree=reviewed)
        self.assertEqual("ready", first["state"])
        self.assertFalse(first["diff_truncated"])
        self.assertIn("merged_tree", first)
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        (self.root / "parent-extra.txt").write_text("parent changed after preview")
        with self.assertRaisesRegex(ValueError, "source changed since merge preview"):
            self.call("merge", expected_tree=reviewed,
                      expected_source_tree=first["source_tree"],
                      expected_merged_tree=first["merged_tree"])
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        second = self.call("preview_merge", expected_tree=reviewed)
        with self.assertRaisesRegex(ValueError, "integration result changed since merge preview"):
            self.call("merge", expected_tree=reviewed,
                      expected_source_tree=second["source_tree"],
                      expected_merged_tree="0" * 40)
        merged = self.call("merge", expected_tree=reviewed,
                           expected_source_tree=second["source_tree"],
                           expected_merged_tree=second["merged_tree"])
        self.assertEqual("merged", merged["state"])
        self.assertEqual("CHILD\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertEqual("parent changed after preview", (self.root / "parent-extra.txt").read_text())

    def test_conflict_and_truncated_preview_do_not_offer_merge_token(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        preview = self.call("preview_merge", expected_tree=self.call("status")["tree"])
        self.assertEqual("conflict", preview["state"])
        self.assertNotIn("merged_tree", preview)
        self.assertEqual("PARENT\nsecond\nthird\n", (self.root / "code.txt").read_text())
        (self.root / "code.txt").write_text("first\nsecond\nthird\n")
        (child / "huge.txt").write_text("X" * 270000 + "\n")
        preview = self.call("preview_merge", expected_tree=self.call("status")["tree"])
        self.assertEqual("needs_full_review", preview["state"])
        self.assertTrue(preview["diff_truncated"])
        self.assertNotIn("merged_tree", preview)
        self.assertFalse((self.root / "huge.txt").exists())

    def test_quality_choice_can_keep_parent_take_child_or_select_by_file(self):
        child = self.fork()
        (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (child / "child-only.txt").write_text("new independent feature")
        tree = self.call("status")["tree"]
        comparison = self.call("compare_merge", expected_tree=tree)
        self.assertEqual("ready", comparison["state"])
        self.assertEqual(["code.txt"], comparison["comparison"]["overlap_paths"])
        self.assertIn("PARENT", comparison["parent_diff"])
        self.assertIn("CHILD", comparison["child_diff"])
        auto = self.call("preview_merge", expected_tree=tree)
        self.assertEqual("conflict", auto["state"])
        for mode, wanted in (("prefer_parent", "PARENT\nsecond\nthird\n"),
                             ("prefer_child", "CHILD\nsecond\nthird\n")):
            preview = self.call("preview_merge", expected_tree=tree, resolution=mode)
            self.assertEqual("ready", preview["state"])
            self.assertEqual(mode, preview["resolution"])
            result = self.call("merge", expected_tree=tree, resolution=mode,
                               expected_source_tree=preview["source_tree"],
                               expected_merged_tree=preview["merged_tree"])
            self.assertIn(result["state"], ("merged", "already_present"))
            self.assertEqual(wanted, (self.root / "code.txt").read_text())
            self.assertEqual("new independent feature", (self.root / "child-only.txt").read_text())
            # Restore the same parent candidate between alternate policy checks.
            (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        current = self.call("compare_merge", expected_tree=tree)["comparison"]
        choices = {path: "parent" for path in current["overlap_paths"]}
        chosen = self.call("preview_merge", expected_tree=tree, resolution="select",
                           path_choices=choices)
        self.assertEqual("ready", chosen["state"])
        self.assertEqual(chosen["merged_tree"], self.call("preview_merge",
            expected_tree=tree, resolution="prefer_parent")["merged_tree"])
        with self.assertRaisesRegex(ValueError, "select requires"):
            self.call("preview_merge", expected_tree=tree, resolution="select",
                      path_choices={"some-other-file": "child"})

    def test_merge_requires_full_review_receipt_and_comparison_criteria(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        expected = self.call("status")["tree"]
        with self.assertRaisesRegex(ValueError, "complete merge preview"):
            workspace.operate(self.root, self.store, "session", "merge",
                              dict(workspace_id=self.identity, expected_tree=expected))
        preview = self.call("preview_merge", expected_tree=expected)
        with self.assertRaisesRegex(ValueError, "quality decision"):
            workspace.operate(self.root, self.store, "session", "merge",
                              dict(workspace_id=self.identity, expected_tree=expected,
                                   expected_source_tree=preview["source_tree"],
                                   expected_merged_tree=preview["merged_tree"],
                                   quality_decision=dict(resolution="auto", reason="insufficient")))
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())

    def test_quality_decision_must_review_both_versions_when_both_changed(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        tree = self.call("status")["tree"]
        preview = self.call("preview_merge", expected_tree=tree, resolution="prefer_parent")
        args = dict(workspace_id=self.identity, expected_tree=tree, resolution="prefer_parent",
                    expected_source_tree=preview["source_tree"],
                    expected_merged_tree=preview["merged_tree"],
                    quality_decision={**self.quality_decision("prefer_parent"), "reviewed_both": False})
        with self.assertRaisesRegex(ValueError, "explicit comparison"):
            workspace.operate(self.root, self.store, "session", "merge", args)

    def test_select_can_drop_dependent_child_files_and_replace_parent_only_files(self):
        child = self.fork()
        (self.root / "code.txt").write_text("PARENT\nsecond\nthird\n")
        (self.root / "parent-only.txt").write_text("obsolete")
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (child / "child-only.txt").write_text("depends on CHILD")
        tree = self.call("status")["tree"]
        comparison = self.call("compare_merge", expected_tree=tree)["comparison"]
        self.assertEqual(["code.txt"], comparison["overlap_paths"])
        self.assertEqual(["child-only.txt"], comparison["child_only_paths"])
        self.assertEqual(["parent-only.txt"], comparison["parent_only_paths"])
        choices = {"code.txt": "parent", "child-only.txt": "parent",
                   "parent-only.txt": "child"}
        preview = self.call("preview_merge", expected_tree=tree, resolution="select",
                            path_choices=choices)
        self.assertEqual("ready", preview["state"])
        result = self.call("merge", expected_tree=tree, resolution="select",
                           path_choices=choices,
                           expected_source_tree=preview["source_tree"],
                           expected_merged_tree=preview["merged_tree"])
        self.assertEqual("merged", result["state"])
        self.assertEqual("PARENT\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertFalse((self.root / "child-only.txt").exists())
        self.assertFalse((self.root / "parent-only.txt").exists())

    def test_large_diff_requires_every_page_before_quality_merge(self):
        child = self.fork()
        (child / "large.txt").write_text("X" * 270000)
        tree = self.call("status")["tree"]
        first = self.call("preview_merge", expected_tree=tree)
        self.assertEqual("needs_full_review", first["state"])
        self.assertTrue(first["diff_truncated"])
        self.assertNotIn("merged_tree", first)
        last = self.call("preview_merge", expected_tree=tree,
                         review_offset=first["next_offset"])
        self.assertEqual("needs_full_review", last["state"])
        self.assertTrue(last["diff_truncated"])
        self.assertTrue(last["last_page"])
        self.assertIsNone(last["next_offset"])
        self.assertIn("merged_tree", last)
        with self.assertRaisesRegex(ValueError, "every exact diff page receipt"):
            workspace.operate(self.root, self.store, "session", "merge",
                              dict(workspace_id=self.identity, expected_tree=tree,
                                   expected_source_tree=first["source_tree"],
                                   expected_merged_tree=last["merged_tree"],
                                   resolution="auto", quality_decision=self.quality_decision(),
                                   review_receipts=[last["review_receipt"]]))
        merged = self.call("merge", expected_tree=tree,
                           expected_source_tree=first["source_tree"],
                           expected_merged_tree=last["merged_tree"],
                           review_receipts=[first["review_receipt"], last["review_receipt"]])
        self.assertEqual("merged", merged["state"])
        self.assertEqual("X" * 270000, (self.root / "large.txt").read_text())

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

    def test_lost_creation_receipt_is_listed_only_for_owner_and_can_be_discarded(self):
        original_save = workspace.save
        def stop_before_checkout(path, value):
            original_save(path, value)
            raise OSError("stopped before checkout")
        with patch.object(workspace, "save", stop_before_checkout):
            with self.assertRaisesRegex(OSError, "stopped before checkout"):
                self.fork()
        listed = self.call("list")["workspaces"]
        self.assertEqual(["creating"], [item["state"] for item in listed])
        foreign = workspace.operate(self.root, self.store, "foreign", "list", {})
        self.assertEqual([], foreign["workspaces"])
        self.assertEqual("discarded", self.call("discard", expected_tree=listed[0]["base_tree"])["state"])

    def test_ready_workspace_is_not_silently_recreated_if_tree_was_removed(self):
        child = self.fork()
        import shutil
        shutil.rmtree(child)
        with self.assertRaises(RuntimeError):
            self.fork()
        self.assertFalse(child.exists())

    def test_graph_merge_refuses_undeclared_files_before_any_source_write(self):
        child = self.fork()
        (child / "code.txt").write_text("node result")
        (child / "extra.txt").write_text("undeclared")
        reviewed = self.call("status")["tree"]
        with self.assertRaisesRegex(ValueError, "outside declared outputs"):
            self.call("merge", expected_tree=reviewed, allowed_paths=["code.txt"])
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertFalse((self.root / "extra.txt").exists())
        self.assertEqual("merged", self.call("merge", expected_tree=reviewed,
                                            allowed_paths=["code.txt", "extra.txt"])["state"])

    def test_graph_rename_cannot_move_a_declared_output_outside_its_scope(self):
        child = self.fork()
        (child / "code.txt").rename(child / "outside.txt")
        with self.assertRaisesRegex(ValueError, "outside declared outputs"):
            self.call("merge", expected_tree=self.call("status")["tree"], allowed_paths=["code.txt"])
        self.assertTrue((self.root / "code.txt").is_file())
        self.assertFalse((self.root / "outside.txt").exists())

    def test_next_node_forks_from_merged_dependency_and_retains_job_identity(self):
        first = self.call("fork", invocation_id="job-node-a")
        child = Path(first["root"])
        (child / "dependency.txt").write_text("producer output")
        self.call("merge", expected_tree=self.call("status")["tree"], allowed_paths=["dependency.txt"])
        self.identity = "b" * 32
        second = self.call("fork", invocation_id="job-node-b")
        self.assertEqual("producer output", (Path(second["root"]) / "dependency.txt").read_text())
        listed = self.call("list")["workspaces"]
        self.assertEqual({"job-node-a", "job-node-b"}, {item["invocation_id"] for item in listed})
        self.assertNotEqual(first["root"], second["root"])

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

    def test_partial_apply_failure_restores_only_the_merge_changes(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (child / "zz-added.bin").write_bytes(b"\x00\xffnew")
        reviewed = self.call("status")["tree"]
        original_replace = workspace.os.replace
        def failing_publish(source, target):
            if Path(target) == self.root / "zz-added.bin":
                (self.root / "external.txt").write_text("concurrent user")
                raise OSError("simulated partial filesystem failure")
            return original_replace(source, target)
        with patch.object(workspace.os, "replace", failing_publish):
            with self.assertRaisesRegex(OSError, "partial filesystem failure"):
                self.call("merge", expected_tree=reviewed)
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertEqual("concurrent user", (self.root / "external.txt").read_text())
        self.assertFalse((self.root / "zz-added.bin").exists())
        self.assertEqual("rolled_back", self.call("status")["integration"]["state"])

    def test_partial_apply_never_rolls_back_a_later_external_edit(self):
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        reviewed = self.call("status")["tree"]
        original_replace = workspace.os.replace
        def failing_publish(source, target):
            result = original_replace(source, target)
            if Path(target) == self.root / "code.txt":
                (self.root / "code.txt").write_text("new user revision")
                raise OSError("partial apply with external edit")
            return result
        with patch.object(workspace.os, "replace", failing_publish):
            with self.assertRaisesRegex(OSError, "partial apply"):
                self.call("merge", expected_tree=reviewed)
        self.assertEqual("new user revision", (self.root / "code.txt").read_text())
        status = self.call("status")["integration"]
        self.assertEqual("needs_inspection", status["state"])
        self.assertEqual(["code.txt"], status["unresolved_paths"])

    def test_sigkill_during_apply_is_recovered_without_replaying_merge(self):
        import json
        import select
        child = self.fork()
        (child / "code.txt").write_text("CHILD\nsecond\nthird\n")
        (child / "zz-added.bin").write_bytes(b"new")
        reviewed = self.call("status")["tree"]
        proposal = self.call("preview_merge", expected_tree=reviewed)
        reviewed_args = dict(workspace_id=self.identity, expected_tree=reviewed,
                             expected_source_tree=proposal["source_tree"],
                             expected_merged_tree=proposal["merged_tree"], resolution="auto",
                             quality_decision=self.quality_decision())
        code = f"""
import importlib.util,sys,signal
from pathlib import Path
spec=importlib.util.spec_from_file_location('workspace', {str(SOURCE)!r})
module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
original=module.os.replace
def interrupted(source,target):
    result=original(source,target)
    if Path(target) == Path({str(self.root)!r})/'code.txt':
        print('partial-write',flush=True)
        signal.pause()
    return result
module.os.replace=interrupted
module.operate({str(self.root)!r},{str(self.store)!r},'session','merge',{reviewed_args!r})
"""
        process = subprocess.Popen([sys.executable, '-B', '-c', code], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertTrue(select.select([process.stdout], [], [], 10)[0], "worker did not reach apply")
            self.assertEqual(b"partial-write\n", process.stdout.readline())
            process.kill(); process.wait(timeout=5)
        finally:
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
            process.stdout.close(); process.stderr.close()
        self.assertEqual("applying", self.call("status")["integration"]["state"])
        recovered = self.call("merge", expected_tree=reviewed)
        self.assertEqual("rolled_back", recovered["state"])
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertEqual("merged", self.call("merge", expected_tree=reviewed)["state"])

    def test_binary_rename_symlink_publish_failure_restores_original_files(self):
        child = self.fork()
        (child / "code.txt").unlink()
        (child / "renamed.bin").write_bytes(b"\x00\xffbinary")
        (child / "link").symlink_to("renamed.bin")
        reviewed = self.call("status")["tree"]
        original_replace = workspace.os.replace
        def no_space(source, target):
            if Path(target) == self.root / "renamed.bin":
                raise OSError("simulated ENOSPC during publication")
            return original_replace(source, target)
        with patch.object(workspace.os, "replace", no_space):
            with self.assertRaisesRegex(OSError, "ENOSPC"):
                self.call("merge", expected_tree=reviewed)
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())
        self.assertFalse((self.root / "renamed.bin").exists())
        self.assertFalse((self.root / "link").is_symlink())
        self.assertEqual("rolled_back", self.call("status")["integration"]["state"])

    def test_file_directory_replacement_rolls_back_without_removing_external_children(self):
        child = self.fork()
        (child / "code.txt").unlink()
        (child / "code.txt").mkdir()
        (child / "code.txt" / "nested").write_text("new")
        (child / "zz-new").write_text("new")
        original_replace = workspace.os.replace
        def no_space(source, target):
            if Path(target) == self.root / "zz-new":
                raise OSError("publish failed")
            return original_replace(source, target)
        with patch.object(workspace.os, "replace", no_space):
            with self.assertRaisesRegex(OSError, "publish failed"):
                self.call("merge", expected_tree=self.call("status")["tree"])
        self.assertTrue((self.root / "code.txt").is_file())
        self.assertEqual("first\nsecond\nthird\n", (self.root / "code.txt").read_text())

    def test_staging_failure_does_not_touch_parent_or_revert_external_changes(self):
        child = self.fork()
        (child / "code.txt").write_text("child")
        reviewed = self.call("status")["tree"]
        original_git = workspace.git
        def fail_private_stage(root, *args, **kwargs):
            if args[:2] == ("apply", "--binary"):
                self.assertNotEqual(self.root, Path(root))
                (self.root / "code.txt").write_text("external")
                raise OSError("private stage failed")
            return original_git(root, *args, **kwargs)
        with patch.object(workspace, "git", fail_private_stage):
            with self.assertRaisesRegex(OSError, "private stage failed"):
                self.call("merge", expected_tree=reviewed)
        self.assertEqual("external", (self.root / "code.txt").read_text())
        self.assertEqual("not_started", self.call("status")["integration"]["state"])

    def test_pending_integration_must_be_resolved_before_discard(self):
        child = self.fork()
        (child / "code.txt").write_text("child")
        reviewed = self.call("status")["tree"]
        original_replace = workspace.os.replace
        def external_edit(source, target):
            result = original_replace(source, target)
            if Path(target) == self.root / "code.txt":
                (self.root / "code.txt").write_text("external")
                raise OSError("uncertain publication")
            return result
        with patch.object(workspace.os, "replace", external_edit):
            with self.assertRaises(OSError):
                self.call("merge", expected_tree=reviewed)
        with self.assertRaisesRegex(ValueError, "pending integration"):
            self.call("discard", expected_tree=reviewed)
        self.assertTrue(child.is_dir())
        self.assertEqual("external", (self.root / "code.txt").read_text())


if __name__ == "__main__":
    unittest.main()
