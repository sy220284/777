# 777 主线 #705/#733 合并后问题复核与处置（2026-10-17）

**固定代码基线：** `ef8ce1df8beee4d41824beb27954cd271b141be5`（`main`）。以 [架构 3.0](../../ARCHITECTURE.md)、[UI 3.0 最高规范](../UI-3.0.zh-CN.md)、[R4 台账](R4-CODE-TASKS.json)、[V3 台账](../../agent-engine4/PR705-KOTLIN-REBUILD-TASKS.json)、[UI 场景矩阵](../../UI-SCENE-MATRIX.json) 为依据。

## 复核纠偏与本 PR 已处理项

| 原审计条目 | 复核事实 | 本 PR 动作 | 验收边界 |
|---|---|---|---|
| V6 独立渲染器 | 已有 75 个真实注册与 `V6FullSceneCoverageTest` 覆盖检查，原审计不能当作「尚未迁移」 | 为 75 个非 Host Scene 增加独立注册的具名代码及测试来源；三处系统服务明确不冒充 UI Pack | 注册通过只证明场景覆盖，不代表按钮及恢复行为通过 |
| 173 动作签收 | `UI-SCENE-MATRIX.json` 仍全部 `PENDING_EVIDENCE`；不得批量变 `VERIFIED` | 强化门禁：具名源码测试、固定提交/CI 标识校验；建立现有发送/拒绝的局部证据 | 173 项仍待完整 Owner→Host→Pack 回执与重启验证 |
| 历史 Feature 文档 | `FEATURE-OWNERSHIP` 含退役 Project/RunCenter 所有权 | 明确归档语义和最新六模块七路由权威 | 无产品路由变更 |
| Android 16/17 | 以基线提交的 Actions 终态为准 | 持续以当前 Head 核对、不复用旧 CI | 本 PR 新提交必须有匹配 SHA 的成功证据 |

## 已确认但本 PR 不伪报解决的缺口

**UI 主题所有权：** `AppRoot` 仍在 Host 外装配 `DshTheme`，其背景图片、阅读缩放、主题、Sy 材质和动效目前相互联动。需要整条视觉链在同一个重构切片内下沉到 UI Pack，保持现有背景/自定义强调色/字体/动态效果的实际表现，再用 AppRoot 禁止 V6 主题调用的反向检查验收。本次只做精准追踪，**未删除现有主题、未宣称主题隔离完成**，避免无源码编译条件下导致现行 V6 回归。

**Agent Engine V3 的 24 项：** 当前权威台账 `AE4V3-01..24` 全部 `in_progress`。已有 `AgentTurnEngine` / `ToolAttemptLedger` 等已接入生产的实现，但 TaskGraph、Goal 验收、取消/恢复、Model/Tool/MCP/Automation 和旧消费者收敛尚无全部最终证据。本次不改旧任务状态、不用 PR 合并状态代替逐项闭环。按台账依赖从当前 Owner 生产消费者、旧执行路径清理、真正的 JVM 反例和恢复验证开始推进。

**S1-10 / S2-01 / S4-09：** 此三项专项仍仅部分覆盖；必须对 173 项按用户触发、真实 Owner 回执、拒绝/撤销、跨会话迟到、冷启动与可见结果逐条核查，按真实测试绑定标记。一个具名 JVM 单测只能说明其断言范围。

## 验证方法与禁止假签收

- 必须执行 `python3 .github/scripts/check-ui-r4-scene-matrix.py --self-test` 及正常检查、完整当前 PR 静态和架构门禁、相关 JVM 测试。
- 人工或自动声称 `VERIFIED` 时，至少必须提供 Owner Port/Receipt、明确 `#方法名` 的恢复测试、40 位提交 SHA 和对应 CI Run ID；存在测试方法本身不证明断言充足，还须复核其真实内容与运行结果。
- 在 `main` 未完成全量设备测试前不得引用旧 SHA 的绿色 CI 作为新 PR 结果。
- 禁止为通过检查降低架构和依赖验证门槛，不恢复退役入口，不新增第二业务事实源。

## 2026-10-17 最新主线设备失败复核与本 PR 修复

主线 CI [#39020211827](https://github.com/sy220284/777/actions/runs/39020211827) 已结束：Android 16/17 均失败，因此 merge-gate 为失败；静态、架构、JVM、ARM64/x86 构建通过。不得把发行工作流单独成功认作设备门禁放行。

- **ManagedProcessAndroidTest：** 真实超时运行结果为 `超时：true`、`TOOL_TIMEOUT`，旧测试误从人类输出里查 `[TOOL_TIMEOUT]`。改为直接断言 `ToolResult.isError/errorCode`，仍验证子进程未在超时后写入。
- **LocalColdStartupRegressionTest：** 真正 V6 输入框已渲染，但测试仍定位旧 Shell 的 `SY_COMPOSER_FIELD_TAG`，始终等到超时。新 V6 输入框提供稳定标签，设备测试改定位新标签，保留真 Activity 冷启动验证。
- **ShenyanMotionInteractionTest：** 原 `graphicsLayer.translationY` 对某些 Compose 语义树的位置不可见，入场和减少动效的断言失败。入场改用偏移定位配合透明度层，保留原上移/淡入及减少动效直达终态；设备测试仍验证实际位置变化。
- **仍待进一步核实：** `V6AutomationContextInteractionTest.restartingEditorAfterSwitchBindsOnlyTheNewOwnerIdentity` 两台设备均等待新任务按钮超时；Android 17 `LocalChatTurnUsageTest` 另有一项超时。本 PR 不能因上述前三项代码调整就假报总门禁通过，需要针对新 Head 完整测试后继续修复。

## 2026-10-17 设备回归统一排查：交互文案与测试契约

- PR #734 的完整 [CI 39026818641](https://github.com/sy220284/777/actions/runs/39026818641)、子 PR #735 的 [CI 39027272452](https://github.com/sy220284/777/actions/runs/39027272452) 均在 Android 16/17 执行 312 项，各仅 2 项失败。均为 `LocalApplicationUiHostInstrumentedTest` 的 `invalidChoiceIsRejectedWithoutChangingTheV6RecoveryRule` 与 `corruptSavedPackHistoryRequiresExplicitRecovery`：测试寻找旧的 `R.string.ui_pack_back_to_stable`，但 #734 已改成“重试加载界面”。这四个设备作业属于**相同两项测试失败**，不是四个独立缺陷。旧动效、自动任务相关设备断言已在这组运行中通过。
- 本轮查阅 UI Host、设置导航及返回、自动任务编辑与未保存保护、会话冷启动、弹窗与工作入口、文档重试、插件入口等已有设备交互测试的断言与标签。此前 312 项中 310 项通过，仅可证明已覆盖的行为；未执行到的人工视觉操作与全部 173 个 UI 动作不可据此标记验证完成。未在已核查的其他测试中确认相同的旧 UI Host 资源键误用。
- **统一修复路径：** UI Host 的恢复提示、重试、退出提示均使用同一处 `R.string` 资源；两按钮建立稳定 Compose 语义标签，测试分别检查当前可见文案和真实点击目标，继续验证损坏快照恢复到 Home、无效 Pack 不进入旧 Shell 且持久化选择回到 V6。错误提示改为同时适用“包缺失”和“导航快照损坏”的中性描述，避免误导。
- 必须按**修复后新提交 SHA** 重跑 Android 16、Android 17 和 merge-gate，必要时修复重试 Activity 重建与 Compose 测试生命周期不一致问题。不得将源代码修补、编译成功或旧 310/312 当作设备签收；不关闭失败测试、不下调门禁。

## 2026-10-17 后续代码修复与真实设备阻断（PR #736）

- #735 的 Android16/17 作业运行 312 项测试，均有 1 项失败：`LocalApplicationUiHostInstrumentedTest.invalidChoiceIsRejectedWithoutChangingTheV6RecoveryRule`。Android17 失败堆栈在测试第 157 行找不到 Compose hierarchy；根因是失效 UI 包「重试」分支调用 `activity.recreate()`，测试活动重建后并不会自动重新安装 `createComposeRule.setContent`。
- #736 的源代码修复将失效包重试改为 **仅在 selected_pack 写入成功后更新宿主选包 Compose 状态**，不重建 Activity；同一界面树中的原 Owner、Host 及其他状态不被无故销毁。仍需 Android16/17 当前 SHA 的真实设备运行成功才能关闭缺口。
- 中立 `AppRoot` → `LocalProductionUiEntry` → `LocalApplicationUiHost` → `V6ApplicationScenePack.SceneFrame` 是新的装配顺序。V6 现行主题参数保持一致，但主题实现尚未物理移动到 V6 模块，后续需要全量字体/壁纸/动效的等价回归。
- 173 项用户动作的正式端到端/冷恢复签收维持 **0/173**，不得由 312 项 Android 仪表通过（若以后通过）推导全部动作已验收。
