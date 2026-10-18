# 朝夕 E8.18 · UI 50 项专项深度审计（2026-10-18）

审计基线：PR #746 的 `feature/zhaoxi-e818-structural-alignment-20261018` 分支；用户本次上传的 `ui设计.skill` 中 `aesthetic-ui/SKILL.md`、`app-mobile.md`、`aesthetic-checklist.md`、`design-tokens.md`、`motion-and-interaction.md`、`page-patterns.md`。对照仓库已有 E8.18 审计文档，但不将其历史“已完成”表述直接当作通过证据。

**方法**：50 项不同专项检查（跨路由/功能/可用性/表现/设备门槛），逐项基于指定源码片段或 CI 记录。结论分为“静态通过”“问题 P0/P1/P2”“部分验证”“待实测”。每项是一次独立检查，不代表执行了 50 次完整设备回归，也不保证发现所有问题；任何截图、物理触控、视觉还原问题，未经真机实测均不得记作通过。

## 第 1 组：产品架构 / 入口 / 路由（01—10）

| # | 检查项 | 结果 | 事实/缺陷 | 证据位置 |
|---|---|---|---|---|
| 01 | 75 场景清单完整 | 静态通过 | Manifest 唯一 ID = 75，目标 75。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiUiPackManifest.kt:26 |
| 02 | 场景分发逐 ID 覆盖 | 静态通过 | 未匹配数 0。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:277 |
| 03 | 显式页面深链有效性 | 静态通过 | 应用分发内 36 处显式路由，未登记 0；其他文件动态路由另审。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:105 |
| 04 | Host 专属权限/通知/附件 | 静态通过 | 3 个宿主系统场景未进入 UI Pack 清单 = true。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiUiPackManifest.kt:26 |
| 05 | 业务端口未注入时的空白页 | 问题 P1 | 多分支直接使用 `sec?.tools?.let / settings?.let`；缺端口时可能整页空白，应提供缺失能力状态。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:161 |
| 06 | 设置子页信息架构 | 静态通过 | `settings.session` 和 `settings.advanced` 指向不同参数配置；动态实测仍需检查。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:462 |
| 07 | 媒体生成/结果目的地 | 静态通过 | 按 `resultsOnly = scene.value == media.result` 分流，是否符合设计稿仍需对拍。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:444 |
| 08 | 工作计划证据入口能力门控 | 静态通过 | 只有注入 evidence port 才允许深链；涉及实际会话身份仍需实测。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:356 |
| 09 | 侧栏检索与列表实际联动 | 待交互验收 | 抽屉存在搜索输入，但仍需验证它对会话/人物/任务/文件各域都确实过滤结果，非仅更新输入值。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxShell.kt:101 |
| 10 | 75 页截图覆盖和 130% 状态 | 部分验证 | 设备测试具备 75 场景、深浅主题及 130% 路径；不能从测试代码推断真实截图均通过。 | app/src/androidTest/java/com/labteto/dshmobile/ui/ZhaoxiAcceptanceAndroidTest.kt:82 |

## 第 2 组：聊天、工作与动态文档（11—20）

| # | 检查项 | 结果 | 事实/缺陷 | 证据位置 |
|---|---|---|---|---|
| 11 | 聊天发送回执与草稿保留 | 静态通过 | 只有 Accepted/Queued 清空草稿，拒收不丢用户输入；还需真实端到端发送测试。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:309 |
| 12 | 工作会话交互走 Work Owner | 静态通过 | 工作聊天的主要提交接 WorkConversationUiPort；连续运行、队列及取消需设备样本。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConversationScenes.kt:131 |
| 13 | 分支切换不能跨 Chat/Work Owner | 静态通过 | WORK 模式已显示不支持并禁止误下发 Chat 分支命令。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:334 |
| 14 | 消息重生成后盲目返回 | 问题 P1 | 触发再生成后无条件 `scope.onBack()`，未按拒收/失效回执留在当前页反馈。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/ZhaoxiApplicationScenePack.kt:338 |
| 15 | 沉浸阅读富文本与可选中复制 | 问题 P2 | 沉浸阅读以 Text 为主；需实测代码块/表格/大段文字可复制、可滚动与返回阅读位置。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConversationScenes.kt:220 |
| 16 | 文档动作会话身份/修订门控 | 静态通过 | UI Document 仍验证当前会话与 revision 后交给 Owner，再核具体动作身份和审批。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiUiDocumentScenes.kt:81 |
| 17 | 工作审批是否硬编码非过期 | 问题 P1 | 审批卡传 `expired = false`；若 Owner 状态过期而未撤销，界面可能错误显示可审批。需用 Owner 可用性状态实测。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSlotScenes.kt:168 |
| 18 | 工作审批拒绝回执 | 静态通过 | 只有命令被接收才退出，拒绝应保留页面；需要失效 pendingId 测试。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSlotScenes.kt:160 |
| 19 | 工作计划步骤与待办分组 | 静态通过 | 步骤和审批分组已存在，真实长步骤溢出/执行中态和多任务的布局需截图复验。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiWorkScenes.kt:55 |
| 20 | 需求证据版本及对象文本泄漏 | 静态通过 | 文件/需求状态与版本变化从结构字段展示；需核实实际成功/过期样本，不得把版本一致当功能核验通过。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiWorkScenes.kt:107 |

## 第 3 组：人物、日记、自动化与媒体（21—30）

| # | 检查项 | 结果 | 事实/缺陷 | 证据位置 |
|---|---|---|---|---|
| 21 | 人物预设导入结果 | 问题 P2 | Preset 安装回执直接字符串化，失败/冲突时暴露程序类型；应区分保存、冲突与拒绝。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiPersonaScenes.kt:104 |
| 22 | 人物画像移除是否确认 | 问题 P1 | 当前移除画像直接执行 Owner 命令，未见相邻确认 Dialog；误触后不可恢复时有数据损失风险。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedPersona.kt:192 |
| 23 | 日记保密视图分域 | 静态通过 | 公开日记投影有独立展示且明确私密内容边界，但必须从 Owner 证实私密字段不会误注入。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSlotScenes.kt:228 |
| 24 | 日记更正和停用调用版本栅栏 | 待核对 | 存在更正与停用回执，需旧 revision 及跨人物案例；当前仅证实代码有版本参数。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSlotScenes.kt:318 |
| 25 | 自动化月调度按自然日历 | 问题 P1 | “每月”被转为固定 30 天间隔；月底、闰年、DST 与用户期待的自然月可能偏离。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiAutomationScenes.kt:281 |
| 26 | 自动化表单真实任务身份 | 问题 P1 | 更新使用 taskId 和 generation fence；仍需测试别的会话编辑拒绝。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiAutomationScenes.kt:312 |
| 27 | 自动化首次运行输入方式 | 问题 P2 | 时间输入是自由文本 `YYYY-MM-DD HH:mm`，无日历/时间选择器与键盘类型；时区/格式易错。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiAutomationScenes.kt:221 |
| 28 | 图片生成比例校验 | 问题 P1 | 比例允许任意字符串直接传入 Image 命令；未见提交前的比例合法性反馈。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedMedia.kt:88 |
| 29 | 付费媒体再次生成确认 | 静态通过 | 已在模型再次调用前增加明确确认，仍须断言取消不会提交命令。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedMedia.kt:182 |
| 30 | 媒体失败结果的非付费重试入口 | 问题 P1 | MediaUiPort 提供不再次计费的图片下载恢复方法，但当前媒体结果页未接入；失败文件仅显示不可用。 | ui-contract/src/main/kotlin/com/labteto/dshmobile/uicontract/MediaUiPort.kt:53 |

## 第 4 组：设置、模型、权限与工具（31—40）

| # | 检查项 | 结果 | 事实/缺陷 | 证据位置 |
|---|---|---|---|---|
| 31 | 设置入口分组与一级信息架构 | 静态通过 | 设置按会话体验/模型账户/权限/数据区分；核心入口实际最少点击步数未验证。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSettingsScenes.kt:52 |
| 32 | 通知权限/首选项保存反馈 | 静态通过 | 提醒开关提交给 HostPreferences 并区分保存结果；通知送达与 Android 后台策略仍待验证。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSettingsScenes.kt:173 |
| 33 | 免打扰时间区间有效性 | 问题 P2 | 输入日期格式通过校验，但开始与结束相同仍会提交；明确全日/空窗口语义前不能用作有效配置。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSettingsScenes.kt:205 |
| 34 | 系统权限返回后的 UI 观察更新 | 待实机 | 权限页直接同步读取系统授权状态，无明显生命周期恢复触发；系统设置返回后的 Compose 刷新需要验证。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiSettingsScenes.kt:217 |
| 35 | 模型 API Key 输入保密显示 | 静态通过 | 模型凭据输入遮蔽。应补凭据更新、日志泄漏和 Activity 重建时私密文本持久化验证。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedSettings.kt:8 |
| 36 | 模型/账户删除安全确认 | 静态通过 | 两个删除路径均有二次确认，需测试取消操作无副作用及取消成功回执。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedSettings.kt:66 |
| 37 | 工作运行限制数值边界 | 问题 P1 | 高级页只判断是否整数，0/负数/过大值可能先提交；具体合法区间需与 Settings Owner 对齐。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedSettings.kt:234 |
| 38 | 模型采样温度非法值引导 | 问题 P2 | 温度输入只检查可解析为 Double，未见字段端的合法区间与即时提示，具体区间由 Owner 确认。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiConnectedSettings.kt:253 |
| 39 | MCP 操作结果与连接健康回执 | 问题 P2 | 连接、健康检查、断开使用 `.toString()` 提示，成功/失败和认证问题未被翻成用户可读状态。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiAutomationScenes.kt:405 |
| 40 | GitHub 未授权状态的下一步入口 | 问题 P1 | GitHub 页提示需要授权但没有可执行的授权操作入口，用户可能卡在空态。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/scenes/ZhaoxiAutomationScenes.kt:446 |

## 第 5 组：组件、视觉、无障碍与设备验收（41—50）

| # | 检查项 | 结果 | 事实/缺陷 | 证据位置 |
|---|---|---|---|---|
| 41 | 核心按钮 48dp 与按下反馈 | 静态通过 | 按钮最小高度 48dp，按压缩放/加载态/禁用行为已经存在；设备触控验证仍需全页抽样。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxPrimitives.kt:69 |
| 42 | 图标按钮可读标签与触控 | 静态通过 | 图标按钮 48dp 且要求语义描述；调用点的空标签或含糊名称需逐项 TalkBack 验证。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxPrimitives.kt:133 |
| 43 | 输入框错误态读屏可感知 | 问题 P1 | ZxField 有错误红框，但没有同时暴露独立错误文案与 TalkBack 错误语义；仅靠颜色传达状态。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxPrimitives.kt:169 |
| 44 | 标签/筛选控件选中态语义 | 静态通过 | Chip 提供 selectable 与 selected 状态、按压触感；过多胶囊的焦点顺序/窄屏位置仍需测。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxPrimitives.kt:279 |
| 45 | 通用空态是否具备下一步动作 | 问题 P2 | ZxEmpty 组件只有标题+正文+图标，无通用行动按钮参数；部分空态只给提示而没有重试/创建入口。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxEmpty.kt:24 |
| 46 | 危险弹框是系统模态并支持返回 | 静态通过 | 真实 Dialog 已使用系统 onDismiss；旋转、背景点击、TalkBack 焦点困锁需设备验收。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/components/ZxFeedback.kt:76 |
| 47 | 动画减弱策略集中生效 | 待验证 | 设计层有 reduced-motion 标志与统一时长 spec；需检查系统 animator scale=0、所有页面/组件回路确实使用。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/theme/ZhaoxiTheme.kt:157 |
| 48 | 正文/背景 WCAG 4.5:1 对比度 | 待测量 | 令牌有多套颜色，但本次无直接像素对比度测试结果；不能由视觉感觉认定全主题 AA 通过。 | ui-pack-zhaoxi/src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/theme/ZhaoxiTheme.kt:49 |
| 49 | 放大字体与 TalkBack 全场景覆盖 | 问题 P1 | 已有 130% 截图路径，但没有 200% 全场景布局证明；设备读屏焦点与键盘遮挡测试不完整。 | app/src/androidTest/java/com/labteto/dshmobile/ui/ZhaoxiAcceptanceAndroidTest.kt:82 |
| 50 | 整合 HEAD 的完整 CI/设备闭环 | 未通过验收 | PR 当前 HEAD `fe72d6284d44`，运行 #39115459918 pending/未完成；不得用旧 SHA 成功代替。 | https://github.com/sy220284/777/actions |

## 结论与证据边界

- 已执行 **50 个不同的静态/流程/视觉验收专项检查**，每项有仓库文件或 CI 证据位置。统计：静态通过 24项；问题 P1 12项；待交互验收 1项；部分验证 1项；问题 P2 7项；待核对 1项；待实机 1项；待验证 1项；待测量 1项；未通过验收 1项。这些结果不是 50 次完整 Android 端到端运行；视觉截图、真实支付/授权、长任务时序和权限回跳都必须另行实测。
- 优先修复：P1（运行/安全/无障碍与用户路径）优先于 P2（文案/输入/空态）。待实测不当作“已通过”，静态通过仅证明源码具备相应结构。
- 最新 HEAD fe72d6284d442b769a0f715c11ada751dd9a4e3e；本报告会随其他贡献者继续提交而变旧，处理时应先对比 PR 的最新 SHA、CI 与本报告锚点。
- E8.18 原始设计 ZIP 在当前工作空间中实际可用，已直接读取 `朝夕UI设计系统-E8.18.zip` 的 `朝夕UI设计规范.md`、`prototype.html`、`tokens/tokens.json`、`背景素材/README.md` 及 10 张背景素材索引，并进行下列**设计条款与 Compose 结构对照**。未取得本次 HEAD 的相应实机截图，故不能宣称完成像素级对拍。

## 补充：原始 E8.18 设计包逐条对照（不是额外虚报“第 51—60 轮”）

来源：已存在当前工作空间的原始 `朝夕UI设计系统-E8.18.zip` 中 `朝夕UI设计规范.md`、`prototype.html`、`背景素材/README.md`。技能要求按实际设计稿检查，以下是**前 50 项审计的设计一致性深挖**，与前述问题计数有交叉，不相加计算。

| 设计对照重点 | 原设计稿要求 | Android PR #746 当前事实 | 判定 |
|---|---|---|---|
| 背景系统 | 附录 O.4/O.7：设置→外观→壁纸 Sheet；5 hue × 明暗 10 张；导入/主题默认/无；缩放/位置/模糊/遮罩/范围/自适应对比度 | `ZhaoxiAppearanceScene` 仅能调主题、字号、环境光、透明度、动效；树状清单无 10 张 PNG 或对应壁纸资源；非纯视觉小偏差 | **P1：整套交互未接入** |
| Agent 审批模式 IA | 附录 F.2/F.3：Agent 与安全组须有**独立审批模式页**；权限页只管 Android 设备授权 | 当前审批模式仍在 `settings.permissions` 由 `ZhaoxiApprovalModeSelector` 内联显示；设置根 Agent 组仅“权限与设备”一项 | **P1：页面分区错误** |
| 设置更新 | 附录 F.2/F.4：数据与系统组“检查更新”**页内即时**而非新增一级子页 | 设置首页 `ZxNavRow("检查更新")` 先导航到 `settings.diagnostics`；与明确冻结的操作路径不符 | **P2：多一层导航** |
| 人物图集 | §7 页 5：圆形立绘 + 人物名 + 亲密度 + 双列网格 | `ZxPersonaCard` 使用首字母圆形头像及故事段数，当前卡无真实立绘/亲密度投影 | **P1：主要视觉信息缺失** |
| 人物编辑 | §7 页 6：头像区域 + **统计行** + 四页签 + 底部操作 | `ZhaoxiAuthoringForm` 已有四页签、简化头像与身份行，但未发现对应统计行和真实画像区块 | **P2：结构差距** |
| 工作运行详情 | §7 页 10、组件 8.10/8.11：进度环标题 → 事件序列 → 工具证据卡 → 需求关联 → 操作 chips | `ZhaoxiPlanReviewScene` 目前以步骤行+待操作入口为主，`ZhaoxiRequirementEvidenceScene` 显示需求/文件字段；进度环与事件时间线没有组成设计上的工作详情页 | **P1：关键内容结构缺失** |
| 自动化编辑 | §7 页 12：名称/任务域双选/内容/8 类调度/通知/**失败策略** | `ZhaoxiAutomationEditorScene` 从上下文推断 CHAT/WORK；选项为单次/间隔/每天/每周/每月/沉默/时段最多 7 类，未发现独立失败策略编辑控件 | **P1：表单功能缺口** |
| 添加 MCP | §7 页 14：**连接方式双选**→对应表单→连接 | `ZhaoxiToolsScene("tools.mcp")` 目前只有“连接方式 · HTTP”与服务器名称/HTTP 地址输入 | **P2：设计入口未完整**；是否能支持第二方式要查 Owner |
| 设置首页密度 | 附录 F.4：每组 ≤4 行；四组为冻结设计 | “对话与体验”包含外观、聊天风格、个人规则、记忆、通知共 **5 行**；Agent 与安全仅 1 行 | **P2：与冻结栅格不符** |
| 设计规范页数 | §7 写“26 页”，表中编号实际只列 **1–25**（其余在段落里说明标准设置页） | 应以精确列表+附属页为验收范围，不宜凭“26 页”写成 26 个精确可枚举画板 | **设计资料内部计数待澄清** |

### 设计包与技能验收差异

- 原包 §4.3 与附录 D4 还冻结了按钮/人物卡/顶栏按压幅度（.96/.92 等）、列表 40–60ms 逐项错峰、模式切换 .28s、可打断的 Sheet .32s；当前公共组件只有部分压感与统一 ZhaoxiMotion，**不能**据此推断全部实现了页面级动效编排。
- 原包 `tokens/对比度验算报告.md` 给出**原设计色板**的 WCAG 数值，不能取代现在 Compose 多主题、内容覆盖、用户背景壁纸下的实时对比度测试。
- 原型是 HTML 交互参考；其模拟成功、数字和占位立绘不能直接作为 Android Owner 已接通的证据。
