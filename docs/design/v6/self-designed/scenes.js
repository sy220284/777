const scenes = [
  {
    "id": 1,
    "title": "聊天",
    "intent": "把人物与对话放在中心",
    "anatomy": "人物身份 → 对话正文 → 紧凑输入 → 模式切换",
    "kind": "chat",
    "native": "local/LocalConversationSurface.kt"
  },
  {
    "id": 2,
    "title": "工作执行",
    "intent": "目标清楚，决策留在上下文",
    "anatomy": "当前目标 → 五区导航 → 工作过程 → 提交区",
    "kind": "work",
    "native": "local/LocalWorkDesignSurface.kt"
  },
  {
    "id": 3,
    "title": "运行中心",
    "intent": "先处理阻塞，再查看执行",
    "anatomy": "在跑 / 等你 / 历史 → 目标卡 → 代理任务",
    "kind": "board",
    "native": "local/LocalRunCenterScreen.kt"
  },
  {
    "id": 4,
    "title": "消耗与用量",
    "intent": "总量、趋势、来源分层",
    "anatomy": "模型筛选 → 总量 → 模式指标 → 趋势 → 请求",
    "kind": "usage",
    "native": "settings/UsageCalculationPage.kt"
  },
  {
    "id": 5,
    "title": "沉浸阅读",
    "intent": "让长内容像一篇文章",
    "anatomy": "实色返回栏 → 阅读标题 → 连续正文",
    "kind": "reader",
    "native": "local/FluidMessageReader.kt"
  },
  {
    "id": 6,
    "title": "人物图集",
    "intent": "浏览与管理分开",
    "anatomy": "搜索 → 双列人物卡 → 独立管理",
    "kind": "gallery",
    "native": "local/FluidPersonaGalleryGrid.kt"
  },
  {
    "id": 7,
    "title": "人物档案",
    "intent": "照片承载身份，故事承载关系",
    "anatomy": "正放照片 → 身份 → 四区导航 → 故事",
    "kind": "profile",
    "native": "local/FluidPersonaSections.kt"
  },
  {
    "id": 8,
    "title": "世界书",
    "intent": "列表与编辑区明确分离",
    "anatomy": "条目搜索 → 选中条目 → 标题 / 关键词 / 正文",
    "kind": "worldbook",
    "native": "local/PersonaWorldBookEditor.kt"
  },
  {
    "id": 9,
    "title": "人物日记",
    "intent": "温暖的记录，安静的阅读",
    "anatomy": "人物筛选 → 日期 → 事件 → 记录正文 → 来源",
    "kind": "diary",
    "native": "local/CharacterDiaryScreen.kt"
  },
  {
    "id": 10,
    "title": "群聊成员",
    "intent": "选择可见，记忆边界明确",
    "anatomy": "隔离说明 → 成员选择 → 选中数 → 确认",
    "kind": "members",
    "native": "local/GroupChatMemberPicker.kt"
  },
  {
    "id": 11,
    "title": "消息操作",
    "intent": "一次操作一个明确结果",
    "anatomy": "上下文消息 → 常用动作 → 危险动作分区",
    "kind": "actions",
    "native": "local/FluidMessageActionSheet.kt"
  },
  {
    "id": 12,
    "title": "定时互动",
    "intent": "以人物和时间组织提醒",
    "anatomy": "作用域 → 状态 → 提醒正文 → 下次时间",
    "kind": "schedules",
    "native": "tasks/ChatAutomationScreen.kt"
  },
  {
    "id": 13,
    "title": "任务编辑",
    "intent": "先写目标，再定时间",
    "anatomy": "目标输入 → 执行计划 → 通知 → 保存",
    "kind": "editor",
    "native": "tasks/TaskEditorComponents.kt"
  },
  {
    "id": 14,
    "title": "能力面板",
    "intent": "发现能力，然后了解边界",
    "anatomy": "检索 → 能力分组 → 可用状态 → 详情",
    "kind": "catalogue",
    "native": "tools/FluidCapabilityCatalogue.kt"
  },
  {
    "id": 15,
    "title": "工作历史",
    "intent": "过程与证据各有层次",
    "anatomy": "事件筛选 → 时间线 → 结果 → 原文证据",
    "kind": "history",
    "native": "local/LocalWorkHistorySheet.kt"
  },
  {
    "id": 16,
    "title": "工作区",
    "intent": "文件身份和路径始终清楚",
    "anatomy": "文件 / 产物 → 路径 → 搜索 → 分类文件行",
    "kind": "files",
    "native": "local/LocalWorkspaceFilesDialog.kt"
  },
  {
    "id": 17,
    "title": "代理团队",
    "intent": "进度优先，成员可观察",
    "anatomy": "真实进度 → 助手 → 任务 → 团队动态",
    "kind": "team",
    "native": "local/LocalAgentTeamUi.kt"
  },
  {
    "id": 18,
    "title": "工具与插件",
    "intent": "连接管理不挤占能力发现",
    "anatomy": "连接状态 → 传输类型 → 工具列表 → 管理",
    "kind": "connections",
    "native": "tools/PluginInventoryBrowser.kt"
  },
  {
    "id": 19,
    "title": "任务计划",
    "intent": "状态先于长任务描述",
    "anatomy": "状态筛选 → 状态标签 → 任务标题 → 时间",
    "kind": "plan",
    "native": "tasks/TaskCardComponents.kt"
  },
  {
    "id": 20,
    "title": "设置首页",
    "intent": "分组清楚，入口易找",
    "anatomy": "常用 → 模型与体验 → 系统与维护 → 版本",
    "kind": "settings",
    "native": "settings/SettingsScreen.kt"
  },
  {
    "id": 21,
    "title": "模型设置",
    "intent": "当前模型与配置明确区分",
    "anatomy": "活动模型 → 档案列表 → 配置表单 → 保存",
    "kind": "models",
    "native": "settings/LocalModelSettingsSections.kt"
  },
  {
    "id": 22,
    "title": "模型性能",
    "intent": "上限与当前选择分别表达",
    "anatomy": "推理上限 → 单选 → 温度上限 → 说明",
    "kind": "performance",
    "native": "settings/ModelPerformanceSettings.kt"
  },
  {
    "id": 23,
    "title": "ChatGPT 账户",
    "intent": "身份、状态、动作互不争位",
    "anatomy": "账户身份 → 状态 → 额度 → 两列管理动作",
    "kind": "account",
    "native": "settings/ChatGptAccountPanel.kt"
  },
  {
    "id": 24,
    "title": "记忆",
    "intent": "来源清晰，纠正可追溯",
    "anatomy": "分类 → 记忆正文 → 作用域 / 来源 → 编辑",
    "kind": "memory",
    "native": "settings/MemorySettingsSections.kt"
  },
  {
    "id": 25,
    "title": "会话与存储",
    "intent": "实测占用与维护动作分开",
    "anatomy": "实际占用 → 预算 → 保留说明 → 导出 / 清理",
    "kind": "storage",
    "native": "settings/LocalSessionStorageCard.kt"
  },
  {
    "id": 26,
    "title": "外观与阅读",
    "intent": "预览和真实控件使用同一风格",
    "anatomy": "主题 → 预览 → 强调色 → 字号与字重",
    "kind": "appearance",
    "native": "settings/AppearanceSettingsComponents.kt"
  },
  {
    "id": 27,
    "title": "权限与设备",
    "intent": "权限状态不等于能力支持",
    "anatomy": "权限说明 → 状态行 → 系统授权入口",
    "kind": "permissions",
    "native": "settings/AgentDeviceSettingsSections.kt"
  },
  {
    "id": 28,
    "title": "通知设置",
    "intent": "通知类型与打扰策略分开",
    "anatomy": "类型开关 → 免打扰 → 系统通知授权",
    "kind": "notifications",
    "native": "settings/SettingsScreen.kt"
  },
  {
    "id": 29,
    "title": "高级与诊断",
    "intent": "让问题和技术详情可定位",
    "anatomy": "运行信息 → 全部 / 异常 → 日志 → 导出",
    "kind": "diagnostics",
    "native": "settings/RuntimeDiagnosticsDialogs.kt"
  },
  {
    "id": 30,
    "title": "检查更新",
    "intent": "版本和检查结果一眼可辨",
    "anatomy": "应用标识 → 当前版本 → 检查状态 → 主动作",
    "kind": "update",
    "native": "settings/SettingsScreen.kt"
  },
  {
    "id": 31,
    "title": "首次引导",
    "intent": "先体验，再按需配置",
    "anatomy": "欢迎 → 模式选择 → 按需授权 → 开始",
    "kind": "onboarding",
    "native": "local/FirstLaunchOnboarding.kt"
  },
  {
    "id": 32,
    "title": "权限申请",
    "intent": "说明用途，允许稍后",
    "anatomy": "能力图标 → 使用说明 → 授权 / 稍后",
    "kind": "permission",
    "native": "settings/DeviceCapabilitiesController.kt"
  },
  {
    "id": 33,
    "title": "统一搜索",
    "intent": "结果保留类型和上下文",
    "anatomy": "搜索输入 → 分类 → 类型图标 → 结果跳转",
    "kind": "search",
    "native": "local/LocalHarnessDrawerComponents.kt"
  },
  {
    "id": 34,
    "title": "空态",
    "intent": "解释原因，给出下一步",
    "anatomy": "图标 → 标题 → 出现条件 → 一个主动作",
    "kind": "empty",
    "native": "app/src/main/java/com/shenyan/designsystem/components/ShenyanFeedback.kt"
  },
  {
    "id": 35,
    "title": "错误态",
    "intent": "失败不冒充空白",
    "anatomy": "失败说明 → 保留内容 → 恢复动作",
    "kind": "error",
    "native": "app/src/main/java/com/shenyan/designsystem/components/ShenyanFeedback.kt"
  },
  {
    "id": 36,
    "title": "系统通知",
    "intent": "简洁、可展开、保护隐私",
    "anatomy": "应用来源 → 状态 → 摘要 → 任务入口",
    "kind": "notification",
    "native": "app/src/main/java/com/labteto/dshmobile/notify/DshNotifications.kt"
  },
  {
    "id": 37,
    "title": "完成与转场",
    "intent": "完成来自真实结果",
    "anatomy": "完成状态 → 结果卡 → 打开产物 → 返回",
    "kind": "completion",
    "native": "local/WorkflowProgressSection.kt"
  },
  {
    "id": 38,
    "title": "媒体创作",
    "intent": "类型、模型、输入、历史分区",
    "anatomy": "类型页签 → 可用模型 → 参数 → 生成 → 历史",
    "kind": "media",
    "native": "local/LocalMediaStudioSheet.kt"
  }
];
const glyphs = {"Persona": ["M12 10.4 C9.7 10.4 8.3 8.9 8.3 6.8 C8.3 4.8 9.8 3.3 12 3.3 C14.2 3.3 15.7 4.8 15.7 6.8 C15.7 8.9 14.3 10.4 12 10.4", "M5 20.2 C5.6 16.2 8.1 13.8 12 13.8 C15.9 13.8 18.4 16.2 19 20.2 M9.4 14.4 L12 16.4 L14.6 14.4", "M17.7 5 L19.2 4.2 L20 5.7"], "NewSession": ["M5.3 5.8 C5.3 4.6 6.2 3.7 7.4 3.7 H17 C18.5 3.7 19.4 4.7 19.4 6.2 V14.2 C19.4 15.6 18.4 16.6 17 16.6 H10.2 L6.1 20.2 V16.1 C5.6 15.6 5.3 15 5.3 14.2 Z", "M12.4 7.6 V13.4 M9.5 10.5 H15.3"], "Projects": ["M3.8 8.1 V6.5 C3.8 5.6 4.5 5 5.4 5 H9.2 L11 7 H18.6 C19.5 7 20.2 7.7 20.2 8.7 V17.9 C20.2 19 19.4 19.8 18.3 19.8 H5.7 C4.6 19.8 3.8 19 3.8 17.9 Z", "M8 13.5 L12 10.5 L16 13.5 L12 16.6 Z"], "Tasks": ["M7 3.7 H15.4 L19 7.4 V19.2 C19 20.1 18.3 20.6 17.5 20.6 H7 C6 20.6 5.4 20 5.4 19.1 V5.3 C5.4 4.4 6 3.7 7 3.7 Z M15.2 3.9 V7.6 H18.8", "M8.4 12.2 L10.3 14.1 L14.4 10.1 M8.4 17.5 H15.6"], "Settings": ["M12 3.4 L13.8 5.9 L16.8 5.2 L17.4 8.1 L20.2 9.4 L18.7 12 L20.2 14.6 L17.4 15.9 L16.8 18.8 L13.8 18.1 L12 20.6 L10.2 18.1 L7.2 18.8 L6.6 15.9 L3.8 14.6 L5.3 12 L3.8 9.4 L6.6 8.1 L7.2 5.2 L10.2 5.9 Z", "M12 15.1 C13.7 15.1 15.1 13.7 15.1 12 S13.7 8.9 12 8.9 S8.9 10.3 8.9 12 S10.3 15.1 12 15.1 Z"], "Search": ["M10.7 4.3 C14.5 4.3 17.2 7 17.2 10.8 S14.5 17.3 10.7 17.3 S4.2 14.6 4.2 10.8 S6.9 4.3 10.7 4.3 Z", "M15.9 16.1 L20.7 20.7 M9.1 7.8 L11.6 7.8"], "Gallery": ["M5 8.8 V18.3 C5 19.3 5.7 20 6.8 20 H17.8 C18.8 20 19.5 19.3 19.5 18.3 V8.8 C19.5 7.8 18.8 7.1 17.8 7.1 H6.8 C5.7 7.1 5 7.8 5 8.8 Z", "M7.9 7.1 V5.5 C7.9 4.5 8.6 3.9 9.6 3.9 H19.3 C20.2 3.9 20.8 4.6 20.8 5.5 V15.2", "M12.2 13.5 C13.8 13.5 14.7 12.4 14.7 11.1 C14.7 9.7 13.7 8.8 12.2 8.8 C10.7 8.8 9.7 9.7 9.7 11.1 C9.7 12.4 10.7 13.5 12.2 13.5 M8.6 18.1 C8.9 16.1 10.2 15 12.2 15 C14.2 15 15.6 16.1 15.8 18.1"], "GroupChat": ["M8.9 10.6 C10.7 10.6 11.9 9.3 11.9 7.6 C11.9 5.9 10.7 4.6 8.9 4.6 C7.1 4.6 5.9 5.9 5.9 7.6 C5.9 9.3 7.1 10.6 8.9 10.6", "M3.6 18.5 C3.8 15.5 5.8 13.5 8.9 13.5 C11 13.5 12.5 14.3 13.4 15.9", "M15.7 5.8 C17.5 6.1 18.4 7.4 18.2 9.2 C18.1 10.7 17.1 11.8 15.5 12.2 M15.7 14.5 C18.4 14.8 20 16.5 20.4 18.5 M16.8 19.2 L19 21 L21 19.2"], "Diary": ["M6.4 4.2 H18.1 V19.6 H6.4 C5.3 19.6 4.7 18.9 4.7 18 V5.9 C4.7 4.9 5.3 4.2 6.4 4.2 Z M8.3 4.2 V19.6", "M11.3 9 H15.8 M11.3 12.4 H16 M11.3 15.8 H14.6", "M14.8 4.2 V7.2 L16.5 6.2 L18.1 7.2 V4.2"], "WorkspaceFiles": ["M7.9 3.5 H15.1 L19.6 8 V18.8 C19.6 19.8 18.9 20.5 17.9 20.5 H7.9 C7 20.5 6.3 19.8 6.3 18.8 V5.2 C6.3 4.2 7 3.5 7.9 3.5 Z M15.1 3.5 V8 H19.6", "M3.7 8 V19.8 C3.7 21 4.7 21.7 5.8 21.7 H15.5", "M9.5 12.5 H16.2 M9.5 16 H14.3"], "RunCenter": ["M12 3.6 C16.7 3.6 20.4 7.3 20.4 12 S16.7 20.4 12 20.4 S3.6 16.7 3.6 12 S7.3 3.6 12 3.6 Z", "M10.2 8.3 L16 12 L10.2 15.7 Z M6.6 12 H7.2 M16.9 5.9 L18 5.1"], "Tools": ["M6.3 4.5 L9.4 7.6 L7.4 9.6 L4.3 6.5 C3.1 9.7 5.3 12.3 8.5 11.7 L16.2 19.4 C17.2 20.4 18.8 20.4 19.7 19.4 C20.7 18.4 20.7 16.9 19.7 15.9 L12.1 8.3 C12.8 5.1 10 2.9 6.3 4.5 Z", "M6 19.7 L8.9 16.8 M4.5 18.2 L7.5 21.2"], "Usage": ["M4.2 17.2 C4.2 12.8 7.6 9.4 12 9.4 C16.4 9.4 19.8 12.8 19.8 17.2", "M12 17.2 L15.4 12.6 M7.2 17.2 V19.5 H16.8 V17.2", "M6.2 6.5 L8.7 4.6 M12 6.1 V3.2 M17.8 6.5 L15.3 4.6"], "Reader": ["M12 6.2 C9.1 4.2 6.3 4.3 3.7 5.1 V18 C6.8 17.1 9.5 17.5 12 19.4 C14.5 17.5 17.2 17.1 20.3 18 V5.1 C17.7 4.3 14.9 4.2 12 6.2 Z M12 6.2 V19.4", "M6.3 8.7 C7.4 8.5 8.5 8.7 9.6 9.2 M14.4 9.2 C15.5 8.7 16.6 8.5 17.7 8.7"], "Quota": ["M5.2 6.6 H18.8 C19.8 6.6 20.4 7.3 20.4 8.3 V17 C20.4 18 19.8 18.7 18.8 18.7 H5.2 C4.2 18.7 3.6 18 3.6 17 V8.3 C3.6 7.3 4.2 6.6 5.2 6.6 Z", "M3.7 10.3 H20.3 M7.1 15 H10.2 M16.4 13.5 L18 15.1 L16.4 16.7 L14.8 15.1 Z"], "Appearance": ["M12 3.4 C17.2 3.4 20.6 6.9 20.6 11.5 C20.6 13.3 19.5 14.2 17.9 14.2 H15.9 C14.3 14.2 13.7 15 13.7 16.1 C13.7 17.5 12.9 20.6 10.5 20.6 C6.7 20.6 3.4 17 3.4 12 C3.4 7.2 7.2 3.4 12 3.4 Z", "M8 9.1 L8.1 9.1 M12.2 6.8 L12.3 6.8 M16.5 9.7 L16.6 9.7 M7.9 14.9 L8 14.9"], "Memory": ["M5.2 8.9 C5.2 6.4 7 4.8 9.2 5.2 C11.1 2.9 14 3.4 15.2 5.5 C18.3 5.3 20.2 7.4 19 10.1 C21.6 13 19.3 16.7 15.7 16.7 H8 C4.2 16.7 2.9 12.8 5.2 10.6 Z", "M9.2 16.8 V19.7 H14.7 V16.8 M11.9 19.7 V21.3", "M8.8 10.7 L11.6 12.8 L15.5 8.7"], "SessionStorage": ["M4.3 5.2 H19.7 V11 H4.3 Z M5.1 12.8 H18.9 V19.5 H5.1 Z", "M9.2 8.2 H14.8 M9.2 16 H14.8 M4.3 11 H19.7"], "Models": ["M12 3.6 L17.6 6.8 V13.2 L12 16.5 L6.4 13.2 V6.8 Z", "M12 3.6 V10.1 M6.4 6.8 L12 10.1 L17.6 6.8 M12 10.1 V16.5", "M16.8 17.5 L19.2 19.9 L21.2 17.9 M4.9 18.6 H10"], "Performance": ["M4 17.7 C4 13 7.6 9.1 12 9.1 S20 13 20 17.7 M6.4 17.7 H17.6", "M12 17.7 L16.2 12.5 M7.1 12.5 L6.1 11.5 M12 10 V8 M16.9 12.5 L17.9 11.5"], "Account": ["M6.3 4.6 H17.7 C18.8 4.6 19.5 5.3 19.5 6.4 V18 C19.5 19 18.8 19.8 17.7 19.8 H6.3 C5.2 19.8 4.5 19 4.5 18 V6.4 C4.5 5.3 5.2 4.6 6.3 4.6 Z", "M12 12.1 C13.5 12.1 14.4 11.1 14.4 9.8 S13.5 7.6 12 7.6 S9.6 8.5 9.6 9.8 S10.5 12.1 12 12.1 Z M8.2 16.9 C8.6 14.6 10 13.5 12 13.5 S15.4 14.6 15.8 16.9"], "Notifications": ["M7.6 9.8 C7.6 6.5 9.4 4.9 12 4.9 S16.4 6.5 16.4 9.8 V13.7 L18.3 16.1 H5.7 L7.6 13.7 Z", "M10.1 18.1 C10.4 19.4 11 20.1 12 20.1 S13.6 19.4 13.9 18.1 M12 3 V4.9", "M18.8 6.2 L20.4 5.5"], "Permissions": ["M12 3.2 L19.2 6 V11.8 C19.2 16 16.8 18.8 12 21 C7.2 18.8 4.8 16 4.8 11.8 V6 Z", "M8.7 12.1 L11 14.4 L15.6 9.8"], "Advanced": ["M5.9 4.2 V19.8 M12 4.2 V19.8 M18.1 4.2 V19.8", "M3.9 8 H7.9 V11 H3.9 Z M10 13 H14 V16 H10 Z M16.1 6 H20.1 V9 H16.1 Z"], "Updates": ["M19.5 9.8 C18.5 5.7 15.8 4 12 4 C7.5 4 4 7.5 4 12 C4 16.5 7.5 20 12 20 C15.4 20 18.3 18 19.4 15.1", "M16.2 9.6 H19.7 V6.1 M8.8 12 H15.3 M12 8.8 L15.3 12 L12 15.2"], "ChatStyle": ["M5.2 4.5 H18.8 C19.8 4.5 20.5 5.3 20.5 6.3 V15.6 C20.5 16.6 19.8 17.3 18.8 17.3 H10.3 L6 20.6 V17.1 C5.2 16.8 4.5 16.2 4.5 15.1 V6.3 C4.5 5.3 5.2 4.5 6.2 4.5 Z", "M8.2 9 H16.7 M8.2 12.9 H13.5"]};
