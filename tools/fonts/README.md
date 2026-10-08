# 777 固定界面字体的自动精简

- `source/NotoSansSC-wght.ttf`：Google Fonts Noto Sans SC 完整可变字体（Git blob `fb0637bafbcd804fe32152370a1225990745b4bc`）。
- `source/OFL.txt`：原始字体的 SIL OFL 1.1 授权。
- `build_ui_font.py`：扫描 `app/src/main/res/values*/` 和 UI Kotlin 固定中文字符串，输出五档真实字重 `ui_noto_sc_400..800.ttf`。
- `generate.sh`：Gradle 入口；仅 GitHub Actions 在线预热阶段可安装锁定版本 fontTools 4.59.2 到 `.gradle/runtime-cache/ui-fonttools-python`，由 GitHub Actions `runtime-cache` Artifact 一并分发；本地只能读取已安装 Artifact，缺失时直接报错，不允许联网补齐。

完整母版只在 GitHub 仓库中参与构建；生成的精简字体进入 APK，禁止打包完整母版。缺失的固定中文字符会导致构建失败；少量特殊符号与 emoji 使用 Android 系统字体回退。由母版 SHA、实际用字和生成器版本确定精简结果，UI 源码修改但字符集合不变时不会重复生成。

固定菜单、按钮、页签、导航和设置用精简字体；聊天、Markdown、工作正文、人物日记、历史会话名称等动态文字用系统字体；代码保持原来的等宽字体。

**构建前提**：本机有 Python 3 和 Bash；首先从 main 成功的 GitHub Actions 下载最新版 `777-toolchain-runtime-cache-latest` 并按工具链说明安装。常规 Gradle `--offline` 构建只使用已校验缓存；即使运行在 GitHub Actions 中，只要带 `--offline` 也禁止字体依赖下载。只有 GitHub Actions 在线预热任务允许首次下载 fontTools，之后随 Runtime Artifact 分发。不得把生成的中间资源提交到 Git。
