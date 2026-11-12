# 777 安卓原生 UI 阅读与交互优化审计

- 基线：`main@b383cc7`；实现分支：`feat/ui-reading-flow-accessibility-20261112`。
- 范围：真实的 `LocalConversation*`、`LocalHarnessMessageComponents`、`ShenyanFoundation` / `ShenyanMaterial` 等生产 Compose 文件；不修改未接入生产路由的 `ShenyanChat.kt` 和 `ShenyanWork.kt` 演示组件。
- 原则：保留 Chat/Work 会话所有权、发送／停止／Steer、审批、文件、模型调用逻辑，调整呈现与交互层级。

## 修改清单

1. **阅读文字**：独立 `linkInk`、`personaInk`、`actionInk` 语义色，统一计算正文与背景对比；支持亮、暗、哑光及中间亮度；壁纸材质采样也纳入链接和人物名称可读性判断。
2. **对话阅读**：Chat 助手正文全宽、去多层卡片边框与背景；历史消息点击展开完整操作，最新回复保留主要操作，维持长按二级操作与文本选择。
3. **信息层级**：Chat 顶栏聚焦人物与会话，行为调节进入人物面板；Work 顶栏展示会话标题和实际状态，包括执行步骤、等待授权、等待确认、异常与失败。
4. **Composer**：模型切换进入输入区展开行，移除横向藏功能设计；保留现有完整附件 Sheet、推理与计划动作、发送／停止及运行时 Steer。
5. **质感**：减轻 L1/L3 内容层模糊与叠加高光，保留 L2/L4 浮层玻璃、三主题和既有动态效果；主按钮实色化。
6. **触控与导航**：修正工具回执行和媒体模式选项的最小触控高度至 48dp；调整侧栏人物、群聊、日记、任务和工作区功能的优先级，原业务能力、既有深链与设置页面保留；将用量统计、MiniMax 套餐额度整理到“设置 → 模型与用量”，额度查询项按已配置模型能力显示。
7. **全页面补漏**：人物行为调节、群聊公告、用量报告、模型选择和两种外观选中标记也切换至可读文字/图标色，统一遵循主题色与正文角色分离原则。
8. **反馈**：主会话操作成功或失败通过既有 `SyToastHost` 统一呈现，降低系统原生 Toast 风格割裂。

## 真实验收

- 静态：`.github/scripts/check-ui-design-system-boundaries.py`、`sync-feature-tree.py --check`、`check-feature-tree-sync.py`，自动生成的功能索引须与此分支源码指纹一致。
- JVM：`ShenyanReadableInkTest` 覆盖三主题、全部用户强调色、中间亮度背景；其他 Kotlin 单元测试按 CI 全集执行。
- Compose 仪表：`V6SeamlessNavigationInteractionTest` 验证历史与最新回复操作入口；`MediaImageDetailInteractionTest` 验证媒体模式标签的至少 48dp 触控区域。
- 真机：320／360／393／412dp、应用字体 1.0／1.5、系统字号有效上限 2.0、三主题、复杂壁纸、软键盘、减少动效、TalkBack；Chat 发送／编辑／重试及 Work 运行／停止／Steer／审批／失败恢复。
- **完成判据**：以最新 Head 的 CI、构建、Android 16/17 仪表测试和真机截图/交互回执为准，不能以代码已存在或生成了索引代替运行通过。

## 维护约束

- UI 品牌填充色与正文可读颜色独立；未来新增色卡时验证 4.5:1 文本、3:1 非文字状态指示。
- 可点控件从 `SyMetric.touch` 获取 48dp，装饰行允许较紧凑。
- 生产页面、设计令牌与离线原型必须同步；功能索引由原生成器自动更新。
- UI 分支的功能树自动同步现由 `.github/workflows/v6-feature-index-refresh.yml` 以 `feat/ui-*` 分支模式复用，降低以后重复提交衍生文档的工作量。
