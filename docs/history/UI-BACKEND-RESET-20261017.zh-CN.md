# 历史归档：777 后端保留版与旧 UI 清空阶段

> **历史资料，已退出当前发布标志。** 原路径 `docs/UI-BACKEND-RESET.zh-CN.md` 曾用于阻止后端保留版产生空白 APK。2026-10-18，`PR #740` 已将朝夕 E8.17 的独立 UI Pack、Host 根及 75 场景接线合入 main。用户明确要求发布新版本，因此将旧“无界面”状态迁入历史档案；当前是否可发布，仍由真实 main push 全量 CI、Android 16/17、ARM64/x86、签名、APK 校验及发布工作流判断。

> 原始文件以下按提交时内容保存，用于追溯当时的移除边界与验收限制。文中“当前”“仍在 PR”等时态，均属于历史快照，不能当作 2026-10-18 以后的现状。

---

# 后端保留版：UI 清空及新界面接入

当前源代码不提供旧 V6 页面。PR #740 已由 `MainActivity` 安装朝夕 E8.17 独立界面，接线与验证状态以该 PR 最新 Head 为准，尚未代表主线交付。本文同时保留后端清空的边界约束与自动发布拦截标志；完成新界面验收前不解除发布拦截。

## 保留

- `:core`、Harness 系列、`app/local`、`app/automation`、`app/connection`、持久化与通知服务。模型请求、会话、记忆、工具、Work、执行和用户数据均由原业务 Owner 保管。
- `:ui-contract` 的各 Feature Port、命令、快照、回执与能力契约。
- `:ui-host` 的场景准入、导航状态、安全系统操作和系统权限入口。Host 不规定界面布局、风格、动画、字体或图标。
- App 内 Host/Feature 适配器及中立的 `LocalApplicationFeaturePortsOwner`：集中注入原 Chat/Work/Tools/Persona/Memory/Session 等真实 Owner 与业务端口，保留加密草稿、Scene Request 和原有用户数据，UI 不复制业务状态。
- 人物预设的角色内容素材属于产品数据，非旧 UI 皮肤，保留供新界面使用。

## 明确移除

- `:ui-pack-v6` 及其所有页面 Renderer / Shenyan 设计组件与视觉系统。
- App `ui/screens`、`ui/components`、`ui/theme`、`ui/shenyan`、侧边栏 UI 实现、字体、图标、旧动画与视觉回归测试。
- V6 设计源码/图标体系/视觉素材文档与旧截图采集工作流。
- 任意编译期对 V6 包的依赖，以及旧 UI 的生产装配。
- 旧页面文案资源（数千条页面标题、按钮与提示）；保留仅供系统通知、业务存储错误与 Android 契约测试使用的字符串。**Gradle 中 AndroidX Compose/Material/Icon/Navigation 的 SDK 依赖仍保留为经过校验的中立编译基座**，并非旧 UI 的页面、组件、素材或活动渲染器；新 Compose 入口只装配独立朝夕包。原安全校验的 Hilt/KSP 依赖图必须保持稳定，新 UI 可在其上接入。

## 新 UI 接入要求

新的界面从独立 `:ui-pack-new` 模块实现 `UiApplicationScenePack`，由 App 明确安装并通过 Host 的场景准入；它只观察原 Feature Port 状态并发送命令，不得创建平行业务状态或绕开 Host 授权。包内自由实现自己的视觉、字体、材质和动画，严禁复制旧 V6 工厂或使用旧兼容回退。目前没有第二套 UI，也不宣称通过了逐功能 UI 验收。

## 验证原则

后端基线仍必须通过架构 3.0、业务单元测试、Android 构建和运行时服务检查；旧 V6 专用的视觉/页面截图断言不再适用于无界面产物。新增的 `check-ui-module-boundaries.py` 明确禁止旧 UI 源码或依赖回来。

## 历史审计快照口径

旧版功能报告产生于 V6 代码仍存在时，文件中的 `ui-pack-v6` / `ui/screens` 路径仅是**历史证据**，不是现行源码或当前功能验收结果。当前 UI 实现以本分支实际 Git 树和新的负面边界门禁为准；重建新 UI 后需要重新生成全量功能审计基线。PR #740 已重新生成 `FEATURE-SOURCE-INDEX.auto.json`、`FEATURE-REVIEW.auto.json` 及功能树；未重新生成的其他审计报告仍属于历史证据。当前来源登记指向现存业务端口、宿主与朝夕源码，静态来源存在不代表动作通过验收。

## 禁止空白 APK 发版

历史记录：当时曾将 UI 后端重建与发布流程联动；当前项目行为以仓库实际配置和应用源码为准。


## 朝夕 E8.17 接入 PR #740

历史 UI 改造期间，`MainActivity` 通过 Hilt 组装业务端口，UI Host 管理导航和系统返回；旧静态界面路径已经退出使用。
