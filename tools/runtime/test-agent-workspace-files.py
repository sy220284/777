#!/usr/bin/env python3
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[2] / 'harness-runtime-android/src/main/resources/com/labteto/dshmobile/runtime/agent_workspace_files.py'
spec = importlib.util.spec_from_file_location('workspace_files', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WorkspaceFilesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.external = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self.previous)
        self.directory.cleanup()
        self.external.cleanup()

    def call(self, operation, path='script.sh', **values):
        return module.operate(dict(operation=operation, path=path, original_path=path, **values))

    def test_read_edit_preserves_mode_and_requires_current_observation(self):
        self.call('write', content='old')
        Path('script.sh').chmod(0o751)
        observed = self.call('read')['fileVersions']['script.sh']
        self.call('edit', observed=observed, old_text='old', new_text='new')
        self.assertEqual(Path('script.sh').read_text(), 'new')
        self.assertEqual(Path('script.sh').stat().st_mode & 0o777, 0o751)
        with self.assertRaises(ValueError):
            self.call('edit', observed=observed, old_text='new', new_text='stale')
        self.assertEqual(Path('script.sh').read_text(), 'new')

    def test_parent_replacement_cannot_redirect_write_outside_workspace(self):
        Path('nested').mkdir()
        outside = Path(self.external.name)
        original_open = os.open
        def replace_parent(name, *args, **kwargs):
            descriptor = original_open(name, *args, **kwargs)
            if name == 'nested':
                Path('nested').rename('moved')
                Path('nested').symlink_to(outside, target_is_directory=True)
            return descriptor
        with patch.object(module.os, 'open', side_effect=replace_parent):
            self.call('write', path='nested/result', content='safe')
        self.assertEqual(Path('moved/result').read_text(), 'safe')
        self.assertFalse((outside / 'result').exists())

    def test_symlink_and_traversal_are_not_followed(self):
        Path('protected').write_text('original')
        Path('link').symlink_to(self.root / 'protected')
        for path in ('link', '../escape', '/absolute'):
            with self.assertRaises((ValueError, OSError)):
                self.call('write', path=path, content='bad')
        self.assertEqual(Path('protected').read_text(), 'original')


if __name__ == '__main__':
    unittest.main()
