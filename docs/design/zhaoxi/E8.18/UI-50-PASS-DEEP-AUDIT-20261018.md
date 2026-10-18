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
