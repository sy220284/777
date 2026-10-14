# R4 UI Host / Pack 交付对照与本轮变更追踪（2026-10-14）

> 依据：`docs/design/UI-3.0.zh-CN.md`、`docs/design/ui3/PLAN-R4.zh-CN.md` 及当前 PR #705。**此文不代表在线 Figma 文件已同步，不代表整包替换已经通过。**

## 修改归属（不得混算）

| 来源 | 实际已具备 | 本轮处理 |
|---|---|---|
| 本轮之前 | `:ui-contract` 场景身份 / `UiPackManifest.checkCompatibility`；Next 会话切片；V6 基础组件移包；Session、Work、Files 等 Port；本地 `UiDocument` Builder | 不改写既有功能的完成归属 |
| **本轮新增** | 过去尚无独立 Pack 注册/选择 API | 新建 `ui-host/UiPackRegistry.kt`：精确 ID 注册、重复 ID 拒绝、缺失包拒绝、逐项场景/能力/契约/窗口/无障碍兼容校验，不隐式回落 V6 |
| **本轮新增** | 原有检查集中于 `UiPackManifest` 单项验证 | 新增 `UiPackRegistryTest`：不存在的包、整包缺场景被拒绝、受限切片可被单独接受、重复 ID 拒绝 |
| **本轮新增** | 在线设计同步证据未获得 | 新建本设计对照文档，明确 Host 负责业务边界、Pack 负责风格；无 Figma 画板编辑证明 |

## UI 设计 / 代码对照（Figma 接入的约束）

- **UI Host（中立）**：注册、选择、兼容诊断、返回/IME/权限/恢复等系统事务；不使用 V6 的颜色/圆角/材质作跨包要求。
- **Pack V6（独立视觉）**：现有 `Sy*` 组件的材质、色彩、动效和排版只属于 V6；当前只是基础组件移出 App，整套页面尚未拆出。
- **Pack Next（独立视觉）**：独立 Material 会话切片；目前不能冒充完整应用包；未覆盖的 Scene 必须显式拒绝。
- **动态 UiDocument（中立结构）**：两个 Pack 均用各自原生控件绘制同一结构。真实数据/命令仍交由原 Feature Owner。
- **在线 Figma 状态**：当前未获得具体设计文件 key/节点与编辑权限证明，此文只同步仓库规范，不宣称线上变量、组件实例、画板已更改。

## 未完成（不可标记完成）

1. **S1**：人物/图库/群聊、Work 团队及证据写入等完整 Feature Ports，旧回调退场。
2. **S2**：真实双包发送/审批/崩溃或系统重建恢复的端到端验收。
3. **S3**：将完整 V6 页面移入独立 Pack；Next 全应用场景，Host 全局导航注册及实际运行时选择器。**本轮注册/选择 API 是底座切片，不是已经接入生产全包选择。**
4. **S4**：工具 Schema/结构化数据自动制卡、媒体/图表/证据/进度节点、Work 运行内发布、公开 patch 与迁移。
5. **S5**：38 场景、主题/字号/无障碍、弱网、未知费用、IME、Android 16/17 真实验收及当前 head CI。

## 证据边界

新增代码的测试覆盖是**测试源码**；除非有绑定当前 Git SHA 的成功回执，不声明编译、CI 或设备测试成功。发布说明应只将上表“本轮新增”标记为本轮贡献。


## 本轮续做（新提交，不属于前述 `09822351`）

- **`ea745fa6`：调试切片真实接线。** `LocalConversationSliceHost` 从直接 `NextConversationUiPack.Render` 改为经 `LocalConversationPackGate` → `UiPackRegistry` 审核后以动态注册 Pack 渲染。Host 独立定义必需场景，并从实际端口连接生成结构能力集合，不直接信任 Manifest 自报能力。拒绝准入时展示错误/返回按钮，阻止隐式切换会话或偷偷进入 V6。
- **`6fee3ab8`：受校验依赖版本约束。** 将 `:ui-host` 的 emoji2 解析固定在已有 `verification-metadata.xml` 校验记录的 1.4.0；这是针对上游 CI 依赖检验阻断的修正尝试，不关闭校验。
- **`d586ba39`：回归测试补充。** 端口完全不连接时必须拒绝 Pack，证明 Pack 的 Manifest 自报不能成为 Owner/Host 的能力事实来源。

**仍未完成：** 生产全 App 注册/导航、V6 全页面独立迁出、Next 全覆盖，动态 UI 媒体/结构化工具适配，真模型与设备验收。以上新提交未有当前 SHA 的成功 CI 回执，不得认定“构建已通过”。在线 Figma 未更新。
