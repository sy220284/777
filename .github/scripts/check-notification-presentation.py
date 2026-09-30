#!/usr/bin/env python3
"""Guard notification identity against duplicated or stale icon presentation."""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
KOTLIN = APP / "java"
MANIFEST = APP / "AndroidManifest.xml"
SMALL_ICON = "R.drawable.ic_notification_butterfly"

violations: list[str] = []

builder_files = []
for path in KOTLIN.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if "NotificationCompat.Builder" not in text:
        continue
    builder_files.append(path)
    if ".setLargeIcon(" in text:
        violations.append(f"{path.relative_to(ROOT)}: 通知不得再设置 LargeIcon，避免系统模板右侧重复应用头像")
    if SMALL_ICON not in text:
        violations.append(f"{path.relative_to(ROOT)}: 通知必须使用统一的单色 small icon")

manifest = MANIFEST.read_text(encoding="utf-8")
if 'android:icon="@mipmap/ic_launcher_777_v2"' not in manifest:
    violations.append("AndroidManifest.xml: 必须使用新版 launcher 资源 ID")
if 'android:roundIcon="@mipmap/ic_launcher_777_v2_round"' not in manifest:
    violations.append("AndroidManifest.xml: 必须同时声明新版 roundIcon")

for stale in (
    APP / "res/mipmap-anydpi-v26/ic_launcher.xml",
    APP / "res/mipmap-anydpi-v26/ic_launcher_round.xml",
    APP / "res/drawable-nodpi/notification_portrait.webp",
    KOTLIN / "com/labteto/dshmobile/notify/NotificationArtwork.kt",
):
    if stale.exists():
        violations.append(f"{stale.relative_to(ROOT)}: 旧通知/启动器图标实现必须移除")

if not builder_files:
    violations.append("未找到 NotificationCompat.Builder，通知图标门禁失去覆盖对象")

if violations:
    print("Notification presentation guard violations:")
    print("\n".join(f"  - {item}" for item in violations))
    sys.exit(1)

print(f"Notification presentation guard passed ({len(builder_files)} builder files)")
