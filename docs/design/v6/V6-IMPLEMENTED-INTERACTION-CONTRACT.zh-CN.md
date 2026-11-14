# 777 V6.3 · 已实现交互与组件契约
> **事实基线**：主线 `02544aa4b8d96fbd0e0aac5d7bf1a3e9f581df6a`（2026-11-13）。本文记录已合并 Compose 中值得继承的交互及其实现细节；下一轮视觉目标见 [V6.4 设计规范](V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md)。本轮只修订文档。
## 1. 事实与设计职责
- **唯一业务来源**：顶层路由由 `LocalFeatureCatalog` 拥有（7 条），设置二级目的地由 `SettingsScreen.kt#SettingsDestination` 拥有（21 项）；当前快照见 [机器清单](current-ui.json)。
- **共享视觉来源**：`SyColors`、`SyMetric`、`SyMaterialSurface`、`SyMotion`。Chat、Work、人物、文件、媒体、工具和设置分别保留专用布局。
- **文档角色**：[当前实现](V6-CURRENT-IMPLEMENTATION.zh-CN.md)与本文记录已经存在的行为，[V6.4](V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md)记录待实施目标，[STATUS](STATUS.zh-CN.md)分开记录代码、测试和 Android 实现状态。原型仅为交互投影。
## 2. 聊天、Work、历史与流式阅读
| 场景 | 当前实现 | 必须维持的体验 |
|---|---|---|
| 助手正文 | `LocalHarnessMessageComponents` 已采用全宽开放式阅读 | 连续阅读，不恢复旧 91% 卡片和重复底板 |
| 用户消息 | 使用独立气泡和长按操作 | 区分发言方，文字选取和链接照常使用 |
| 回复工具栏 | 最新助手保留主要操作，旧回复可点明确的 48dp 入口展开 | 保持可发现、可收起，不依赖隐蔽长按 |
| 前台可见窗口 | `LocalTranscriptVisibleWindow` 以消息 ID 锚定正在阅读的消息 | 新消息到来不得丢弃当前历史阅读位置 |
| 翻页 | `LocalTranscriptPagingUi` 监听主动向上滚动触发分页 | 后台更新不重复触发分页 |
| 流式与键盘 | 在读者位于末尾且未主动拖动时跟随消息增长；IME/输入区变化修正视口位置 | 手动向上查看历史后立即停止强制跟随 |
**源代码**：[消息与操作](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessMessageComponents.kt)、[窗口锚点](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptVisibleWindow.kt)、[分页与跟随](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptPagingUi.kt)。
## 3. 大字号和实际内容决定布局
- `SyCategoryRow` 根据标题与数值实际测量宽度判断能否同排；空间不足或系统 `fontScale >= 1.2` 时，改为标题、说明、数值依次纵排。
- `AppSettingsComponents` 的设置行在系统字号放大时也采用纵排。设置组使用标题、分隔线组织，不堆叠 L3 整块阴影卡。
- 独立交互目标至少 48dp；视觉图标更小时保留实际点击区域。长模型名、中英文混排、折行、屏幕阅读需实机核查。
- 未来统一布局应复用实测文字尺寸策略，而非只看屏宽或固定汉字个数。
**源代码**：[自适应行](../../../app/src/main/java/com/shenyan/designsystem/components/ShenyanCompound.kt)、[设置分组](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/settings/AppSettingsComponents.kt)。
## 4. Work 执行状态的真实语义
| 内部状态 | 显示文案 | 行为和无障碍要求 |
|---|---|---|
| DECLARED | 等待执行 | 说明任务尚未开始 |
| RUNNING | 执行中 | 仅当前执行项可有持续低强度动画 |
| COMPLETED | 已返回 | 工具返回与成果最终验收分开，完成后静止 |
| FAILED | 失败 | 警示图标、文字和可查看的错误回执 |
| CANCELLED | 已中断 | 终止进行中效果并记录中断事实 |
| OUTCOME_UNKNOWN | 结果待确认 | 保留不确定性，不伪造完成或隐式重试 |
- `LocalWorkTimelineStepRow` 将技术工具调用显示为可理解的动作，重复检查可合并计数，支持按需打开真实回执。
- 可交互行保证至少 48dp 触控，使用 `stateDescription` 告知 TalkBack 状态；文字与图标同时表达状态，不能只靠颜色。
- 工作消息仍位于时间线，不新增独立运行中心和旧式 Work 五段分页。
**源代码**：[Work 步骤](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkTimelineStepRow.kt)、[工作状态投影](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalWorkStateContent.kt)。
## 5. 导航、共享动画与输入区
- `LocalFeatureAnimatedHost` 是唯一导航转场主机，承载 Predictive Back 的手势进度。Android 两侧边缘遵循返回，抽屉另由内容区手势控制。
- 工作区文件共享元素由工作区+文件路径标识；两个不相关的文件不可共用过渡身份，系统减少动效时跳过非必要动画。
- `LocalConversationComposer` 依据 Chat/Work、焦点、换行、附件和运行状态决定是否展开；打开加号面板前处理 IME 焦点，运行中主操作为停止，发送与 Work 草稿调度分开。
- 使用 `SyMotion` 当前标准时长：按压 70ms、选择 150ms、前进 260ms、返回 220ms、同级 140ms、侧栏 180ms，后续优化只通过统一时序来源调整。
**源代码**：[页面转场](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalFeatureAnimatedHost.kt)、[聊天输入区](../../../app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationComposer.kt)。
## 6. 权限与能力事实
- 现行 `SettingsScreen` 在用户从 Android 系统设置返回后重新查询设备状态，区分无障碍服务连接、通知监听和普通通知发布权限的独立来源。
- **PR #897 仍是草稿候选**：按所需能力授权、拒绝后的修复入口及“已授权与服务连接”四态尚未进入主线。待实际合并后按最终代码升级本项，不提前记入当前页面清单。
- 工具、技能与插件按真实能力、安装和连接状态展示入口，受影响操作按真实回执启用/禁用，详细技术参数按需展开。
## 7. 各场景既有的特色
- 人物：双列卡、相片式详情、管理态置顶/排序/多选与低频操作下沉，详见 [手机端人物专用设计](../mobile-ui.md#人物图集)。
- 多模态：图像/视频/声音的真实成果状态、保存分享与模型归属，详见 [专用创作规范](V6-MEDIA-CHAT-EXPERIENCE.zh-CN.md)。
- 工作区：目录与搜索分页、文件预览、分享选择、路径层级和失败恢复保持完整。
- 任务：列表筛选不改变业务事实；运行、暂停、拒绝、取消和结果仍从真实操作回执读取。
## 8. 证据和持续维护
| 现有测试文件 | 可确认的测试方向 | 尚需验收 |
|---|---|---|
| `LocalTranscriptVisibleWindowTest`、`LocalTranscriptPagingAndroidTest` | 阅读窗口及分页 | 流式与 IME 同时变化、主动向上阅读、长会话实测 |
| `WorkReplyTimelineVisualTest` | Work 工具状态和回执操作 | 六态在 320dp、大字、TalkBack 下的真实观感 |
| `SettingsRowIconVisualTest` | 图标槽尺寸与整行点击 | 字号 ≥ 1.2、长文案、混排 |
| `LocalNavigationAndTimelineStressTest` | 部分导航与时间线逻辑 | 预测返回、文件共享动画的录屏 |
| `ShenyanReadableInkTest` | 部分语义字色对比 | 八色卡按钮以及壁纸最终合成对比 |
**每次合并后的维护路径**：先对照主线功能路由与机器清单，再更新现行规范及本契约；待实施优化只写入 V6.4；最后关联 STATUS 和离线设计源。已有稳定行为只复核差异，避免重复回退和全面重写。