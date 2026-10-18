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
