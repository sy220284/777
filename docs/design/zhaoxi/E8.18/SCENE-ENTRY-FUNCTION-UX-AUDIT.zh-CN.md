# 朝夕 E8.18 · 全页面、入口、业务接线与动效审计

基准：用户提供的 E8.18 ZIP（prototype.html、tokens.css、组件五态陈列、背景素材与 Compose 规范）、已上传「ui设计」技能、《E8.17 全页面布局一致性审计》及 sy220284/777 的 PR #746。
本次代码审计严格区分 **静态可到达**、**真实 Owner 接线**、**实际操作验证** 与 **逐屏视觉签收**，不将空态截图或 CI 绿灯冒充最终设计还原。

## 1. 全场景入口覆盖

- `ZhaoxiUiPackManifest.fullSceneIds` 包含 **75 个非宿主 Scene**，`ZhaoxiApplicationScenePack.RenderScene` 对每个 ID 都存在静态分支；这只证明分发完整，**不代表业务运行和所有按钮已验收**。
- `ZhaoxiUiPackManifest.fullCapabilityIds` 共 **36 个能力 ID**；运行时按真实注入端口裁剪，**不能把常量声明视为成功连接**。
- `conversation.attachments`、`shell.notifications`、`system.permissions` 属于 Host 负责的 3 个系统场景，不应在 UI Pack 再渲染一份。
- 扫描应用场景分发与主要 scenes 文件内的显式 `UiSceneId("...")` 后，未发现指向 fullSceneIds 外部的字符串导航目标。动态菜单、宿主提案以及运行时动作仍须设备验证。
- 七个顶级路由：`route.home`、`route.persona_gallery`、`route.diary`、`route.workspace`、`route.tasks`、`route.tools`、`route.settings`；四类能力共用外壳时允许别名，但**独立设计目的地不得复用同屏空态充数**。

## 2. E8.18 25 核心设计页 × 代码接线

| # | 设计页 | Scene / 实现 | 本轮源码落实 | 剩余验收 |
|---|---|---|---|---|
| 1 | 聊天主页 | conversation.chat | ChatUiPort/ConversationDraftUiPort/ReadingUiPort；消息、Composer、Stop 实链；48dp 点击与阅读布局 | 长回复、工具等待、真实流式样本、头像与正文缩进视觉 |
| 2 | 工作主页 | conversation.work | WorkConversationUiPort/审批/问题/文档；工作阅读流与操作入口 | 真实运行计划→工具→审批→队列的连续录屏 |
| 3 | 群聊 | chat.group_members、chat.group_announcement | ChatGroupUiPort，成员/公告可提交 | 多成员长时间线、公开日记样例 |
| 4 | 沉浸阅读 | conversation.reader | 消息 identity 与正文读取；富文本渲染 | 真实长文、200% 字体、返回锚点 |
| 5 | 人物图集 | route.persona_gallery | PersonaGalleryUiPort 实际读取；搜索、双列人物、预设折叠、导入/新建 | 真实头像/亲密度卡、跨字号密度 |
| 6 | 人物编辑 | chat.persona_editor、chat.world_book | PersonaAuthoringUiPort 四页签、保存；世界书直达对应 Tab | 头像统计、复杂草稿与状态、图片对拍 |
| 7 | 日记 | route.diary、chat.diary | DiaryUiPort 的日记场景 | 公开/私人、纠错、来源与删除状态的证据 |
| 8 | 工作区 | route.workspace | FilesUiPort.workspaceFiles/conversationFiles，工作区与会话产物分域 | 搜索、长文件名、分享/导出（当前 FilesUiPort 能力约束） |
| 9 | 文件预览 | workspace.preview | FilesUiPort.previewWorkspaceFile，大小、正文、截断状态 | 大文件滚动、二进制与文件分享 |
| 10 | Work 计划与事件 | work.plan_review | Work Owner 真实计划、工具执行事件和状态 | 计划审阅、事件时间线与真实运行夹具 |
| 11 | 定时任务 | route.tasks、automation.tasks | AutomationUiPort：状态筛选、真实暂停/继续/立即运行，任务操作行独立避免拥挤 | 各状态与调度策略设备验收 |
| 12 | 任务编辑 | automation.editor、automation.persona_interaction | Create/Update、generation fence、当前聊天身份；单次、固定间隔、日、周、月、沉默、时段及原自然语言规划，非真实支持项不伪造 | 失败策略设计、编辑旧调度、后台实际执行 |
| 13 | 工具中心 | route.tools、work.capabilities | ToolsUiPort：按能力家族、技能、连接分区，跳独立功能页 | 高数量/长名称、错误态、权限样本 |
| 14 | MCP | tools.mcp | ToolsUiPort：HTTP 配置、健康检查、断开，返回原服务端回执 | 真实连接/失败/权限审批与连接器全态 |
| 15 | 用量统计 | settings.usage、settings.model_usage | UsageUiPort：大数字三联、模型筛选、分组明细 | 大数据排序、切换模型、历史不完整提示 |
| 16 | 用量日志与详情 | settings.usage_log、settings.usage_detail | UsageUiPort.recentRecords/requestDetail，以真实 requestId 绑定单条 | 空/估算/异常/长数字/窄屏样本 |
| 17 | MiniMax 套餐 | settings.minimax_quota | SettingsUiPort.queryMiniMaxQuota：AccountBalance/TokenPlan 结构化展示 | 多窗口重置、API 异常与真实套餐验收 |
| 18 | 设置首页 | settings.root | 对话体验/模型账户/Agent 安全/数据系统分区，补个人规则入口 | 25 页间信息架构、横跨字体档位 |
| 19 | 外观 | settings.appearance | HostPreferences：明暗、五色、字号、环境光、玻璃、动效 | 字重、自适应对比、**可选** 10 张壁纸资源接入 |
| 20 | 模型 | settings.models、settings.model_performance、settings.pricing | Settings/Performance/Pricing Owner；选中/编辑/测试/保存，移除前确认 | 配置卡视觉和真实多模型、凭据失败流程 |
| 21 | 记忆 | settings.memory、settings.memory_settings、settings.memory_management | 全局统计、自动召回/记忆、增删改查拆为独立页；MemoryUiPort 与 SettingsUiPort | 人物/项目作用域筛选、完整记录复核 |
| 22 | 权限 | settings.permissions | HostPreferences 真实系统通知/媒体权限、SettingsUiPort 审批模式 | 系统回跳、审批权限与不同 Android 版本 |
| 23 | 通知 | settings.notifications | 五类通知开关、免打扰时间入 HostPreferences | 免打扰时间与异常保存、通知送达 |
| 24 | 会话存储 | settings.session_storage | SettingsUiPort 的真实容量、压缩、文档导出 | 大容量、异常、用户选文件 URI |
| 25 | 聊天风格 | settings.chat | StyleGuard、过滤词增删、命中记录/二次清空 | 实际文本命中、跨人物与关闭态 |

**E8.15 扩展**：`media.creation` 与 `media.result` 已拆出独立视图，保留 MediaUiPort.generate/export/share/persona portrait owner；再生成涉及模型费用时要求明确确认。`settings.session` 与 `settings.advanced` 已拆为“个人规则”/“高级设置”两页，不再重复堆记忆和风格开关。人物互动页面已移除示例卡，改读当前会话的真实任务。

## 3. 按钮、导航与交互断言

| 类别 | 真正的操作及回执 | 新增改善 | 未通过的边界 |
|---|---|---|---|
| Button / IconButton | clickable(enabled)、Role.Button | 48dp、focus 框、pressed 缩放、独立 loading spinner；Outline 真描边 | 需不同背景/字体截图与 TalkBack 顺序 |
| Field / Switch / Chip / Tabs | TextField/onValueChange、toggleable、selectable、onSelect | focus/error 描边、Switch 滑块动画、selected 语义、Tab 横滚 | 错误文本与外部焦点验证 |
| NavRow / Row / Drawer | onNavigate / onClick，经 Host 提交 | 56dp 交互行、长文本分权重列、抽屉可滚动，静态行无假箭头 | 设备 130%/200% 和横竖屏 |
| ZxDialog / Toast | destructive actions 需确认 | 原来内联确认容器已改真实 Android Dialog；淡入淡出遵循 Reduced Motion | 焦点、返回、旋转、生命周期 |
| 删除配置 / 账户 / 媒体 | Settings/Media Owner 变更 | 模型/ChatGPT 账户确认删除；媒体再生成提示模型费用 | 失败/离线后重复提交和错误恢复 |
| 动态列表 / 技能工具 | 原 Owner 快照，不根据 ID 猜数据 | 主操作与开关分行避免小屏挤压；状态反馈 | 真实大列表、权限、并发更新 |
| 消息与 Work 操作 | Chat/Work Owner 与会话 identity | 保留渐进回复主权、拒收不丢草稿 | 成功/失败/撤销/审批同屏运行验收 |

## 4. 文案清理原则与已改项

已移除测试演示入口、硬编码旧版号、API/Owner/route 术语和常见 `toString()` 文本泄漏，覆盖诊断、工作计划、媒体、设置、自动化、记忆、文件与人物空态；只保留有用户决策价值的费用、系统授权、历史用量偏差和失败说明。不得移除真正的错误回执或危险操作提示。

## 5. 验收硬门槛和遗留项

1. 最新 HEAD 完整通过 scope、static、architecture、unit、ARM64、x86、Android 16/17；旧提交通过**不算**。
2. 75 Scene 的 light 截图、重点页面 dark/130% 截图、带真实内容的聊天与工作例子；增加 Button 的点击/禁用/loading 设备断言，不用空态代替业务 E2E。
3. 需要针对真实后台工作/审批、群聊、日记、媒体和文件，按 owner 的具体写入及错误回执分别签收。
4. **仍未闭环**：十张 E8.18 opt-in 壁纸导入和启用状态、完整人物视觉、工作进度环/事件时间线、媒体结果跨设备截图、用户关闭动画的全路径、Figma 设计节点逐层比对；Figma Starter MCP 已遇额度限制。
5. 以上明确的缺口不是“已经完成”，本 PR 仍应保持 Draft；达到所有签收条件之后再考虑合并与发版。


## 2026-10-19 · 3.0 接口与全部 75 场景逐项源码核对

本轮从 #759 的 `455d2ac22` 组合基线继续实现。以下 75 行分别核对宿主登记、Pack 分发、实际场景函数及接口消费；**记录的是源码事实，不是逐动作验收通过**。三份既有需求文档中的“待逐动作验收”不改为已完成。新增入口复用既有场景，不恢复已撤销的需求与证据页面。

### 本轮明确修复

- 文档详情、计划审查、回复版本、媒体结果、工作调整由不可导航的 SLOT 修正为 OVERLAY；菜单不再把完整页面塞进 340dp 小窗口。保留 header/timeline/composer/update 等真正的嵌入槽。新增生产宿主导航进入、返回、恢复测试。
- 接回人物故事归档、固定技能与连接器、人物修正提示/撤销；原 Owner 保持唯一，未另建业务状态或执行器。
- 补回消息覆盖确认、Work 外部副作用提示、编辑草稿保护、助手重生成/回复版本入口；审批本轮设备授权、撤销与全局自动审批均走真实策略端口。
- 自动化确认删除；图片理解方式；媒体存储恢复、失败记录清理、视频仅重试本地保存与历史继续加载。
- MCP stdio 使用原样 JSON argv；GitHub 技能安装与刷新。交互文档增加动作回执/发送确认、生成反馈、正文/表格/图表编辑、单个/批量删除。
- 当前会话消息正文搜索使用 Owner 游标继续检索，命中打开精确正文；不把标题搜索冒充全历史消息搜索。

### 逐项映射

下表所有行状态均为“源码已核；编译、设备动作及逐屏视觉待验”。`/` 分隔的是同一场景消费的原有端口，不代表新增接口。

| Scene | 场景实现 | 接口或宿主边界 | 入口与动作核对 |
|---|---|---|---|
| `conversation.chat` | `ZhaoxiChatScene` | ChatUiPort / Draft / Reading / History / Reasoning / PersonaCorrection | 发送、停止、历史、草稿、推理档位、人物修正撤销 |
| `conversation.work` | `ZhaoxiWorkScene` | WorkConversationUiPort / Draft / Reading / Reasoning | 发送、排队、停止、审批、问答、计划与团队入口 |
| `conversation.header` | `ZhaoxiConversationMenu` | Session / ComposerInvocation / PersonaGallerySave / WorkPolicy | 模式、附件、归档、固定调用、完整详情入口、撤销本轮设备授权 |
| `conversation.timeline` | `ZhaoxiTimelineScene` | Chat / Reading / History / UiDocument | 有界历史、版本切换、阅读锚点、文档回执 |
| `conversation.composer` | `ZhaoxiComposerSlotScene` | Chat / ConversationDraft | 发送、停止与拒收保留草稿；生产 Chat/Work 使用完整输入区 |
| `conversation.ui_document` | `ZhaoxiUiDocumentScene` | UiDocumentUiPort | 表单动作回执、发送确认、正文/表格/图表编辑、单个/多选删除 |
| `conversation.ui_document_generate` | `ZhaoxiUiDocumentGenerateScene` | UiDocumentUiPort | 模型/离线生成、加载反馈、失败回执、成功进入详情 |
| `conversation.reply_status` | `ZhaoxiReplyStatusScene` | ChatUiPort | 执行阶段与排队数量展示 |
| `conversation.message_actions` | `ZhaoxiMessageActionsScene` | Chat / WorkConversation | 复制、阅读、修改、助手重生成、回复版本入口 |
| `conversation.edit` | `ZhaoxiEditScene` | Chat / WorkConversation | 加密编辑草稿、覆盖后续确认、外部副作用说明、放弃确认 |
| `conversation.reader` | `ZhaoxiReaderScene` | message target / preview | 精确正文阅读及返回；搜索命中沿此入口打开 |
| `conversation.model_picker` | `ZhaoxiModelPickerScene` | SettingsUiPort | 选择模型、等待实际选中投影、管理模型 |
| `chat.persona_picker` | `ZhaoxiPersonaGalleryScene` | PersonaGallery / Authoring / Transfer / Portrait | 搜索、预设连续安装、创建、导入、详情 |
| `chat.persona_editor` | `ZhaoxiPersonaEditorScene` | PersonaAuthoringUiPort | 四页签、13项字段、事实/世界书增删改、AI建议采纳、保存与离开确认 |
| `chat.character_tuning` | `ZhaoxiCurrentPersonaTuning` | CurrentPersonaUiPort | 当前人物采样与恢复全局采样；不恢复旧行为滑杆 |
| `chat.group_members` | `ZxGroupMembersScene` | ChatGroupUiPort | 选人、保存成员、创建群聊 |
| `chat.group_announcement` | `ZxGroupAnnouncementScene` | ChatGroupUiPort | 公告保存与原始回执；无退役的独立AI入口 |
| `chat.reply_suggestions` | `ZxReplySuggestionsScene` | ReplySuggestions / Draft | 生成已有建议、选择填入草稿 |
| `chat.branches` | `ZhaoxiBranchesScene` | ChatUiPort | 独立打开已保存回复版本、SelectVariant、返回 |
| `work.approval` | `ZhaoxiApprovalScene` | WorkConversation / WorkPolicy | 批准/拒绝、过期状态、本轮设备授权、全局自动审批二次确认 |
| `work.steering_choice` | `ZhaoxiSteeringScene` | WorkConversation / Draft | 菜单独立进入、调整指令、真实受理/排队、失败留草稿 |
| `work.question` | `ZhaoxiQuestionScene` | WorkConversationUiPort | 选项/自由回答、提交、拒收保留答案 |
| `work.plan_review` | `ZhaoxiPlanReviewScene` | WorkRead / WorkConversation | 独立进入计划/待办/事件/工具状态、历史分页、审批问答跳转 |
| `work.team` | `ZhaoxiWorkTeamScene` | WorkTeamUiPort | 成员/任务/输出/发消息/停止/重派与真实回执 |
| `work.capabilities` | `ZhaoxiToolsScene` | Tools / ComposerInvocation | 能力目录；HTTP/stdio连接、健康检查；GitHub授权/目录；技能本地/GitHub安装、编辑、启停、删除 |
| `workspace.preview` | `ZhaoxiWorkspacePreviewScene` | FilesUiPort / Host exporter | 文件正文/截断、保存、分享、外部打开 |
| `chat.gallery` | `ZhaoxiPersonaGalleryScene` | PersonaGallery / Authoring / Transfer / Portrait | 搜索、预设连续安装、创建、导入、详情 |
| `chat.gallery_profile` | `ZhaoxiPersonaProfileScene` | Gallery / Selection / Story / Transfer / Authoring | 选择人物、续接/新故事、历史分页、设定检查、编辑/删除故事、导出与更多管理 |
| `chat.world_book` | `ZhaoxiPersonaEditorScene` | PersonaAuthoringUiPort | 四页签、13项字段、事实/世界书增删改、AI建议采纳、保存与离开确认 |
| `chat.gallery_delete` | `ZxGalleryDeleteScene` | PersonaGalleryDeletionUiPort | 确认删除人物、缺失/失败回执与返回图集 |
| `chat.gallery_portrait` | `ZhaoxiPortraitEditor` | PersonaPortraitUiPort / Host picker | 导入、预览、确认移除；裁切效果仍待设备核对 |
| `chat.diary` | `ZhaoxiDiaryScene` | DiaryUiPort | 公开日记阅读、版本勘误、停用 |
| `automation.tasks` | `ZhaoxiAutomationTasksScene` | AutomationUiPort | 搜索、模式/状态筛选、编辑、暂停/恢复/立即运行、确认删除 |
| `automation.editor` | `ZhaoxiAutomationEditorScene` | AutomationUiPort | 时间选择、周期/时段/沉默、创建/编辑、自然语言规划、保存回执 |
| `automation.persona_interaction` | `ZhaoxiPersonaInteractionScene` | AutomationUiPort | 当前人物会话任务与编辑入口；进入不自动生成建议 |
| `tools.mcp` | `ZhaoxiToolsScene` | Tools / ComposerInvocation | 能力目录；HTTP/stdio连接、健康检查；GitHub授权/目录；技能本地/GitHub安装、编辑、启停、删除 |
| `tools.github` | `ZhaoxiToolsScene` | Tools / ComposerInvocation | 能力目录；HTTP/stdio连接、健康检查；GitHub授权/目录；技能本地/GitHub安装、编辑、启停、删除 |
| `tools.plugins` | `ZhaoxiToolsScene` | Tools / ComposerInvocation | 能力目录；HTTP/stdio连接、健康检查；GitHub授权/目录；技能本地/GitHub安装、编辑、启停、删除 |
| `media.creation` | `ZhaoxiConnectedMediaScene` | MediaUiPort / Settings / Portrait / Host exporter | 多模型创作、结果预览/分享/保存、再生成确认、存储恢复、视频保存重试、历史继续加载 |
| `media.result` | `ZhaoxiConnectedMediaScene` | MediaUiPort / Settings / Portrait / Host exporter | 多模型创作、结果预览/分享/保存、再生成确认、存储恢复、视频保存重试、历史继续加载 |
| `shell.new_session` | `ZhaoxiHomeScene` | SessionUiPort / ConversationShell | 新建/续接、侧栏、会话模式/标题/固定、路由打开 |
| `shell.search` | `ZhaoxiSearchScene` | Session / Gallery / Automation / Files / MessageHistory | 四域过滤、当前会话正文搜索/游标续查、精确命中阅读 |
| `shell.onboarding` | `ZhaoxiOnboardingScene` | Host navigation | 引导、跳过、连接模型、进入主页 |
| `shell.empty` | `ZhaoxiEmptyDemoScene` | Host navigation | 真实空态与返回主页 |
| `shell.error` | `ZhaoxiErrorDemoScene` | Host navigation | 返回和本地诊断；不伪造报错/重试 |
| `settings.root` | `ZhaoxiSettingsHomeScene` | Settings / Host navigation | 分类设置、所有二级入口、直接检查更新 |
| `settings.session` | `ZhaoxiRuntimeSettingsScene` | SettingsUiPort | 个人规则/运行限制分开；高级设置增加图片理解方式 |
| `settings.appearance` | `ZhaoxiAppearanceScene` | HostPreferencesUiPort | 主题/壁纸/字体/对比/透明/动效保存 |
| `settings.chat` | `ZhaoxiChatGuardScene` | SettingsUiPort | 过滤开关、词条增删、命中记录、清空确认 |
| `settings.models` | `ZhaoxiModelsScene` | SettingsUiPort | 添加、目录发现、测试、保存、切换、删除确认 |
| `settings.model_performance` | `ZhaoxiPerformanceScene` | ModelPerformanceUiPort | 推理/温度读取与保存 |
| `settings.account` | `ZhaoxiAccountScene` | SettingsUiPort | 多账户连接/选择/测试/授权/撤销/移除及套餐状态 |
| `settings.model_usage` | `ZhaoxiUsageOverviewScene` | UsageUiPort / Settings | 已配置/历史模型切换、统计、精确模型日志跳转 |
| `settings.minimax_quota` | `ZhaoxiQuotaScene` | SettingsUiPort | 真实套餐窗口、剩余与查询状态 |
| `settings.pricing` | `ZhaoxiPricingScene` | PricingUiPort | 定价来源/时间与模型价格 |
| `settings.usage` | `ZhaoxiUsageOverviewScene` | UsageUiPort / Settings | 已配置/历史模型切换、统计、精确模型日志跳转 |
| `settings.usage_log` | `ZhaoxiUsageLogScene` | UsageUiPort | 模型/时间筛选、Owner历史分页/失败重试、请求详情 |
| `settings.usage_detail` | `ZhaoxiUsageDetailScene` | UsageUiPort | 按requestId读取单次消耗 |
| `settings.memory` | `ZhaoxiMemoryOverviewScene` | Memory / Settings | 有效统计、自动记忆/召回状态、管理入口 |
| `settings.memory_settings` | `ZhaoxiMemorySettingsScene` | SettingsUiPort | 记忆/召回策略保存 |
| `settings.memory_management` | `ZhaoxiMemoryManagementScene` | Memory / Settings | 可见作用域、全库搜索/分页、编辑、置顶、遗忘确认 |
| `settings.permissions` | `ZhaoxiPermissionsScene` | HostPreferences / Android permission bridge | 系统授权真实状态、跳系统设置和回跳 |
| `settings.approval_mode` | `ZhaoxiApprovalModeScene` | SettingsUiPort | 默认/手动/自动审批真实持久状态与确认 |
| `settings.notifications` | `ZhaoxiNotificationsScene` | HostPreferencesUiPort | 分类通知开关、免打扰时间与保存 |
| `settings.advanced` | `ZhaoxiRuntimeSettingsScene` | SettingsUiPort | 个人规则/运行限制分开；高级设置增加图片理解方式 |
| `settings.session_storage` | `ZhaoxiStorageScene` | SettingsUiPort / SAF | 容量、压缩、导出、恢复暂存与重启说明 |
| `settings.diagnostics` | `ZhaoxiDiagnosticsSettingsScene` | SettingsUiPort / SAF | 环境、网络、报告与安全导出 |
| `settings.update` | `ZhaoxiDiagnosticsScene` | App update callback | 仅状态槽；设置页直接发起检查更新 |
| `route.home` | `ZhaoxiHomeScene` | SessionUiPort / ConversationShell | 新建/续接、侧栏、会话模式/标题/固定、路由打开 |
| `route.persona_gallery` | `ZhaoxiPersonaGalleryScene` | PersonaGallery / Authoring / Transfer / Portrait | 搜索、预设连续安装、创建、导入、详情 |
| `route.diary` | `ZhaoxiDiaryHubScene` | Gallery / Diary | 先选人物，再打开对应公开日记 |
| `route.workspace` | `ZhaoxiWorkspaceScene` | FilesUiPort / Host exporter | 文件/会话产物分域、搜索、刷新、预览、多选打包保存分享 |
| `route.tasks` | `ZhaoxiAutomationTasksScene` | AutomationUiPort | 搜索、模式/状态筛选、编辑、暂停/恢复/立即运行、确认删除 |
| `route.tools` | `ZhaoxiToolsScene` | Tools / ComposerInvocation | 能力目录；HTTP/stdio连接、健康检查；GitHub授权/目录；技能本地/GitHub安装、编辑、启停、删除 |
| `route.settings` | `ZhaoxiSettingsHomeScene` | Settings / Host navigation | 分类设置、所有二级入口、直接检查更新 |

### 宿主场景与边界

| 系统场景 | 核对内容 | 当前状态 |
|---|---|---|
| conversation.attachments | 系统选择器→AttachmentUiPort→会话附件草稿→发送与失败保留 | 既有源码链；设备授权/取消/冷恢复待验 |
| shell.notifications | 通知抵达→Host 目标解析→Session 接单→精确会话 | 既有宿主链；系统送达与过期目标待验 |
| system.permissions | 用户主动触发→系统授权→回跳刷新 | 既有宿主链；Android 16/17 待验 |

`PersonaArchiveMutationUiPort` 是原领域归档事务协作边界，由 Gallery/Story/Deletion 的公开端口调用，不增加第二个直接写入 UI；`WorkReadUiPort` 的需求证据读取不作为恢复已撤销页面的理由；`UiDocumentFormUiPort`/Reading/Draft 属于宿主恢复和表单生命周期，不按每个辅助方法新增按钮。

### 验证边界与尚未签收

- 本轮实际执行并通过：UI 模块边界、3.0 架构边界、执行/性能不变量、Kotlin 风险模式、测试质量、构建版本基线、功能树同步及二次独立复核、git diff 空白检查。对全部显式 onNavigate 目标核对 Host kind，未发现 SYSTEM/SLOT/未知路由。
- 新增 Kotlin 单测包括详情导航进入/返回/恢复、修正撤销原始身份投影、stdio 参数原样解析、显示编辑不触及绑定/表单/审批节点。仅提交测试源码，未执行不得记为通过。
- 本机工具链 plan 显示缺少 JDK 27、Android SDK、Gradle 及离线依赖/Runtime 组件，不能在本机完成 Kotlin 编译、APK 或 Android 16/17 实测。源码未经编译的风险尚未消除。
- Figma `oHivuQziUC5AbWET9tJXX9` 读取被 Starter MCP 调用限额拒绝，whoami 确认为 Starter/View；没有在线画板修改和设计对拍证据。沿用仓库 E8.18 设计基线。
- 全面屏/系统栏、130%/200% 字体、肖像裁切、外部账户与真实付费媒体、后台运行及跨页恢复仍待最新组合的设备验收。不能用本表或已有 CI 代替。
- 本轮远端推送被自动审批拦截，等待用户明确授权后才提交 #759；本地提交不能描述为 GitHub 已更新。
