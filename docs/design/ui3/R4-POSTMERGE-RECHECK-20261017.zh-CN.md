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
