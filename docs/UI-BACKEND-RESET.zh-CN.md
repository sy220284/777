# 后端保留版：UI 清空及新界面接入

当前源代码**不提供旧聊天、Work、人物、工具或设置页面**。MainActivity 仅作为 Android 宿主入口，不安装 Compose 内容、视觉桥或任何旧 UI。此状态用于重新接入全新 UI；用户无法通过旧页面操作业务，这是有意设计的过渡态。

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
- 旧页面文案资源（数千条页面标题、按钮与提示）和 App 侧旧 Material/Navigation/图标库；保留仅供系统通知、业务存储错误与 Android 契约测试使用的字符串。

## 新 UI 接入要求

新的界面从独立 `:ui-pack-new` 模块实现 `UiApplicationScenePack`，由 App 明确安装并通过 Host 的场景准入；它只观察原 Feature Port 状态并发送命令，不得创建平行业务状态或绕开 Host 授权。包内自由实现自己的视觉、字体、材质和动画，严禁复制旧 V6 工厂或使用旧兼容回退。真正接入新界面时新增 Compose 入口并独立进行完整行为与设备回归；目前没有第二套 UI，也不宣称通过了逐功能 UI 验收。

## 验证原则

后端基线仍必须通过架构 3.0、业务单元测试、Android 构建和运行时服务检查；旧 V6 专用的视觉/页面截图断言不再适用于无界面产物。新增的 `check-ui-module-boundaries.py` 明确禁止旧 UI 源码或依赖回来。

## 历史审计快照口径

`docs/FEATURE-SOURCE-INDEX.auto.json`、`FEATURE-REVIEW.auto.json`、`FEATURE-REALITY-AUDIT.auto.json` 等报告产生于旧 UI 代码仍存在时，文件中的 `ui-pack-v6` / `ui/screens` 路径仅是**历史证据**，不是现行源码或当前功能验收结果。当前 UI 实现以本分支实际 Git 树和新的负面边界门禁为准；重建新 UI 后需要重新生成全量功能审计基线，不能复用这些旧截图、页面或证据声明。现行功能来源登记已改指向真实业务端口或 Provider 解析代码。

## 禁止空白 APK 发版

本阶段属于无界面后端基线，即使 CI 构建、Android 设备测试全部通过，也不能对终端用户发布空白客户端。`.github/workflows/release.yml` 在发布检测阶段识别本权威文档作为无界面标志并直接跳过发版；`.github/scripts/check-ci-repository-integrity.py` 强制验证拦截存在。**未来完整接入并验收新 UI 后**，在移除无界面标志和发布拦截时必须重新完成全量主线 CI 与发布证据检查。
