# PR #864：Codex × Claude × 777 全域扩展方案源码覆盖与语义核查

> 审计日期：2026-10-24。审计对象：`refactor/agent-control-plane-20261024`；扩展蓝图《777-Codex-Claude-多代理全域扩展蓝图-20261024.md》A01–A24、D01–D18、E00–E14，以及 N1–N6 与 84 项验收场景。此报告属于静态证据核查，不等于运行通过。
> 参考源码：Codex `codex-main-6.zip` 提交 `e8aa7c1678caa9a368243d72bf0e9b7859d97f15`；Claude 2.1.88 是历史第三方还原资料，只作为行为语义参照，不代表官方当前实现、不复制其代码。

## 方法与判定

- 读取并核对 PR 所有改动文件清单（本次起点为 65 文件）和关键新旧源码：`LocalAgentControlPort`、`LocalAgentControlPlane`、`LocalAgentTreeProjection`、`LocalSubagentRunner`、`LocalSubagentCapabilities`、`LocalAgentTeamRuntime/Reducer/EventCodec`、`LocalTeamMessageReceipt`、`LocalWorkGoalDriver`、`LocalWorkCompletionEvidence`、`LocalWorkTaskGraphRecovery`、`LocalWorkSubagentRuntime`、Tool Policy 与 Team UI。
- 此次不是全仓逐行、全部 84 场景跑通的审计；表中“未见实现”特指本次核验过的目标模块和 PR 改动，不能据此断言整个仓库绝无类似基础设施。
- 状态：**部分落地** = 可追踪生产代码但目标语义不全；**有基础** = 已有通用设施但未构成蓝图规定的多代理系统能力；**未落地** = 本次证据不足以支持目标能力已实现；**验收通过**只能由当前 SHA 的真实 CI/设备/E2E 结果确认。以下没有将任何项判定为“全量验收通过”。

## A01–A24 能力覆盖表

| 能力 | 本轮审计状态 | 可定位的代码证据 | 距完整语义的主要缺口 |
|---|---|---|---|
| A01 多级代理树 | 部分落地 | `LocalNestedAgentDispatcher.kt`、`LocalSubagentCapabilities.kt`、`LocalAgentTreeProjection.kt` | 仅前台只读最多 3 层；持久 Team/Fork/设备混合深度、generation 与全树路径控制不完整 |
| A02 混合拓扑 | 部分落地 | `LocalAgentTeamRuntime.kt`、`LocalWorkSubagentRuntime.kt` | Team、TaskGraph、同步树仍未形成统一混合任务身份、所有权与 DAG 结算 |
| A03 统一控制 API | 部分落地（本轮增强） | `LocalAgentControlPort.kt`、`LocalAgentControlPlane.kt` | 已有 delegate/list/tree/send/inspect/watch/interrupt/resume/close，并统一 Lead Team 入口；仍缺 Codex resolve/ensure_loaded/turn-admission/usage 与跨混合子树的统一事务 |
| A04 角色系统 | 部分落地 | `LocalAgentRolePolicy.kt` | 7 个静态 preset，有只读限权；缺需求清单中完整角色、角色注册及版本冻结 |
| A05 自定义代理与技能绑定 | 有基础 | `LocalAgentRolePolicy.kt`，既有技能工具 | 没有审计到版本化 AgentSkillBinding、角色+技能+grant 独立注册和生命周期 |
| A06 动态任务图 | 部分落地 | `LocalAgentTeamRuntime.kt` Task CRUD、`LocalWorkSubagentRuntime.kt` TaskGraph | 尚无 Goal 驱动的自动分解/重规划与多代理分支回收闭环 |
| A07 代理间事件协议 | 部分落地 | `LocalAgentMessageDeliveryMode.kt`、`LocalAgentTeamEventCodec.kt`、`LocalAgentInboxProjection.kt` | Team 消息和关闭有分型，跨所有代理的 canonical AgentMessageEnvelope 与通知订阅不全 |
| A08 可靠投递 | 部分落地 | `LocalTeamMessageReceipt.kt`、`LocalSubagentContinuation.kt`、`LocalAgentTeamRuntime.kt` | 已区分接纳、历史提交、模型回复及成员声明；非全后端通用 ACK，也不保证外部效果 exactly-once |
| A09 根指令约束 | 部分落地 | `LocalAgentTeamRuntime.requireCurrentRootAuthorization`、`LocalWorkTaskGraphPlan.kt` | Team USER revision 有运行前拦截；未形成全子树 scope/generation 撤销、上下文同步与全部后端栅栏 |
| A10 上下文策略矩阵 | 部分落地 | `LocalSubagentCapabilities.kt` 历史模式、`LocalSubagentRunner.kt` | 仅 isolated/inherit 等基础，缺 FilteredFork/ScopedSummary/ArtifactOnly/CacheFriendly 全矩阵 |
| A11 分层知识与记忆 | 有基础 | `LocalSubagentRunnerFactory.kt` 现有 Memory/Context | 未见带 TTL/provenance/冲突撤销的 Run-Agent-Team-Goal-Project 独立知识流 |
| A12 专业独立验证 | 有基础/部分落地 | `LocalWorkCompletionEvidence.kt`、`LocalWorkGoalDriver.kt`、Team 完成证据 | 已有机器 Goal 门禁与 Task 结果 checkpoint；缺独立 Verifier Record、反例/交叉审查失败影响 Goal 的完整链 |
| A13 成果合并器 | 有基础 | `LocalWorkSubagentRuntime.kt` TaskGraph result/output version | 未见多代理冲突合并裁决、基线 diff/review/merge 自动闸门 |
| A14 精细能力授权 | 部分落地 | `LocalSubagentCapabilities.kt`、`LocalToolPolicy.kt`、`LocalRecursiveReadOnlyToolScope.kt` | 有 tool allowlist 与受控角色，未形成按代理/任务的路径、命令、设备及外部服务强 grant 一致性 |
| A15 工作空间隔离 | 未落地 | `LocalAgentTeamRuntime.renderTaskView` 明确 `workspace=shared; scope_enforcement=advisory_only; file_isolation=none` | 无执行端硬路径 ACL 与受隔离 worktree/branch 合并闸门 |
| A16 多模型路由 | 部分落地 | `LocalAgentControlPlane.kt`、`LocalPersistentJobRecoveryCoordinator.kt` | 能选择/固定模型路由，未见按成本质量时延自动规划与完整路由实验 |
| A17 智能调度 | 部分落地 | `LocalTeamIdleTaskDispatch.kt`、`LocalAgentSubtreeBudget.kt`、ResourceScheduler | 依赖就绪/有界预算已存在；未达公平优先级、跨拓扑抢占/熔断自适应 |
| A18 运行与驻留分离 | 部分落地 | `LocalAgentTeamActivityProjection.kt`、`LocalPersistentJobRecoveryCoordinator.kt` | 有 idle/dormant/续跑；未覆盖所有 Backend 的卸载/加载协议 |
| A19 中断恢复 | 部分落地 | `LocalSubagentContinuation.kt`、`LocalPersistentJobRecoveryCoordinator.kt` | 有恢复 watermark、副作用审慎策略，缺完整 SIGKILL/OOM/设备异常/跨版本注入矩阵实测 |
| A20 多执行后端 | 未落地 | `LocalSubagentRunnerFactory.kt` 仍走本地执行组合 | 未见 LocalSandbox/RemoteTrusted 的真实分布式身份、租约、结果 reconcile |
| A21 实时可观测 | 部分落地 | `LocalAgentTreeProjection.kt`、`LocalAgentTeamUi.kt`、EventLog | 有树与成员事件视图，缺完整证据差分、全链跨度/花费追踪和历史缺口 UI |
| A22 人工协同 | 部分落地 | `LocalAgentTeamUi.kt` 的消息/中断/重派入口 | 缺完整授权撤销、指定验证、冲突审查、执行前用户签批全路径 |
| A23 可重复评测平台 | 未落地 | PR 新增单元/Android fixtures 是局部回归 | 未见冻结模型/成本/故障注入的完整 84 场景和三方同题真实 benchmark |
| A24 持续自优化 | 未落地 | 本次代理控制与 Team 变更未提供离线 AB/灰度闭环 | 未见可回滚的受约束策略学习—发布—基准验证全过程 |

## D01–D18 契约覆盖

| 契约 | 判定及证据 | 仍需完成 |
|---|---|---|
| D01 树身份 | 部分：`LocalAgentParentLink.kt`、run checkpoint | 完整 root/path/generation/version |
| D02 关系事件 | 部分：`LocalAgentTreeProjection.kt` 只读 Run 投影 | spawned/accepted/linked/detached/closed 完整可重放事件 |
| D03 角色 Profile | 部分：`LocalAgentRolePolicy.kt` 静态 v1 | 动态注册、版本冻结与 Output Contract |
| D04 技能绑定 | 未落地 | typed AgentSkillBinding/version/provenance |
| D05 根指令快照 | 部分：Team `rootUserRevision` | rootDirectiveSnapshot、范围、撤销对整树传播 |
| D06 ContextBundle | 部分：`LocalSubagentRunnerFactory.kt` 的历史/Context | 来源区段、预算、脱敏、可核查 provenance |
| D07 委派决策 | 未见完整 | 选择角色/预算/前置证据的记录型 decision |
| D08 Spawn 准入 | 部分：委派准入与 Job start | accepted/activated/parent ACK 丢失的幂等承诺 |
| D09 MessageEnvelope | 部分：Team typed message 与 trusted QueuedAgentInput | 跨所有代理的统一 event/wake/cursor |
| D10 MailboxReceipt | 部分：`LocalTeamMessageReceipt.kt` & Member ACK | 全子树通用 accepted/claimed/processed/acked，冷热同义验证 |
| D11 BudgetLease | 部分：`LocalAgentSubtreeBudget.kt` | 生命周期/TTL/树级租约回收 |
| D12 ExecutionBackend | 未落地 | 本地隔离进程/可信远程能力接口和真实实现 |
| D13 WorkspaceGrant | 未落地 | 执行层路径/命令/worktree 强 ACL |
| D14 EvidenceRef | 部分（本轮增强）：Team `completionResultId` + `completionEvidenceSequence`，新增只读 `team_task_evidence` 冷恢复关联复核，TaskGraph outputs | 仍缺内容级工件 SHA、测试 exitCode、独立 reviewer 与 Goal 事实引用一致性 |
| D15 IntegrationDecision | 未落地 | approved/rejected/conflicted/review Gate |
| D16 VerificationRecord | 有基础但未落地完整契约：Goal 机器接受 vs 独立审查 | 证据+verifier 身份+反例+判决 |
| D17 LifecycleSnapshot | 部分：Run/Job/Team 多个投影 | 统一 admitted→dormant→unverifiable→terminal 执行后端状态 |
| D18 TelemetryTrace | 部分：EventLog/usage 与 UI | 全链 trace、token/latency/cost/wait reason 结构化联接 |

## E00–E14 Wave 进度审查

| Wave | 状态 | 审计结论 |
|---|---|---|
| E00 | 部分 | 资料快照和源文件映射有；没有实测三方 fixture 基线与成本指标 |
| E01 | 代码修复存在、待验证 | Team fork allowMutation 与冷热 Inbox modelMessage 修复已有代码；以最新 CI/Android 为准 |
| E02 | 部分（本轮增强） | Port 已接入 Lead Team 控制和 inspect/watch/pause/resume/close；完整 resolve/准入、跨树身份和统一结果 Envelope 仍缺 |
| E03 | 部分 | 静态角色+有限 allowlist，Agent 技能绑定/完整 grant 缺 |
| E04 | 部分 | Team 根用户版本可在模型/工具边界检查；非全子树 Context+Memory 版本撤销 |
| E05 | 部分 | 已有 Goal、TaskGraph、Team，但动态统一派单到 Goal 的集成闭环缺 |
| E06 | 部分 | 受控 3 层前台只读递归存在，混合写/Team/后台递归缺 |
| E07 | 部分 | durable mailbox、idempotent delivery、member ACK、idle wake 有，跨拓扑完整 watch 缺 |
| E08 | 部分 | 本次持久固定 Team result_id+checkpoint seq；独立 verifier、冲突裁决与 Goal 验证闭环仍缺 |
| E09 | 部分 | ToolPolicy/ToolGrant 受控，路径/设备强 grant 尚不全 |
| E10 | 未完成 | 工作区硬隔离/worktree/提交合并闸门缺 |
| E11 | 部分 | 路由选择/资源配额/idle 调度已有，成本、QoS、P95/公平性基准缺 |
| E12 | 部分 | 续跑与检查点已实现，完整崩溃矩阵未实测 |
| E13 | 未完成 | 多后端与远程分布式协议缺 |
| E14 | 部分 | UI/手工操作基础已有；三方 Bench、自优化灰度和回滚闭环缺 |

## 六类网络 / 84 场景的语义判定

- N1 同步主子：已有；N2 持久异步：部分；N3 长驻 Team：部分；N4 多级专家：仅受限只读；N5 DAG：已有 TaskGraph 基础但无法声称全域自动验收；N6 人工/设备/远程：部分人工/设备基础，远程未落地。
- 原有 24 + 新增 60 = 84 个场景是蓝图要求，不是自动意味着 PR 已新增 84 个逐项覆盖测试。当前 PR 新增针对单元和 Android 递归 fixture，但没有观察到按 84 编号逐条验收的真实三方报告。

## 本轮 E08 证据指针增量（不冒充独立核验）

- `LocalAgentTeamRuntime.kt`: `complete` 严格检查 owner/job、结果 ID 与任务 revision 的先后关系，再将确切 `result_id` 及 `LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT.sequence` 写进完成的任务事件；`hasFreshTaskResult` 复用返回事件的同一个查询。
- `LocalAgentTeamEventCodec.kt`: 新字段可选解码，旧任务记录不被伪造为有证据；`LocalAgentTeamReducer.kt` 约束字段成对出现、已完成态不可擅改；Team 任务工具输出与 UI projection 返回证据指针。
- 有单元测试覆盖工作任务完成/恢复、证据对齐、非法字段组合和旧版事件；但 **这个 result checkpoint 属于原执行者的产出证据，不是独立 reviewer 的验收纪录，也不是 Goal 完成判决**。

## 仍需优先推进的 P0/P1

1. **P0 运行验收**：本 HEAD 全部 CI、ARM64、Android 16/17、分页矩阵完成；对失败日志逐条修复。分支目前还须核对与新 main 的同步。
2. **P0 安全/语义**：Team/普通代理/Fork/TaskGraph 的根授权及 cancellation 全链版本栅栏，尤其历史 legacy 和失主子树边界。
3. **P0 独立验证**：Team EvidenceRef → 独立 VerifierRecord（测试回执、文件 SHA、反例）→ IntegrationDecision → Goal 机器验收；严禁仅模型自报成功就 settle。
4. **P1 工作区隔离**：从 advisory writeScopes 到执行端路径 ACL → per-agent worktree → 冲突合并门禁。
5. **P1 控制/混合拓扑**：统一 AgentControl watch/resume/close/inspect，Team/后台可恢复多级委派的授权与资源规则。
6. **P1 完整性能/恢复/平台**：Android OOM/SIGKILL/跨版本注入、模型成本/延迟评测、D12 可信后端协议和用户审批；后端/评测/自优化属于蓝图不可删目标，但不能声称已实现。

## 严格结论

**截至本次审计，A01–A24 没有任何一项获得“完整运行语义、全量计划范围、所有验收场景均通过”的证明。代码上多项有实质部分实现，A15/A20/A23/A24 等目标尤其明显未闭环；D01–D18、E00–E14 亦未全对齐。不能据此宣称 777 现在已达到完整 Codex/Claude 的执行效果。**


## 2026-10-24 功能重构续推：统一 AgentControl 生命周期 + Team 证据重验

- 新增模型工具 `agent_inspect`（源 Job 快照）、`agent_watch`（有界观察游标）、`agent_resume`（选定 Agent 安全恢复）、`agent_close`（永久取消），`interrupt_agent` 改为只中断可续跑 Agent Activation、保留身份与 Inbox。控制面通过单一 `LocalAgentControlPort` 接入 Lead Team 指令；Team 成员继续受自己的来源身份与事件投影管理，不允许普通 Agent 关闭命令绕过 Team。
- `LocalPersistentJobRecoveryCoordinator` 使用 `pendingTargetedAgents` 精确限制显式恢复 Job ID，普通会话恢复逻辑保留；模型工具层 `agent_resume` 仅允许当前会话已有持久只读代理、有效暂停态和消息/安全执行片。回执仅声明恢复已请求，不声称模型已启动或完成。
- `LocalAgentLifecycleControlTest` 验证会话所有权、非代理拒绝、不同恢复状态、Job 实际中断与永久关闭的 Inbox 行为和定向恢复筛选；工具策略同时登记读观察与需审批的生命周期变更。
- 新增只读 `team_task_evidence`：从原 Session EventLog 以持久 `result_id`、checkpoint sequence、owner job、任务 in_progress 事实做关联复核，明确 `content_verified=false`、`independent_reviewed=false`、`goal_accepted=false`。附带真实、缺失及伪造证据的回归测试。无新消息/成果事实源。
- 这些代码增强了**功能覆盖**，并不自动满足所有 Codex/Claude 生命周期与独立验证语义。剩余必做：统一 resolve/admission/ensure_loaded、全树父子安全收敛、多拓扑可写组合、真正独立 Verifier/IntegrationDecision/Goal、强工作区隔离、多后端和 84 场景验收。
- 本次静态核查及提交记录不能替代最新 SHA 的 Kotlin/Android CI；**PR 保留 Draft、禁止未验证合并**。
