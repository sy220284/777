#!/usr/bin/env python3
"""只运行受改动影响的设备回归；通用构建与单元测试始终执行。"""
from fnmatch import fnmatchcase
import os
import re
import subprocess
import sys

FULL = (
    ".github/workflows/android-capability-contract.yml",
    ".github/workflows/runtime-user-journey.yml",
    ".github/workflows/extension-marketplace-check.yml",
    ".github/workflows/extension-marketplace-device-test.yml",
    ".github/workflows/session-storage-device-test.yml",
    "tools/ci/run_prebuilt_android_tests.py",
    "tools/ci/test_run_prebuilt_android_tests.py",
    "tools/ci/select_android_checks.py",
    "tools/ci/test_select_android_checks.py",
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle/**",
    ".github/actions/setup-android-cached/**",
    "core/**",
    "harness-core/**",
    "harness-interop/**",
    "harness-device-android/**",
    "harness-runtime-android/**",
    "reference-validation/**",
    "tools/runtime/**",
)

UI = (
    "app/src/main/java/com/labteto/dshmobile/notify/**",
    "app/src/androidTest/java/com/labteto/dshmobile/notify/**",
    "app/src/main/java/com/labteto/dshmobile/local/chat/**",
    "app/src/main/java/com/labteto/dshmobile/local/work/**",
    "app/src/main/java/com/labteto/dshmobile/local/model/**",
    "app/src/main/java/com/labteto/dshmobile/local/tools/**",
    "app/src/main/java/com/labteto/dshmobile/ui/**",
    "app/src/main/java/com/shenyan/designsystem/**",
    "app/src/main/res/**",
    "app/src/androidTest/java/com/labteto/dshmobile/ui/**",
    "app/src/androidTest/java/com/shenyan/designsystem/**",
    "tools/ui/**",
    "tools/fonts/**",
)

MARKET = (
    "app/src/main/java/com/labteto/dshmobile/ui/screens/tools/**",
    "app/src/main/java/com/labteto/dshmobile/local/tools/**",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/**",
    "app/src/main/java/com/labteto/dshmobile/uicontract/**",
    "app/src/test/java/com/labteto/dshmobile/local/tools/**",
    "app/src/test/java/com/labteto/dshmobile/ui/screens/tools/**",
    "app/src/androidTest/java/com/labteto/dshmobile/ui/screens/tools/**",
    "app/src/androidTest/java/com/labteto/dshmobile/local/tools/**",
    "tools/ui/**",
)

STORAGE = (
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalRequestMessageSurface.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalRequestEvidenceSurfaceMemo.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/**",
    "app/src/main/java/com/labteto/dshmobile/local/usage/**",
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/attachment/LocalPdfRenderer.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalMultimodal.kt",
    "app/src/androidTest/java/com/labteto/dshmobile/local/session/**",
    "app/src/androidTest/java/com/labteto/dshmobile/local/usage/**",
    "app/src/androidTest/java/com/labteto/dshmobile/local/attachment/**",
)


def matches(path, patterns):
    return any(fnmatchcase(path, pattern) for pattern in patterns)


def select_checks(paths, manual=False):
    if manual:
        return {"ui": True, "market": True, "migration": True}
    if not paths:
        raise ValueError("无法确定 PR 修改文件，拒绝跳过设备测试")
    selected = {"ui": False, "market": False, "migration": False}
    for path in paths:
        if matches(path, FULL):
            return dict.fromkeys(selected, True)
        affects_ui = matches(path, UI)
        affects_market = matches(path, MARKET)
        affects_storage = matches(path, STORAGE)
        if affects_ui:
            selected["ui"] = True
        if affects_market:
            selected["market"] = True
        if affects_storage:
            selected["migration"] = True
            selected["ui"] = True
        # 每个文件独立判断；同一 PR 的其他文件已触发市场测试时也不能漏掉 UI 回归。
        if (path.startswith("app/src/main/") or path.startswith("app/src/androidTest/")) and not (
            affects_ui or affects_market or affects_storage
        ):
            selected["ui"] = True
    return selected


def changed_files():
    if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
        return None
    base = os.environ.get("CI_BASE_SHA", "")
    head = os.environ.get("CI_HEAD_SHA", "")
    if not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("缺少有效 PR base/head 提交 SHA")
    output = subprocess.check_output(
        ["git", "diff", "--name-only", "--diff-filter=ACMRTD", "-z", f"{base}...{head}"]
    )
    return [file.decode("utf-8") for file in output.split(b"\0") if file]


def main():
    paths = changed_files()
    selected = select_checks(paths, manual=paths is None)
    for name, enabled in selected.items():
        print(f"{name}: {'执行' if enabled else '本次无相关改动'}", flush=True)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for name, enabled in selected.items():
            output.write(f"{name}={str(enabled).lower()}\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"设备回归范围判定失败：{exc}", file=sys.stderr)
        raise SystemExit(1)
