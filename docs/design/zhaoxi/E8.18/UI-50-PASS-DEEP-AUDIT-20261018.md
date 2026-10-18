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
