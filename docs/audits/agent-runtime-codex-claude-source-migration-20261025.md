# 777 主子代理运行内核：固定 Codex / Claude 参考源码到实装模块的迁移表

> 本文件随 PR #864 的实际迁移提交维护。它是**源码到实现的映射及未达标项记录**，不是 CI、Android、真实模型三方验收通过报告。
> 源码固定：Codex `codex-main-6.zip`、ZIP 注释 commit `e8aa7c1678caa9a368243d72bf0e9b7859d97f15`；Claude Code `claude-code-2.1.88-main.zip`（**第三方历史 sourcemap 还原材料，仅研究行为，禁止直接移植**）。参考文件来自两份用户提供的固定 ZIP，不假设等于当日上游最新源码。

## 迁移约束

1. 以 Codex `codex-rs/core/src/agent/api.rs:41-164` 的边界为主线：**身份解析 / 子代理接纳 / 消息投递 / Inbox 观察 / 当前执行轮次中断 / 成果通知**必须分离。接纳、送达、模型处理、成果核验不能合并为“成功”状态。
2. 以 Claude 2.1.88 `source/src/utils/swarm/inProcessRunner.ts`、`source/src/tasks/InProcessTeammateTask/InProcessTeammateTask.tsx`、`source/src/utils/teammateMailbox.ts`、`source/src/tools/SendMessageTool/SendMessageTool.ts` 的 Team 职责边界为参考。保持正常消息和 `shutdown_request/shutdown_response` 的不同协议。
3. 777 的 `AgentTurnEngine`、`SessionEventLog`、`RunOwnership`、`ToolAttemptLedger` 和持久 Job Inbox 继续作为**唯一运行和持久事实来源**；新类不创建第二套线程、会话、消息、结果或权限账本。
4. Codex 是源码级、受许可证约束的开源参考；Claude 历史还原包只做设计验证与测试语义对照，不按原文复制专有实现。所有迁移以 777 当前类型、生命周期与实际回归作准。

## 第一批已切换生产入口（本次结构性迁移）

| 源码实装参照 | 777 的新职责 owner | 替换与删除的旧实现 | 保留 / 尚未对齐 |
|---|---|---|---|
| Codex `agent/api.rs` `spawn()`；`agent/control/spawn_guard.rs` provisional child 边界 | `LocalAgentSpawnController.kt` | 从 `LocalAgentControlPort.kt` 移出前台只读、后台只读、Fork 创建 `when` 分支；`LocalWorkAgentControlRuntime.delegate` 只委托 | 已统一三条创建入口；Codex PendingSpawn 级别的接纳事务、回执与悬空清理尚未完全一致 |
| Codex `agent/api.rs` `resolve/interrupt/list`；`agent/control/api.rs` | `LocalAgentLifecycleController.kt` | 将 owner 校验、inspect/watch、软中断、永久关闭、指定恢复和状态游标从旧控制类删除；Port 只委托 | Run/Job 单源事实保留；根节点解析、已卸载子节点装载/容量准入/usage 还缺 |
| Codex `agent/api.rs::resolve` 与 `agent/control/api.rs` 身份解析 | `LocalAgentRegistry.kt` | 原 Lifecycle 和消息发送各自处理身份的路径统一为只读 Registry；`send_message` 现在不能将 Team Job 当普通 Agent 直接投递 | 当前为已有持久 Job 的 Session 内解析；跨会话、根路径、unloaded child 加载还未完成 |
| Claude `utils/swarm/inProcessRunner.ts`、`tasks/InProcessTeammateTask/InProcessTeammateTask.tsx` 生命周期与 Codex `agent/control/spawn_guard.rs` 接纳失败清理 | `LocalTeamMemberLifecycle.kt` | 从 `LocalAgentTeamRuntime.kt` 移出成员创建、启动、恢复、禁用、解散、招募、投影成员事件提交；保留真正 Work Job 的身份和恢复职责 | 尚未实现完整跨模式 PendingSpawn provisional ownership/跨后端统一注册事务 |
| Claude `utils/swarm/inProcessRunner.ts` Task 领取；Codex 受控任务执行准入 | `LocalTeamTaskBoard.kt` | 从 `LocalAgentTeamRuntime.kt` 移出任务创建、领取/释放/完成/重分配、依赖、范围冲突、任务展示、唯一持久 Team Task Event 写入 | 暂保留已有 advisory writeScopes；不声称已做执行端工作区沙箱 |
| Claude `tools/AgentTool/built-in/verificationAgent.ts` 独立验证行为；Codex `agent/control/completion.rs` 终态通知与结果验收分离 | `LocalTeamVerificationController.kt` | 从 Team Runtime 移出结果身份、终态检查点验证、独立 reviewer 记录与来源重验 | Reviewer 报告与证据绑定已分离；缺客观测试、工件 SHA、冲突集成和 Goal 机器结算 |
| Codex `agent/control/mailbox.rs` 保留 unread queue-only 消息；`agent/control/delivery.rs` QueueOnly/TriggerTurn；Claude `utils/teammateMailbox.ts` 与 `SendMessageTool.ts` | `LocalTeamMessageBus.kt` | 从 Team Runtime 移出 Team 消息排队、成员 ACK、结构化关闭回应、Lead/成员 Inbox 投递及冷恢复；Team Runtime 保留跨生命周期恢复顺序 | 当前 Team 投递仍复用原 durable Job Inbox；并不等价于 Codex 的纯内存 Mailboxes；全代理消息契约尚待统一 |
| Codex `agent/control/mailbox.rs` mailbox read/watch 与执行驻留解耦 | `LocalTeamResultMailbox.kt` | 从 Team Runtime 移出**成员终态结果**的分页扫描、游标、观察和等待 | 结果读取保持无副作用，且与输入 Inbox 两种事实严格区分 |

## 仍需替换的生产模块（不是“已重构完成”）

- **AgentRegistry + 树身份管理**：当前 `LocalAgentTreeProjection`、Team 成员表、Job 信息仍各有身份视角。参照 Codex `agent/api.rs::resolve/list/child_agent_paths/ensure_child_loaded` 继续迁移；不可直接用任意 Job label 当代理授权。
- **Team 成员生命周期**：本轮已把 create/start/spawn/disable/dismiss 及 provisioning/失败恢复迁入 `LocalTeamMemberLifecycle`，并通过 `LocalAgentRegistry` 收窄普通消息目标；尚未跨 Fork/前后台/Team 统一共享完整注册事务、subtree generation、Codex ensure_child_loaded。
- **TaskGraph/Team/后台代理混合调度**：现有 `LocalWorkSubagentRuntime`、`LocalPersistentJobRecoveryCoordinator`、Team idle dispatch 仍由各自模式路由。需统一 admission/parent-link、资源预算、用户授权 generation、错误/取消与存量恢复。
- **Result Integration → Goal**：目前 Model Reviewer 判定 `approve/reject` 有可追溯证据，但 `LocalWorkCompletionEvidence.kt` 和 `LocalWorkGoalDriver.kt` 还没有完整独立测试和工件合并闭环，不能把此判断当已验收。
- **Workspace 及设备执行强隔离**：Team 原语义仍 `workspace=shared`、`scope_enforcement=advisory_only`；应结合 Codex `core/src/tools/sandboxing.rs` 与 Claude `utils/worktree.ts` 的实际约束验证实现隔离、冲突检测和回滚。
- **多后端/完整差分评测**：当前尚未有完整 Android、冷恢复、84 场景 + Codex/Claude/777 同题运行的可重复基准。

## 本轮结构变更核对

- `LocalAgentTeamRuntime.kt`：迁移前约 2581 行；本轮截至此文件更新时约 1069 行。**减少代码集中度只能证明模块边界发生了变化，不能单凭行数判定语义等价或系统全部重构。**
- 新增且已接入真实调用的职责 owner：`LocalAgentRegistry`、`LocalAgentSpawnController`、`LocalAgentLifecycleController`、`LocalTeamMemberLifecycle`、`LocalTeamTaskBoard`、`LocalTeamMessageBus`、`LocalTeamResultMailbox`、`LocalTeamVerificationController`。
- Team Runtime 保留跨模块恢复顺序、idle 自动派单、Run/Team UI 投影、成员专属工具身份判定和授权栅栏；Session EventLog/Job Inbox/RunOwnership 未另设可变镜像。
- *仍需真实实现替换*：Codex PendingSpawn 接纳回滚与 ensure_child_loaded 的全树语义；TaskGraph/Team/非 Team 统一 delegation registry 和根授权 generation；客观验证→成果合并→Goal admission；Workspace ACL/worktree；后端/设备与完整三方差分。
- 最新 HEAD 的自动化测试还需完整完成，尤其是 Kotlin 编译、EventLog 冷重放、软中断/关闭、消息去重、Team 生命周期竞态与 Android 16/17。前一 HEAD 因 `Json` import 缺失导致 CI 编译失败，修复已纳入这次迁移分支，但不能推定迁移版 CI 通过。

## 迁移验证门槛

- 每一批必须满足：新模块成为真实调用 owner；原方法删除；EventLog/Job 的状态事实来源不变；旧 EventLog 和冷恢复能重放；新旧输入在差分测试中观察到相同的业务结果或有明确且安全的有意变更。
- 此次结构切分本身不意味着 CI 通过。最新 SHA 的 Kotlin 编译、单元、架构门禁、ARM64 与 Android 16/17 结果必须单独记录。
- 本表中的“已切换”指生产源码调用已调整，**“功能验收通过”以 CI 与 E2E 为准**。
