#!/usr/bin/env python3
"""在模拟器上直接运行已校验的测试 APK，无需在设备任务中重新编译。"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APK_ROOT = ROOT / "app/build/outputs/apk"
TEST_PACKAGE = "com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner"


def verify_apks(directory=APK_ROOT):
    manifest = directory / "SHA256SUMS"
    expected_paths = {
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    }
    expected = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([a-fA-F0-9]{64})  (app/build/outputs/apk/\S+)", line)
        if not match or match.group(2) in expected:
            raise ValueError(f"APK 摘要清单含无效或重复条目：{line!r}")
        expected[match.group(2)] = match.group(1).lower()
    if set(expected) != expected_paths:
        raise ValueError(f"APK 清单与测试包契约不一致：{sorted(expected)}")
    # Android 仪表测试包依赖被测应用，必须先安装应用再安装测试包。
    install_order = [
        "app/build/outputs/apk/debug/app-debug.apk",
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]
    verified = []
    for relative in install_order:
        file = ROOT / relative
        with file.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != expected[relative]:
            raise ValueError(f"APK SHA-256 校验失败：{relative}")
        verified.append(file)
    return verified


def parse_results(output, requested):
    if "INSTRUMENTATION_CODE: -1" not in output:
        raise ValueError("Android 仪表测试未正常结束")
    if "FAILURES!!!" in output or "INSTRUMENTATION_ABORTED" in output:
        raise ValueError("Android 仪表测试报告失败或中止")
    match = re.search(r"\bOK \((\d+) tests?\)", output)
    if not match or int(match.group(1)) < 1:
        raise ValueError("Android 测试没有报告非零通过用例")
    fields = {}
    results = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if sep:
                fields[field] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            if code in (0, -1, -2) and "class" in fields and "test" in fields:
                results.append((fields["class"], fields["test"], code, fields.get("stack", "")))
            fields = {}
    if len(results) != int(match.group(1)):
        raise ValueError(f"用例数量与仪表测试报告不一致：{len(results)} / {match.group(1)}")
    missing = sorted(set(requested) - {item[0] for item in results})
    if missing:
        raise ValueError("以下测试类未执行：" + ", ".join(missing))
    errors = [f"{cls}.{test}" for cls, test, code, _ in results if code != 0]
    if errors:
        raise ValueError("Android 测试断言失败：" + ", ".join(errors))
    return results


def save_results(folder, suite, output, cases=None, error=None):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{suite}.log").write_text(output, encoding="utf-8")
    cases = cases or []
    group = ET.Element("testsuite", {
        "name": suite, "tests": str(len(cases) if error is None else len(cases) + 1),
        "failures": str(sum(code != 0 for _, _, code, _ in cases) + int(error is not None)),
    })
    for cls, test, code, stack in cases:
        case = ET.SubElement(group, "testcase", {"classname": cls, "name": test})
        if code != 0:
            ET.SubElement(case, "failure", {"message": "设备断言失败"}).text = stack
    if error is not None:
        case = ET.SubElement(group, "testcase", {"classname": suite, "name": "完整性检查"})
        ET.SubElement(case, "failure", {"message": str(error)}).text = output[-10000:]
    ET.ElementTree(group).write(folder / f"TEST-{suite}.xml", encoding="utf-8", xml_declaration=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, choices=["extension-market", "session-storage"])
    parser.add_argument("--classes", required=True, help="逗号分隔的 Android 测试类全名")
    args = parser.parse_args(argv)
    requested = args.classes.split(",")
    if not requested or any(not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", cls) for cls in requested):
        parser.error("--classes 必须提供完整类名，逗号之间不允许空条目")
    if len(requested) != len(set(requested)):
        parser.error("测试类重复")
    report = ROOT / "app/build/outputs/androidTest-results/connected"
    output = ""
    try:
        verified = verify_apks()
        for apk in verified:
            result = subprocess.run(["adb", "install", "--no-streaming", "-r", str(apk)], text=True, capture_output=True, check=True, timeout=180)
            print(result.stdout, flush=True)
        result = subprocess.run([
            "adb", "shell", "-n", "am", "instrument", "-w", "-r", "-e", "class",
            ",".join(requested), TEST_PACKAGE,
        ], text=True, capture_output=True, timeout=3300)
        output = result.stdout + "\n" + result.stderr
        print(output, flush=True)
        if result.returncode:
            raise RuntimeError(f"adb 仪表测试退出码：{result.returncode}")
        cases = parse_results(output, requested)
        save_results(report, args.suite, output, cases)
        print(f"{args.suite}：{len(cases)} 项真实 Android 测试通过，APK 未重新构建", flush=True)
        return 0
    except (OSError, ValueError, subprocess.SubprocessError, RuntimeError) as exc:
        save_results(report, args.suite, output, error=exc)
        print(f"{args.suite} 回归失败：{exc}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
