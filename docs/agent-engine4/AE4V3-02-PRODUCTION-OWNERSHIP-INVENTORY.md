# AE4V3-02：生产调用与事实所有权核查

检查基线：PR #705 `feat/ui-r4-pluggable`，HEAD `85c164f1aaa5db1e3f1892bdb17eab3d0d124c6e`（2026-10-17）。这是实际路径核验清单，并非全仓逐行审计或 CI 验收证明。

| Owner | 已核对的 Kotlin 入口 | 迁移决定与消费者 |
| --- | --- | --- |
| App Shell / ChatFeature 冻结域 | `docs/ARCHITECTURE.md` | 保留 V6 UI、人物、群聊和聊天域；共享契约要保持既有业务行为 |
| 启动 Kernel | `app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeKernel.kt` | 只持有进程生命周期与恢复触发，不接管 Run/Tool/Goal Owner |
| Shared Agent Turn/Step | `harness-core/src/main/kotlin/com/labteto/dshmobile/harness/agent/AgentTurnEngine.kt` | `LocalWorkAgentTurnExecutor.kt` / `LocalSubagentRunner.kt` 实际调用；旧 AgentLoop 已退出 |
| Session 事实流 | `harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/SessionEventLog.kt`、`app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionEventLog.kt` | Checkpoint/UI 只作为投影，后续统一冷恢复及写入租约 |
| Tool Attempt | `harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/ToolAttemptLedger.kt`、`app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt` | 统一声明、真实准入、结果不确定及身份核销 |
| Model Request | `app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt` | 需要逐项审计 Provider、Request Admission、冻结路由、Chat/Work 共用边界 |
| Work Goal/Verifier | `app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkGoalContinuation.kt`、`LocalWorkCompletionEvidence.kt` | Work 唯一目标与验收 Owner，TurnCompleted 不能代替 GoalCompleted |
| Work Run Owner | `app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunRegistry.kt` | 待继续检查取消、交接、恢复栅栏，不得建立重复执行入口 |
| TaskGraph | `harness-core/src/main/kotlin/com/labteto/dshmobile/harness/workflow/TaskGraphExecutor.kt` | Work `LocalWorkflowCoordinator.kt`、`LocalWorkSubagentRuntime.kt` 已调用；旧 WorkflowRunner 退出 |
| Agent Team | `app/src/main/java/com/labteto/dshmobile/local/work/LocalAgentTeamRuntime.kt`、`LocalAgentTeamReducer.kt` | 与 Shared DAG 共用拓扑，持久团队任务及结果 Owner 后续整合 |
| Automation | `app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomation.kt` | 逐条核对 Shared Run/Tool Lease 的唯一消费者，避免平行账本 |
| Android / MCP / LSP / Device | `harness-runtime-android/`、`harness-interop/`、`harness-device-android/` | 作为 Kotlin 受托 Provider，不承担 Goal 状态写入 |

## AE4V3-03 真实运行行为基准

`reference-validation/src/test/kotlin/com/labteto/dshmobile/reference/AgentEngine4ExecutionBaselineTest.kt` 是执行中的 JVM 合同：同一测试贯穿 AgentTurnEngine → ToolAttemptLedger → SessionEventLog 冷重启 → TaskGraph 失败/阻塞传递 → Goal 验收状态。并检查后续成功工具不能掩盖此前未结算的外部副作用。该测试为本地产品要求，独立于 `ReferenceConformanceTest` 的官方锁定 Golden。

AE4V3-01 保护域与本清单生产入口的核查持续进行；02/03 在当前 SHA CI 全部通过、生产关联与退出条件复核前不得标记 `completed`。下一步按 01→02→03→04 顺序复查，不以文档数量计完成。
