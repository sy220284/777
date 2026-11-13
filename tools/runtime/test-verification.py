"""验证真实签名、固定签名者以及离线脚本的下载边界。"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
FINGERPRINT = "CC72CF8BA7DBFA0182877D045A897D96E57CF20C"


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)

    def verify(self, index, fingerprint=FINGERPRINT):
        return subprocess.run(
            ["bash", "-c", 'source "$1"; verify_termux_signature "$2" "$3" "$4" "$5"',
             "test", str(ROOT / "verify-termux-signature.sh"), str(index),
             str(ROOT / "testdata/termux/keyring.gpg"), fingerprint, str(self.home)],
            capture_output=True, text=True, check=False,
        )

    def test_valid_signature_without_agent(self):
        result = self.verify(ROOT / "testdata/termux/InRelease")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(self.home.glob("S.gpg-agent*")))

    def test_unexpected_signer(self):
        result = self.verify(ROOT / "testdata/termux/InRelease", "0" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("签名者公钥指纹不匹配", result.stderr)

    def test_tampered_signed_content(self):
        index = self.home / "InRelease"
        index.write_bytes((ROOT / "testdata/termux/InRelease").read_bytes().replace(
            b"Origin: termux-main stable", b"Origin: untrusted-repository"
        ))
        result = self.verify(index)
        self.assertNotEqual(result.returncode, 0)

    def test_offline_never_invokes_curl(self):
        executable = self.home / "curl"
        executable.write_text('#!/bin/bash\ntouch "$DOWNLOAD_MARKER"\n')
        executable.chmod(0o755)
        marker = self.home / "downloaded"
        env = dict(os.environ, DSH_RUNTIME_OFFLINE="true", DOWNLOAD_MARKER=str(marker))
        env["PATH"] = str(self.home) + os.pathsep + env["PATH"]
        result = subprocess.run(
            ["bash", "-c", 'source "$1"; runtime_download --fail https://example.invalid',
             "test", str(ROOT / "runtime-download.sh")],
            env=env, capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())
        self.assertIn("缓存缺失或校验失败", result.stderr)


if __name__ == "__main__":
    unittest.main()
