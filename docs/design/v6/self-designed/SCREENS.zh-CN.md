# V6 自主设计 · 38 场景

用户于 2026-10-13 明确授权依据规范自主设计。本目录为新设计参考，非 10 月 12 日 ZIP 的还原，也非 Android 实机截图。原生页继续使用真实数据和现有回调；预览中的所有内容、数字和人物均为示例。

打开 `app.html` 可选择 38 个场景、三主题、393/412 宽度、1.0/1.3/1.5 字号和内容/加载/空白/错误状态。

| # | 页面 | 设计意图 | 原生归属（screens/ 相对路径） |
|---|---|---|---|
| 01 | 聊天 | 人物身份 → 对话正文 → 紧凑输入 → 模式切换 | `local/LocalConversationSurface.kt` |
| 02 | 工作执行 | 当前目标 → 五区导航 → 工作过程 → 提交区 | `local/LocalWorkDesignSurface.kt` |
| 03 | 运行中心 | 在跑 / 等你 / 历史 → 目标卡 → 代理任务 | `local/LocalRunCenterScreen.kt` |
| 04 | 消耗与用量 | 模型筛选 → 总量 → 模式指标 → 趋势 → 请求 | `settings/UsageCalculationPage.kt` |
| 05 | 沉浸阅读 | 实色返回栏 → 阅读标题 → 连续正文 | `local/FluidMessageReader.kt` |
| 06 | 人物图集 | 搜索 → 双列人物卡 → 独立管理 | `local/FluidPersonaGalleryGrid.kt` |
| 07 | 人物档案 | 正放照片 → 身份 → 四区导航 → 故事 | `local/FluidPersonaSections.kt` |
| 08 | 世界书 | 条目搜索 → 选中条目 → 标题 / 关键词 / 正文 | `local/PersonaWorldBookEditor.kt` |
| 09 | 人物日记 | 人物筛选 → 日期 → 事件 → 记录正文 → 来源 | `local/CharacterDiaryScreen.kt` |
| 10 | 群聊成员 | 隔离说明 → 成员选择 → 选中数 → 确认 | `local/GroupChatMemberPicker.kt` |
| 11 | 消息操作 | 上下文消息 → 常用动作 → 危险动作分区 | `local/FluidMessageActionSheet.kt` |
| 12 | 定时互动 | 作用域 → 状态 → 提醒正文 → 下次时间 | `tasks/ChatAutomationScreen.kt` |
| 13 | 任务编辑 | 目标输入 → 执行计划 → 通知 → 保存 | `tasks/TaskEditorComponents.kt` |
| 14 | 能力面板 | 检索 → 能力分组 → 可用状态 → 详情 | `tools/FluidCapabilityCatalogue.kt` |
| 15 | 工作历史 | 事件筛选 → 时间线 → 结果 → 原文证据 | `local/LocalWorkHistorySheet.kt` |
| 16 | 工作区 | 文件 / 产物 → 路径 → 搜索 → 分类文件行 | `local/LocalWorkspaceFilesDialog.kt` |
| 17 | 代理团队 | 真实进度 → 助手 → 任务 → 团队动态 | `local/LocalAgentTeamUi.kt` |
| 18 | 工具与插件 | 连接状态 → 传输类型 → 工具列表 → 管理 | `tools/PluginInventoryBrowser.kt` |
| 19 | 任务计划 | 状态筛选 → 状态标签 → 任务标题 → 时间 | `tasks/TaskCardComponents.kt` |
| 20 | 设置首页 | 常用 → 模型与体验 → 系统与维护 → 版本 | `settings/SettingsScreen.kt` |
| 21 | 模型设置 | 活动模型 → 档案列表 → 配置表单 → 保存 | `settings/LocalModelSettingsSections.kt` |
| 22 | 模型性能 | 推理上限 → 单选 → 温度上限 → 说明 | `settings/ModelPerformanceSettings.kt` |
| 23 | ChatGPT 账户 | 账户身份 → 状态 → 额度 → 两列管理动作 | `settings/ChatGptAccountPanel.kt` |
| 24 | 记忆 | 分类 → 记忆正文 → 作用域 / 来源 → 编辑 | `settings/MemorySettingsSections.kt` |
| 25 | 会话与存储 | 实际占用 → 预算 → 保留说明 → 导出 / 清理 | `settings/LocalSessionStorageCard.kt` |
| 26 | 外观与阅读 | 主题 → 预览 → 强调色 → 字号与字重 | `settings/AppearanceSettingsComponents.kt` |
| 27 | 权限与设备 | 权限说明 → 状态行 → 系统授权入口 | `settings/AgentDeviceSettingsSections.kt` |
| 28 | 通知设置 | 类型开关 → 免打扰 → 系统通知授权 | `settings/SettingsScreen.kt` |
| 29 | 高级与诊断 | 运行信息 → 全部 / 异常 → 日志 → 导出 | `settings/RuntimeDiagnosticsDialogs.kt` |
| 30 | 检查更新 | 应用标识 → 当前版本 → 检查状态 → 主动作 | `settings/SettingsScreen.kt` |
| 31 | 首次引导 | 欢迎 → 模式选择 → 按需授权 → 开始 | `local/FirstLaunchOnboarding.kt` |
| 32 | 权限申请 | 能力图标 → 使用说明 → 授权 / 稍后 | `settings/DeviceCapabilitiesController.kt` |
| 33 | 统一搜索 | 搜索输入 → 分类 → 类型图标 → 结果跳转 | `local/LocalHarnessDrawerComponents.kt` |
| 34 | 空态 | 图标 → 标题 → 出现条件 → 一个主动作 | `app/src/main/java/com/shenyan/designsystem/components/ShenyanFeedback.kt` |
| 35 | 错误态 | 失败说明 → 保留内容 → 恢复动作 | `app/src/main/java/com/shenyan/designsystem/components/ShenyanFeedback.kt` |
| 36 | 系统通知 | 应用来源 → 状态 → 摘要 → 任务入口 | `app/src/main/java/com/labteto/dshmobile/notify/DshNotifications.kt` |
| 37 | 完成与转场 | 完成状态 → 结果卡 → 打开产物 → 返回 | `local/WorkflowProgressSection.kt` |
| 38 | 媒体创作 | 类型页签 → 可用模型 → 参数 → 生成 → 历史 | `local/LocalMediaStudioSheet.kt` |
