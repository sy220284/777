# UI 3.0：全新 UI 包接入契约

> 状态：第一、二步源码实施方案。第二套完整 UI 的行为验收不属于本阶段。

## 唯一接入路径

1. 新建独立 Android Library `:ui-pack-<id>`（不得依赖 `:app` 或 `:ui-pack-v6`），仅依赖 `:ui-host`、`:ui-contract` 和 Compose 等平台库。
2. 实现 `UiApplicationScenePack`：每一个非 Host 的必需 `UiSceneId` 都必须有真正可执行的 `UiSceneRenderer`；`UiPackManifest` 包含真实版本、能力、窗口、深色和无障碍声明。只登记场景不代表动作已验收。
3. App 的 Feature Owner 通过现有 `*UiPort` 构造包，保留唯一数据源。UI 包只能发起命令、观察状态与回执，不得自行复制 Session、Work、记忆、模型、文件、系统权限或认证。
4. 仅在 App 生产装配处将新包加入 `installedPacks`，提供 `defaultPackId` 与确有必要的 `legacyPackIds`；Host 使用 `UiApplicationPackInstallation` 验证身份唯一性、默认包与历史映射。原包不需要增加对新包的引用。
5. 皮肤在本包的 `SceneFrame` 内实现：主题、动态壁纸、字体、材质、动画、图标及视觉资源归本包，Host 不统一注入任何 V6 样式。通用用户偏好通过 `HostPreferencesUiPort` 的不可变快照读取。
6. 由 Host 进行场景准入、导航、恢复、文件/媒体/权限系统操作。缺 Scene、缺能力、未知包、错误恢复均显式拒绝，不可偷用 V6 或伪造成功。

## 发布前硬性验证

- 各包独立编译、模块依赖门禁、场景清单与 Renderer 一一对应；对全部必需场景的真实 Feature Owner 组合链路运行测试。
- 逐动作核对实际业务回执（不拿 Manifest 覆盖率充当用户动作通过率），确保会话、Work、人物、工具、模型、设置、媒体及动态文档内容与业务事件来源保持一致。
- 验证导航前后栈、深链、输入草稿、切换会话、进程冷恢复、权限撤回、执行中与用户审批状态；背景和字体倍率、系统缩减动画、窄屏及无障碍实机验收另行签收。
- 仅在新包功能、行为及回归达标后移除旧包及其安装配置。删除 V6 **不是**本阶段动作；不要先删除回退能力再验新包。

## 已实施代码

- 通用准入：`ui-host/.../UiApplicationScenePack.kt`、`UiApplicationPackInstallation.kt`。
- 真实场景目录：`app/.../LocalUiSceneCatalog.kt`；生产装配：`app/.../LocalProductionUiEntry.kt`。
- V6 私有外观：`ui-pack-v6/src/main/kotlin/com/labteto/dshmobile/ui/theme/` 与 `V6VisualFrame.kt`，字体资源：`ui-pack-v6/src/main/res/font/`。
- 通用外观参数：`UiHostPreferences`、`UiReadingLimits`，不要求新包遵守 V6 色板或动效样式。

**边界**：现阶段仅有完整 V6 包。双包生产切换、173 个动作逐项签收、设备视觉与无障碍验收属于后续步骤，不在本阶段冒充完成。
