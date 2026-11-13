"""使用小型组件产物验证真实安装入口的失败传播和按需复用。"""
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dev = self.root / "dev"
        shutil.copytree(Path(__file__).resolve().parent, self.dev)
        # 隔离宿主的现有 JDK，测试组件安装而非宿主环境。
        with (self.dev / "lib/common.sh").open("a") as file:
            file.write('\nfind_compatible_java_home() { [ -x "$TOOLS_ROOT/jdk/bin/javac" ] && printf "%s\\n" "$TOOLS_ROOT/jdk"; }\n')
        (self.dev / "setup-toolchain.sh").write_text("#!/bin/bash\nexit 0\n")
        with (self.dev / "toolchain-components.env").open("a") as file:
            file.write("\nBUILD_COMPONENTS=jdk\n")
        self.artifact = self.root / "artifacts/777-toolchain-jdk-latest"
        self.artifact.mkdir(parents=True)
        versions = (self.dev / "toolchain-versions.env").read_text()
        (self.artifact / "component.env").write_text(
            "ARTIFACT_SCHEMA=4\nARTIFACT_COMPONENT=jdk\nARTIFACT_ARCH=x64\n"
            "ARTIFACT_PART=1\nARTIFACT_PARTS=1\nARTIFACT_HEAD_SHA=test\n" + versions
        )
        self.env = dict(os.environ)
        self.env.pop("TAR_OPTIONS", None)
        for name, value in {
            "TOOLS_ROOT": "tools", "ANDROID_SDK_ROOT": "sdk",
            "GRADLE_HOME": "gradle", "GRADLE_USER_HOME": "gradle-cache",
            "TOOLCHAIN_ENV_FILE": "env", "REPO_ROOT": "repo",
        }.items():
            self.env["DEV777_" + name] = str(self.root / value)
        self.payload()

    def payload(self, malformed=False):
        with tarfile.open(self.artifact / "payload.tar.gz", "w:gz") as archive:
            for name in (["unexpected"] if malformed else ["jdk/bin/java", "jdk/bin/javac"]):
                data = b'#!/bin/bash\necho \'openjdk version "27"\' >&2\n'
                entry = tarfile.TarInfo(name)
                entry.mode, entry.uid, entry.gid, entry.size = 0o755, 1001, 1001, len(data)
                archive.addfile(entry, io.BytesIO(data))
        self.checksums()

    def checksums(self):
        (self.artifact / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256((self.artifact / name).read_bytes()).hexdigest()}  {name}\n"
            for name in ["component.env", "payload.tar.gz"]
        ))

    def run_install(self):
        return subprocess.run(
            ["bash", str(self.dev / "install.sh"), "install", "build", "--artifacts-dir", str(self.root / "artifacts")],
            env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            check=False,
        )

    def assert_failed(self):
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertNotIn("已安装组件：jdk", result.stdout)
        self.assertNotIn("工具链按需安装完成", result.stdout)

    def test_install_and_reuse(self):
        result = self.run_install()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertTrue((self.root / "tools/jdk/bin/javac").is_file())
        self.assertNotIn("Cannot change ownership", result.stdout)
        # 移除产物后再次安装，证明无需重复读取产物。
        shutil.rmtree(self.artifact)
        result = self.run_install()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("无需下载或安装组件", result.stdout)

    def test_checksum_failure(self):
        with (self.artifact / "payload.tar.gz").open("ab") as file:
            file.write(b"damaged")
        self.assert_failed()

    def test_invalid_archive(self):
        (self.artifact / "payload.tar.gz").write_bytes(b"invalid gzip")
        self.checksums()
        self.assert_failed()

    def test_missing_component_directory(self):
        self.payload(malformed=True)
        self.assert_failed()

    def command_failure(self, command):
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        executable = bin_dir / command
        executable.write_text("#!/bin/bash\nexit 73\n")
        executable.chmod(0o755)
        self.env["PATH"] = str(bin_dir) + os.pathsep + self.env["PATH"]
        self.assert_failed()

    def test_extraction_failure(self):
        self.command_failure("tar")

    def test_copy_failure(self):
        self.command_failure("cp")


if __name__ == "__main__":
    unittest.main()
