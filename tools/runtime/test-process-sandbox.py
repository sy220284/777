#!/usr/bin/env python3
"""Execute the production helper against real kernel boundaries; no mocked authorization."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[2] / 'harness-runtime-android/src/main/resources/com/labteto/dshmobile/runtime/process_sandbox.py'


class ProcessSandboxTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / 'workspace'
        self.workspace.mkdir()
        self.secret = self.root / 'secret'
        self.secret.write_text('private')
        self.policy = {'read': ['/usr', '/bin', '/lib', '/lib64', '/etc/ld.so.cache', '/dev/null', '/dev/urandom'],
                       'write': [str(self.workspace)]}

    def run_child(self, code, **kwargs):
        return subprocess.run([sys.executable, '-I', str(HELPER), json.dumps(self.policy),
                               json.dumps([sys.executable, '-I', '-c', code])],
                              text=True, capture_output=True, timeout=10, **kwargs)

    def test_workspace_exec_create_write_truncate_and_descendant(self):
        result = self.run_child("import pathlib,subprocess,sys; p=pathlib.Path('allowed'); p.write_text('before'); "
                                "p.write_text('after'); assert p.read_text()=='after'; "
                                "subprocess.run([sys.executable,'-c',\"open('child','w').write('ok')\"],check=True)")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('after', (self.workspace / 'allowed').read_text())
        self.assertEqual('ok', (self.workspace / 'child').read_text())

    def test_parent_read_write_truncate_and_symlink_are_denied(self):
        (self.workspace / 'escape').symlink_to(self.secret)
        source = f"""
import os
for path in [{str(self.secret)!r}, 'escape']:
    for flags in [os.O_RDONLY, os.O_WRONLY, os.O_WRONLY|os.O_TRUNC]:
        try:
            descriptor=os.open(path,flags)
        except PermissionError:
            continue
        else:
            os.close(descriptor)
            raise AssertionError((path,flags))
"""
        result = self.run_child(source)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('private', self.secret.read_text())

    def test_network_and_cross_process_access_are_denied(self):
        result = self.run_child("""
import ctypes,os,socket
for operation in [lambda: socket.socket(), lambda: socket.socket(socket.AF_UNIX), lambda: os.kill(os.getppid(),0)]:
    try: operation()
    except PermissionError: pass
    else: raise AssertionError('sandbox escape')
""")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_hardlink_and_rename_cannot_import_parent_file(self):
        result = self.run_child(f"""
import os
for operation in [lambda: os.link({str(self.secret)!r},'imported'), lambda: os.rename({str(self.secret)!r},'moved')]:
    try: operation()
    except OSError: pass
    else: raise AssertionError('escaped')
""")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('private', self.secret.read_text())
        self.assertFalse((self.workspace / 'imported').exists())

    def test_inherited_private_descriptor_is_closed_before_exec(self):
        with self.secret.open() as secret:
            result = self.run_child(f"""
import os
try: os.read({secret.fileno()},100)
except OSError: pass
else: raise AssertionError('inherited private fd')
""", pass_fds=(secret.fileno(),))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_permission_never_falls_back_to_unsandboxed_command(self):
        self.policy['read'] = []
        result = self.run_child("open('should-not-run','w').write('unsafe')")
        self.assertEqual(125, result.returncode)
        self.assertIn('PROCESS_ISOLATION_UNAVAILABLE', result.stderr)
        self.assertFalse((self.workspace / 'should-not-run').exists())


if __name__ == '__main__':
    unittest.main()
