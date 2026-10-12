# 神言神语设计稿 × 777 主线 · 38 屏逐项审计

> 权威来源：用户上传的 `shenyan-final.zip`，其中 `app.html` SHA-256 为 `0c2327d2ebad4c2def8df7b9416bff0e42afbef71d15e34ad3a7d89672b55ea8`。设计包含 38 个界面、18 个组件、6 类转场。Android 源码路径前缀：`app/src/main/java/com/labteto/dshmobile/ui/screens/`（components/ 行另指 `ui/components/`）。

**判定原则**：只换颜色/主题不算完成；代码已实现但未截图/设备验收为“部分”。参考稿中演示金额、模型名、进度、人设、权限均不是软件实时业务数据，不得复制为生产数据。设计稿外的入口必须保留业务功能，但按同一套组件、导航、边距、图标与动效重排。

## 页面审计台账

| # | 设计页面 | 原生代码归属 | 状态 | 当前主要差距/验收重点 |
|---|---|---|---|---|
| 01 | 聊天三栏 | `local/LocalConversationSurface.kt；local/LocalHarnessDrawer.kt` | 部分 | 三栏/单栏投影、头像、消息布局、滚动和连续回复仍需设备逐状态截图 |
| 02 | 工作执行 | `local/LocalWorkDesignSurface.kt；local/LocalWorkProcessComponents.kt` | 部分 | 五区已接，审批与输出片段需要逐状态/转场验收 |
| 03 | 运行中心 | `local/LocalRunCenterScreen.kt；local/FluidRunBoardColumn.kt` | 部分 | 三看板已接，长列表、空态、等待审批进入后动效未验 |
| 04 | 消耗与用量 | `settings/UsageCalculationPage.kt；settings/UsageCalculationComponents.kt` | 本轮修改 | 真实模型构成行可以直接点击筛选，保留真实趋势、单笔账本及历史不足提示；图形/布局/设备验收待完工 |
| 05 | 沉浸阅读 | `local/FluidMessageReader.kt；local/LocalFeatureAnimatedHost.kt` | 部分 | 抽屉收起/返回/消息原位恢复需实机验证 |
| 06 | 人物图集 | `local/FluidPersonaGalleryGrid.kt；local/PersonaGalleryOverviewV3.kt` | 本轮修改 | 首十卡 360ms/80ms Cascade 已接，长按有震动；照片适配/进入后动作仍需设备比对 |
| 07 | 人物档案 | `local/FluidPersonaSections.kt；local/PersonaGalleryDialog.kt` | 部分 | 关系/故事线/记忆/档案四段必须与现有状态分离一致 |
| 08 | 世界书 | `local/PersonaWorldBookEditor.kt` | 本轮修改 | 手机上下/≥600dp 主从双栏已接；选中条目、关键词与草稿提交仍须真实设备测试 |
| 09 | 人物日记 | `local/CharacterDiaryScreen.kt` | 部分 | 阅读布局、隐私隔离已接；编辑/停用/来源返回的实际设备交互需验 |
| 10 | 群聊成员 | `local/GroupChatMemberPicker.kt` | 本轮修改 | 增加记忆隔离卡；仍需视觉比对、人数上下限与双向状态验证 |
| 11 | 消息操作 | `local/FluidMessageActionSheet.kt；local/LocalChatEditMessageSheet.kt` | 部分 | 复制/编辑重发/分支的独立语义、触觉与撤销需验 |
| 12 | 人物定时互动 | `tasks/ChatAutomationScreen.kt；local/LocalAutomationUiContribution.kt` | 待改 | 按人物/触发时间/内容组织；不得伪造亲密度与长期计数 |
| 13 | 任务编辑器 | `tasks/TaskEditorComponents.kt` | 本轮修改 | 目标/计划/通知分组已改；设计中的步骤拆解需真实工作任务 API 支持，不能造假 |
| 14 | 能力面板 | `local/LocalWorkCapabilitySheet.kt；tools/FluidCapabilityCatalogue.kt` | 部分 | 低中高授权等级与工具来源映射、审批不可逆交互待验 |
| 15 | 工作历史 | `local/LocalWorkHistorySheet.kt` | 本轮修改 | 已接全部/调用/开始/结果真实事件页签与原证据回跳；状态聚合需可信数据源 |
| 16 | 工作区 | `local/LocalWorkspaceFilesDialog.kt` | 部分 | 路径边界与分享已接；目录树、文件预览、权限回退需验 |
| 17 | 代理团队 | `local/LocalAgentTeamUi.kt` | 本轮修改 | 真实任务数进度已接统一 running/done/failed 视觉态、成员与任务详情展开触觉；分工布局与实机动效待验 |
| 18 | 工具插件 | `tools/ToolsScreen.kt；tools/PluginInventoryBrowser.kt` | 本轮修改 | 实际连接 HTTP/stdio/全部分类、实时数量及连接管理已接；设计中的商店/Webhook 操作只有实态后可显示 |
| 19 | 任务计划 | `tasks/TasksScreen.kt` | 本轮修改 | 全部/待执行/执行中/暂停玻璃选项及动态计数、空态恢复已接；单任务成本/预算不可取时不显示 |
| 20 | 设置首页 | `settings/SettingsScreen.kt` | 部分 | 三组分区已接，保留额外入口仍须归一同款列表行和图标 |
| 21 | 模型设置 | `settings/LocalModelSettingsSections.kt` | 待改 | 模型档案/协议/上下文层级与可用模型动态展示 |
| 22 | 性能设置 | `settings/ModelPerformanceSettings.kt` | 本轮修改 | 自定义玻璃单选行及触觉温度滑块已接，真实配置持久化保留；速率/成本指标只在可得时展示 |
| 23 | ChatGPT账户 | `settings/ChatGptAccountPanel.kt` | 部分 | 登录授权来源与套餐用量不得写死，账户状态需实机验 |
| 24 | 记忆 | `settings/MemorySettingsSections.kt` | 本轮修改 | 真实记录分类切到玻璃页签，保留编辑和来源作用域；六类独立开关无独立底座，不伪造 |
| 25 | 会话存储 | `settings/LocalSessionStorageCard.kt` | 本轮修改 | 会话归档的真实占用/预算进度条、清理与导出入口已接；其余存储分类缺真实统计，不展示设计稿示例数字 |
| 26 | 外观阅读 | `settings/AppearanceSettingsComponents.kt` | 本轮修改 | 四态主题玻璃单选/触觉已接并复用持久化；实际 Light/Dark/Matte 渲染仍待截图回归 |
| 27 | 权限设备 | `settings/DeviceCapabilitiesController.kt` | 本轮修改 | 实测无障碍、通知监听及应用通知发布权限分开展示，虚拟屏仅标 API 可用而非已授权；其余系统权限待逐项接入 |
| 28 | 通知设置 | `settings/SettingsScreen.kt` | 本轮修改 | 实际系统通知发布授权、跳转设置、现有本机任务通知开关与用途说明已接；六类独立开关/免打扰业务链未接 |
| 29 | 高级诊断 | `settings/RuntimeDiagnosticsDialogs.kt` | 本轮修改 | 真实环境信息、网络诊断及 AppLog 错误/全部玻璃筛选、计数和导出已接；运行时字段详细卡布局待验 |
| 30 | 检查更新 | `settings/SettingsScreen.kt` | 待改 | 版本/签名/hash 实值、不伪造更新/未来发布日期 |
| 31 | 首次引导 | `local/LocalHarnessScreen.kt；local/LocalModeIntroBanner.kt` | 待改 | 三步引导和先体验后授权的真实导航状态 |
| 32 | 权限申请 | `settings/DeviceCapabilitiesController.kt` | 本轮修改 | Android 13+ 可在用户点击时请求 POST_NOTIFICATIONS，拒绝后保留系统设置恢复路径；完整首启浮层未按稿交付 |
| 33 | 全局搜索 | `local/LocalHarnessDrawerComponents.kt` | 本轮修改 | 会话+人物真实搜索、分类计数、跳转图集和无结果提示已接；任务/文件索引及精确人物直达尚未实现 |
| 34 | 空态合集 | `components/DsPageEmptyState.kt` | 本轮修改 | 共用空态真实动作升级为主按钮，复杂空态分支与屏幕布局仍需验收 |
| 35 | 错误态合集 | `components/Overlays.kt；components/DsPageEmptyState.kt` | 部分 | 网络/权限/设备/语义故障需真实诊断与对应操作 |
| 36 | 通知样式 | `components/Overlays.kt；settings/SettingsScreen.kt` | 待改 | 应用内 toast 与系统通知分别验颜色、跳转和免打扰 |
| 37 | 完成转场 | `local/WorkflowProgressSection.kt；local/LocalWorkStateContent.kt` | 待改 | 原位 Morph/任务卡片出现，不能无条件弹成功提示 |
| 38 | 媒体创作 | `local/LocalMediaStudioSheet.kt` | 部分 | 图像/视频/语音/转录已有；模型能力、参数和历史仍需实际验证 |

## 跨页面 18 个组件对照

| 设计组件 | 原生对口 | 本轮动作/差距 |
|---|---|---|
| Button | `DsButton` | 暖杏渐变主操作、按下 80ms 缩放、8dp→0 阴影；仍需 48dp 触控设备验收 |
| GlassCard | `DsCard` | 可点击卡悬停上浮 -2dp 与响应阴影，不对整个内容加 blur |
| Input | `DsTextField` | 焦点、禁用、错误和键盘 IME 交互待逐组件验收 |
| Tabs | `DsSegmented` | 200ms 实色选中片，保留 RadioButton 无障碍语义 |
| TabBar | `DsSegmented` + `FluidSectionNavigation` | Tab 型增加 18dp 选中下划线，导航五项以上仍需手机抽屉规则 |
| ModalSheet | `DsBottomSheet` | 顶部 24dp、手势/返回/审批不可逆需实机验证 |
| ConfirmDialog | `DsDialog` | 92% 进入、16dp 浮层阴影，危险动作必须区分确认语义 |
| SearchBar | `DsSearchField` | 14dp 圆角、暖杏聚焦描边、玻璃聚焦实色；只有真实支持时才显示快捷键 |
| Toast | `DsToastHost` | 修正前景/深底对比，新增 WCAG 4.5:1 单测 |
| ProgressBar | `DsProgressBar` | idle/running/done/failed 四态，340ms，失败定格不持续脉动 |
| Avatar | `FluidPersonaGalleryGrid`/头像相关组件 | 图片裁切、首字回退、状态与动画待验 |
| Switch | `DsSwitch` | 需要设备触摸/实际持久化验证 |
| ListItem | `DsSheetChoiceRow`/设置列表 | 保留副标题/图标/右侧动作及实态 |
| StatusTag | `DsStatusPill` | 语义状态颜色，红色仅不可逆/破坏 |
| FileRow | `DsDeliverableCard` | 路径安全及真实文件 MIME/缩略图 |
| Note | `DsPageEmptyState`/本机内联提示 | 提示等级与恢复路径待逐屏接入 |
| LoadingStates | `DsPageLoadingState` | 仅真实加载态呼吸，不在完成失败态持续动 |
| ApproveCard | `LocalPlanReviewCard` | 确认/取消和执行边界需保持真实运行权限 |

## 动效检查

| 类型 | 设计指标 | 当前代码证据 | 结论 |
|---|---|---|---|
| Push | 20px + fade，480ms | `LocalFeatureAnimatedHost` / `DsAnimations.featurePageForward` | 已接但未录屏验收 |
| Modal | .92→1，280ms | `DsDialog` / `DsAnimations.modal` | 已接但未录屏验收 |
| Sheet | 底部 +40px，380ms | `DsBottomSheet` (Material3 Sheet) | 系统动态曲线和手势仍待实机对比 |
| Swap | ±60px，380ms | `FluidSectionSwap` | 已接但页面覆盖不足 |
| Morph | 原位变形，500ms | 部分工作状态单独动画 | 缺统一实现/验收 |
| Cascade | 80ms stagger，最多 10 条 | `FluidPersonaGalleryGrid` + `DsAnimations.cascade` 已接；抽屉未接 | 图集待设备验证/抽屉待补齐 |

## 质量门禁与禁止事项

- **结构**：入口、子页面、主操作的位置与层级必须与手机稿一致；无稿新入口按相同信息架构插入，不删功能。
- **数据**：只有真实运行的数据和已实现的功能才能渲染；参考稿示例数据永远不能当结果。
- **可访问性**：48dp 最小触控目标、内容描述、字体缩放、系统返回、语义选择状态。
- **状态**：加载/成功/失败/取消/无数据/权限不足逐态验收，不得仅对正常页面截图。
- **视觉**：Light/Dark/Matte、两类手机宽度、显示缩放、长中文及英文长路径；对照 JPG/HTML 原稿录屏、截图逐点确认。
- **回归**：静态、架构、Kotlin/单测、arm64、Android16/17 仪表测试都通过后才能写“设备验收通过”。
- **不能用构建成功代替视觉验收**，也不能把本台账或共享主题更新当作 38 屏全部完成。

## 三轮实现日志（2026-10-12 / PR #654）

- 第 18 屏：真实连接的 HTTP、stdio 分类与数量，连接管理功能保留。测试 `PluginTransportFilterTest`。
- 第 19 屏：真实任务状态筛选、动态数量、无结果恢复入口。测试 `WorkTaskListFilterTest`。运行、失败和取消状态不会被混算成计划中；不使用稿内虚构货币统计。
- 第 24 屏：记忆真实记录的类型筛选统一为玻璃 Tab，保留人物/会话作用域，暂不提供六类虚构独立开关。
- 所有新入口仅调用仓库现有业务回调，不覆盖本机定时逻辑和模型协议。
- Figma MCP Starter 限流且为 View seat，本轮无法通过插件读写 Figma 节点，源 HTML 和预览作为验收资料。
- 最新 head 的构建、两代 Android 仪表测试及视觉截图/录屏未通过前仍保持 Draft。

- 第 17 屏（第四组）：代理团队进度改用 `DsProgressBar` 的实际运行/完成/失败颜色，成员和任务详情使用触觉反馈；不改变调度、代理日志和审批语义。

## 五轮核查与新提交（2026-10-12 / PR #654）

当前按代码与原稿逐页核对：共 38 屏，按本轮进展分类为“本轮修改”18、“部分”13、“待改”7、“待接通”0。**这些不是完成率：38 屏尚无一项取得完整的多状态截图、动效、Android16/17 设备验收证据。**

- 第 27/28 屏：加入 postingNotifications（真正在 Android 中发送通知的应用级状态），与现有通知监听服务严格分开；System Settings 返回和切页均会刷新；VirtualDisplay API 支持≠录屏已授权。
- 第 32 屏：RequestPermission(POST_NOTIFICATIONS) 由用户明确点击触发，Android 13+ 适用；用户拒绝后提供系统设置入口。没有擅自把权限开关伪造成立即授权成功。
- 第 33 屏：先接会话、人物两类真实内容检索和人物图集路由。任务、文件、跨模式全文搜索及结果精确跳转待后续能力接入，不应称全局搜索已完成。测试：LocalDrawerPersonaSearchTest。
- 第 25 屏：真实会话存储占用/预算比例，sessionStorageUsageFraction 保证范围并对无效预算返回 0；测试：LocalSessionStorageVisualTest。
- 第 29 屏：真实 AppLog 全部/异常计数与分组标签，保留刷新、完整诊断导出。
- Figma MCP 仍受 Starter 额度阻塞，已上传的 HTML/Compose 设计包为可用的原始对照依据；最新 CI 尚未证实通过，不把组件一致当做全页面验收完成。

## 最新差异校准（2026-10-12 / PR #654）

- 第 04 屏：真实模型构成列表支持直接切换模型用量视图；原有全部/单模型筛选共用唯一回调。
- 第 31/32 屏关联的原生权限生命周期：已移除 MainActivity 的“开屏立刻请求通知权限”；通知渠道仍创建，用户可在权限/通知页面明确点击系统授权按钮。首次欢迎引导页的完整三步骤和转场仍属第 31 屏未完成。
- 截至本轮代码提交，38 屏分类为：19 屏“本轮修改”、13 屏“部分”、6 屏“待改”。其含义仅为进展分类，**完整视觉/动效/Android16-17 回归验收为 0 屏**。
- 还未进入本轮页面结构改造的六项：12 人物定时互动、21 模型设置、30 检查更新、31 首次引导、36 通知样式、37 完成转场。
