# 神言神语 V6 · 手机端唯一设计源

**当前仅支持 Android 手机设计：393dp / 412dp，三主题：暖纸、烟熏玻璃、哑光黑。**

- [打开完整 38 场景原型](mobile/app.html)：从原 38 场景设计稿统一整理，移除 1024/1440dp 桌面三栏、平板及桌面专属入口。聊天为「会话／对话／人物档案」三视图，运行中心为「运行中／待处理／历史」单列切换。
- [26 枚现行功能矢量图标](sy-entry-icons.svg) 和 [入口规范](SY-ENTRY-ICONS.zh-CN.md)。源路径与真实 Compose `SyEntryIcons.kt` 一致。
- [文件矢量素材](assets/file-icons-preview.svg)、[手机端验收台账](PR664-VISUAL-ACCEPTANCE.zh-CN.md)。
- **设计素材 ZIP：**资料库 `/shenyan-v6-mobile-unified-20261013.zip`；包内包含 38 场景、26 枚独立 SVG、文件 SVG、Android Vector XML、V6 令牌、30 张本轮手机预览截图、哈希清单和 228 项浏览器矩阵检测结果。
- `sync-design-package.py` 为现行**纯手机素材再打包工具**：只接收已整理的 V6 手机源目录，拒绝旧版文件、大屏布局和字体二进制；不再从 V5/V4 目录继承历史素材。

**只有一个交付版本。** 删除重复的自主设计草稿目录，不再随包发布 V2/V3/V4/V5 预览、旧验证、过期 Kotlin 快照或过期 APK。此前技术审计文档可以在 GitHub 提交历史追溯，但不属于现行素材包。

本 HTML 是离线交互设计稿，示例数据不代表真实 App；Android 生产源码与行为仍以 [PR #664](https://github.com/sy220284/777/pull/664) 最新提交为准。38 场景的浏览器通过并不代表 Android 16/17 真机验收通过。
