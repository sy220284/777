# 777 固定界面字体的自动精简

- `source/NotoSansSC-wght.ttf`：Google Fonts Noto Sans SC 完整可变字体（Git blob `fb0637bafbcd804fe32152370a1225990745b4bc`）。
- `source/OFL.txt`：原始字体的 SIL OFL 1.1 授权。
- `build_ui_font.py`：扫描 `app/src/main/res/values*/` 和 UI Kotlin 固定中文字符串，输出五档真实字重 `ui_noto_sc_400..800.ttf`。
- `generate.sh`：Gradle 入口；自动安装锁定版本的 fontTools 4.59.2 到项目本地 `.gradle/ui-fonttools-python`，离线可复用。

完整母版只在 GitHub 仓库中参与构建；生成的精简字体进入 APK，禁止打包完整母版。缺失的固定中文字符会导致构建失败；少量特殊符号与 emoji 使用 Android 系统字体回退。由母版 SHA、实际用字和生成器版本确定精简结果，UI 源码修改但字符集合不变时不会重复生成。

固定菜单、按钮、页签、导航和设置用精简字体；聊天、Markdown、工作正文、人物日记、历史会话名称等动态文字用系统字体；代码保持原来的等宽字体。

**构建前提**：本机存在 Python 3、pip 和 Bash，首次准备字体构建依赖需要联网，之后缓存复用。不得把生成的中间资源提交到 Git。
