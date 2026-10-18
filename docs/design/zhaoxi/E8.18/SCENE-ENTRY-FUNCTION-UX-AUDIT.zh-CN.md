# 朝夕 E8.18 · 全页面、入口、业务接线与动效审计

基准：用户提供的 E8.18 ZIP（prototype.html、tokens.css、组件五态陈列、背景素材与 Compose 规范）、已上传「ui设计」技能、《E8.17 全页面布局一致性审计》及 sy220284/777 的 PR #746。
本次代码审计严格区分 **静态可到达**、**真实 Owner 接线**、**实际操作验证** 与 **逐屏视觉签收**，不将空态截图或 CI 绿灯冒充最终设计还原。

## 1. 全场景入口覆盖

- `ZhaoxiUiPackManifest.fullSceneIds` 包含 **75 个非宿主 Scene**，`ZhaoxiApplicationScenePack.RenderScene` 对每个 ID 都存在静态分支；这只证明分发完整，**不代表业务运行和所有按钮已验收**。
- `ZhaoxiUiPackManifest.fullCapabilityIds` 共 **37 个能力 ID**；运行时按真实注入端口裁剪，**不能把常量声明视为成功连接**。
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
| 10 | 运行详情/证据 | work.requirement_evidence、work.plan_review | Work Owner 真实需求/文件证据/版本状态，不再直出 data class toString | 设计进度环、事件时间线、逐步审批及实际运行夹具 |
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

已移除测试演示入口、硬编码旧版号、API/Owner/route 术语和常见 `toString()` 文本泄漏，覆盖诊断、工作证据、媒体、设置、自动化、记忆、文件与人物空态；只保留有用户决策价值的费用、系统授权、历史用量偏差和失败说明。不得移除真正的错误回执或危险操作提示。

## 5. 验收硬门槛和遗留项

1. 最新 HEAD 完整通过 scope、static、architecture、unit、ARM64、x86、Android 16/17；旧提交通过**不算**。
2. 75 Scene 的 light 截图、重点页面 dark/130% 截图、带真实内容的聊天与工作例子；增加 Button 的点击/禁用/loading 设备断言，不用空态代替业务 E2E。
3. 需要针对真实后台工作/审批、群聊、日记、媒体和文件，按 owner 的具体写入及错误回执分别签收。
4. **仍未闭环**：十张 E8.18 opt-in 壁纸导入和启用状态、完整人物视觉、工作进度环/事件时间线、媒体结果跨设备截图、用户关闭动画的全路径、Figma 设计节点逐层比对；Figma Starter MCP 已遇额度限制。
5. 以上明确的缺口不是“已经完成”，本 PR 仍应保持 Draft；达到所有签收条件之后再考虑合并与发版。
