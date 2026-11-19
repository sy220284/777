#!/usr/bin/env python3
"""Regression checks for release descriptions extracted from PRs."""
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("generate-release-notes.py")
spec = importlib.util.spec_from_file_location("release_notes", SCRIPT)
notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notes)


class ReleaseNotesTest(unittest.TestCase):
    def test_pr_body_selects_changes_before_validation(self):
        body = """## 背景
过往问题。
## 改动
- 主提示词明确审查、规划和实施的交付边界，处理多项要求、中途纠正和收尾核验。
- 优化运行时说明。
## 验证
- CI 全部通过。"""
        self.assertEqual(
            notes.summary(body, "提示词优化"),
            "主提示词明确审查、规划和实施的交付边界，处理多项要求、中途纠正和收尾核验。",
        )

    def test_explicit_release_note_has_priority(self):
        body = """## 发布说明
- 增加快捷操作入口，减少重复点击。
## 改动内容
- 修改类名和代码结构。"""
        self.assertEqual(notes.summary(body, "更新"), "增加快捷操作入口，减少重复点击。")

    def test_summary_ignores_validation_and_shrinks_long_detail(self):
        self.assertEqual(notes.summary("## 验证\n- CI passed", "改善操作"), "")
        self.assertLessEqual(len(notes.shorten("长" * 100)), 85)

    def test_generate_filters_docs_and_deduplicates_pr(self):
        sha_a, sha_b, sha_c = ("a" * 40, "b" * 40, "c" * 40)
        pr = {
            "number": 12,
            "title": "优化工作流程",
            "body": "## 改动\n- 现在可以按执行步骤查看处理进度。",
            "merged_at": "2026-11-19T00:00:00Z",
            "base": {"ref": "main"},
        }
        def fake_run(*args):
            if args[:2] in (("git", "rev-parse"), ("git", "merge-base")):
                return ""
            if args[:2] == ("git", "rev-list"):
                return "\n".join([sha_a, sha_b, sha_c])
            if args[:2] == ("git", "diff"):
                return "docs/a.md" if args[-1] == sha_a else "app/a.kt"
            if args[:2] == ("gh", "api"):
                return __import__("json").dumps([pr])
            raise AssertionError(args)
        with patch.object(notes, "run", side_effect=fake_run):
            out = notes.generate("owner/777", "v1", "HEAD")
        self.assertEqual(
            out,
            "## 本次更新\n\n- 优化工作流程：现在可以按执行步骤查看处理进度。\n",
        )

    def test_prioritize_user_change_over_packaging_and_complete_sentence(self):
        body = """## 完成内容
- 完整打包四套技能的 125 个原始文件，包含图像和脚本。
- 使用现有安装与管理链路，首次安装及已有用户升级各自动执行一次预置注册；卸载后不重复自动恢复。
## 验证
- 测试全部通过。"""
        self.assertEqual(
            notes.summary(body, "内置四套技能"),
            "首次安装及已有用户升级各自动执行一次预置注册；卸载后不重复自动恢复。",
        )
        self.assertFalse(notes.shorten("包含许多逗号，" * 20).endswith("，"))

    def test_direct_commit_without_pr(self):
        def fake_run(*args):
            if args[:2] in (("git", "rev-parse"), ("git", "merge-base")):
                return ""
            if args[:2] == ("git", "rev-list"):
                return "d" * 40
            if args[:2] == ("git", "diff"):
                return "app/main.kt"
            if args[:2] == ("gh", "api"):
                return "[]"
            if args[:2] == ("git", "log"):
                return "fix: 修复任务列表刷新丢失"
            raise AssertionError(args)
        with patch.object(notes, "run", side_effect=fake_run):
            out = notes.generate("owner/777", "v1", "HEAD")
        self.assertIn("- 修复任务列表刷新丢失", out)

    def test_fail_on_missing_notes_for_client_change(self):
        def fake_run(*args):
            if args[:2] in (("git", "rev-parse"), ("git", "merge-base")):
                return ""
            if args[:2] == ("git", "rev-list"):
                return "d" * 40
            if args[:2] == ("git", "diff"):
                return "app/main.kt"
            if args[:2] == ("gh", "api"):
                return "[]"
            if args[:2] == ("git", "log"):
                return "[skip ci]"
            raise AssertionError(args)
        with patch.object(notes, "run", side_effect=fake_run):
            with self.assertRaisesRegex(RuntimeError, "no usable release notes"):
                notes.generate("owner/777", "v1", "HEAD")


if __name__ == "__main__":
    unittest.main()
