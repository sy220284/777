# V6 · 当前实现与设计状态
> **2026-11-13 主线快照** `02544aa4b8d96fbd0e0aac5d7bf1a3e9f581df6a`。这是视觉/业务行为复核台账，而不是历史 38 张示例卡片的浏览器通过计数。下一轮尚未实现的目标见 [V6.4 设计规范](V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md)。参照 [现行规范](V6-CURRENT-IMPLEMENTATION.zh-CN.md)、[页面清单](current-ui.json) 和 [手机设计投影](mobile/app.html)。
## 代码已确认的约束
- 七条原生主路由 `HOME / PERSONA_GALLERY / DIARY / WORKSPACE / TASKS / TOOLS / SETTINGS`；侧栏的用量、群聊、阅读、MiniMax 配额等属于功能/设置二级入口，不要再创造“第八条”假一级路由。
- 设置内部 `SettingsDestination` **21 项**；外观预览缩小，高级外观默认折叠；页面上直接检查更新，不再绘制独立更新页。
- 聊天助手正文使用全宽开放阅读，#896 已清理旧 91% 卡片；用户气泡保留，旧助手回复提供展开操作。聊天对白保留单次换行，Work 使用统一时间线。
- 工作五段和独立产出卡、首页三选条、重复搜索、工作项目入口、独立任务运行中心、后台助手、聊天用量管理和套餐提示均已移除；数据/审批/成果没有因此被删除。
- 全局 L1–L5 场景同源、壁纸按区域取色、动效受 `SyMotion` 统一控制；通用输入、视觉和无障碍详见规范。旧 `Ds` 绘制组件不得重新进入新页面。
- **#711 已合并**：紧凑材质不再受高光测量撑宽影响；技能/插件采用 40dp 图标和独立操作行，Android 16/17 自动化已通过，字体放大与 TalkBack。
- **#705 已于 2026-10-17 合并；后续原生 V6.3 恢复已经取代 UI 3.0 双包方案**。本轮评估以当前主线实际 Compose 为准。**#896 已合并**，其聊天阅读流、输入与工作交互调整纳入本次设计规范对照。
- **Figma 未同步**：账户 Starter MCP 调用达到限额，未修改可编辑设计层；Canva 也未找到 777 同名设计稿/品牌包，均不计为本轮实施或视觉验证。
## V6.4 规范新增整改台账（全部待代码实施/设备验证）
| 优先级 | 设计问题 | 目标验收 | 本轮结果 |
|---|---|---|---|
| P0 | 强调色按钮文字可能低于 4.5:1 | 八色卡 × 三主题 × 正常/按下实际对比值自动校验 | 规范已定义，代码待改 |
| P0 | 玻璃透明度最终合成后可能低于目标对比 | 极端壁纸 + 真实区域 + 合成顺序校验 | 规范已定义，代码待改 |
| P1 | 部分平台控件位置取色为固定中段 | 文本框/Chip/卡片相同位置使用同一采样背景 | 规范已定义，代码待改 |
| P1 | 菜单/Sheet/Dialog 共用单一 L4 外观 | 三类浮层同源但有不同阴影、边缘和阅读密度 | 规范已定义，代码待改 |
| P1 | 文档参数脱离当前实现 | 当前值与待实施值分离；后续 CI 对比关键令牌 | 当前文档已修订，自动门禁待扩展 |
| P2 | 组件光线过重与场景动效不足 | 真实 Android 截图及录屏核实材质与状态动效 | 设计目标已定义 |
## 已实现交互与验证层级
| 现有体验 | 生产实现 | 已有测试源（不等于本轮执行） | 设备视觉结果 |
|---|---|---|---|
| 全宽阅读、操作按需展开 | `LocalHarnessMessageComponents` | `WorkReplyTimelineVisualTest` 覆盖部分 Work 状态 | 待实测 |
| 历史阅读锚点、自动分页 | `LocalTranscriptVisibleWindow`、`LocalTranscriptPagingUi` | `LocalTranscriptVisibleWindowTest`、`LocalTranscriptPagingAndroidTest` | 待实测 |
| 大字设置行纵排 | `SyCategoryRow`、`AppSettingsComponents` | `SettingsRowIconVisualTest` 仅覆盖图标与点击，字体仍需补测 | 待实测 |
| Work 六态、结果未知和回执 | `LocalWorkTimelineStepRow` | `WorkReplyTimelineVisualTest` | 待实测 |
| 导航手势与状态恢复 | `LocalFeatureAnimatedHost` | `LocalNavigationAndTimelineStressTest` 覆盖部分导航逻辑 | 待实测 |
详情见 [已实现交互契约](V6-IMPLEMENTED-INTERACTION-CONTRACT.zh-CN.md)。
## 原生场景验收（按实际产品，而非旧 38 屏编号）
| 场景与入口 | 关键状态 / 交互 | 源码/任务 | 交互改进 |
|---|---|---|---|
| HOME 聊天 | 侧栏/搜索、群聊、长文本与换行、分支、用户/助手操作区、气泡、IME、滚动阅读 | `LocalConversationSurface`、`LocalHarnessMessageComponents` | 待实测 |
| HOME 工作 | 时间线/计划、审批/提问、草稿队列/立即调整、停止、真实进度和成果卡 | `LocalConversationComposer`、`LocalWorkStateContent` | 待实测 |
| PERSONA_GALLERY / DIARY | 人物选择、详情编辑、群聊、日记与返回 | `PersonaGalleryOverviewV3`、`CharacterDiaryScreen` | 待实测 |
| WORKSPACE | 列表/目录/预览、打开文件与回到会话成果 | `LocalWorkspaceFilesDialog` | 待实测 |
| TASKS | 空列表/创建/编辑/真实执行状态 | `TasksScreen` | 待实测 |
| TOOLS | 技能/插件列表与说明、搜索、安装更新、错误/无数据/安全确认，320dp 与放大字体 | `ToolsScreen`；#711 已合并，待视觉复查 | 待实测 |
| SETTINGS | 21 项设置内部导航与来源恢复；模型、账号、消耗、记忆、权限、通知、诊断 | `SettingsScreen` | 待实测 |
| 外观全局 | 三主题、壁纸/纯色、环境光/对比/透明度滑块、无白底弹层、系统减少动效 | `ShenyanFoundation`、`ShenyanMaterialColor`、`AppearanceSettingsComponents` | 待实测 |
| 搜索/媒体/系统弹层 | 单列菜单、四项加号入口、焦点、权限、文件选择、取消/错误/结果不明 | 对应 Feature UI / Host | 待实测 |
## 关闭条件
1. 将当前文档分支合并后确认 V6 页面清单、HTML 和源码对应；CI 的静态门禁全部通过。
2. #711 已合并且 Android 16/17 自动化回归通过；工具/技能页面 320dp、放大字体与 TalkBack 仍需设备视觉复核。
4. Figma 可访问后同步可编辑节点、语义变量/组件/场景，并复查画板对比；未访问或失败时保持“待同步”。
5. 每次入口调整、统一视觉 Token 或移除功能时同步本台账、[规范](V6-CURRENT-IMPLEMENTATION.zh-CN.md)、[机器清单](current-ui.json) 和 [离线设计稿](mobile/app.html)，旧 ZIP 不参与继承。
## 新版设计交付核对
- GitHub 当前分支的活动 SVG 文件总览应恰好 24 枚；历史 Kotlin 中保留的 2 条路径不能被视为可点击入口。静态门禁应查实际 SVG 内容及离线 HTML 的图标映射，不能只读 README 文案。
- 离线 HTML 已拆分 9 个此前共用占位设计的设置结构；这是布局/交互投影，不接入账号、存储、真实用量或模型能力。
- 旧版固定数量截图从现行目录剔除，不再复用旧图；新渲染 PNG 仅存在外部设计交付包，不参与 GitHub 源码打包。
## 历史附录：2026-10-15 本地审计修复候选（不再代表主线现状）
此处为 2026-10-15 旧候选快照，今日状态应回查主线；旧稿曾包含：保留现有七条产品路由。工作会话菜单增加“工作记录与交付”弹层：完整历史游标分页、工具真实阶段和原始事件详情、当前目标与步骤、选定成果版本的手动来源关联。关联只证明来源，不代表验收通过。文件元数据观察用于刷新预览，显式核验和关联才计算内容哈希。
记忆管理改为源数据先搜索／类型筛选再分页，可到达旧记录；任务暂停、恢复、取消与立即运行提供异步回执，异常后先重读事实而不重放命令。Android 测试代码已编译。