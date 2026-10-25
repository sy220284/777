# PR #864 — Codex × Claude × 777 多代理重构统一任务与验收

> **唯一交付 PR：[#864](https://github.com/sy220284/777/pull/864)**。本文件将先前误放入 Issue #873 的待办完整迁回当前 PR 分支；**不是新 PR、不是任务已完成证明，也不建立新的执行/持久化系统**。
>
> 记录：Issue #873 仅包含文本核对清单，无源码提交，因此无需 cherry-pick。代码只以 PR #864 当前分支事实与当前 HEAD CI 为准。
>
> 原始蓝图：A01–A24（24 项能力）、D01–D18（18 项契约）、E00–E14（15 阶段）及 84 个验收场景。三个编号体系存在交叉，不能相加作为独立剩余开发项。
>
> 参考：[全域审计](agent-graph-expanded-alignment-pr864-20261024.md) · [源码迁移映射](agent-runtime-codex-claude-source-migration-20261025.md)。遵循 777 唯一 SessionEventLog / Job Inbox / RunOwnership / ToolAttemptLedger 事实源；Claude 历史第三方还原资料只用于行为对照，不能直接移植非许可源码。

## 未闭环清单（迁回自 #873，全部继续跟踪）

## 10 组交付包（均未标记完成，须逐条提供代码和运行证据）
- [ ] **U01 身份与生命周期**（A01/A02/A03/A18；D01/D02/D08/D17；E02/E06）：统一 Agent Registry/树 path/root/parent/generation；PendingSpawn、确保直属父 Run 的 ensure_child_loaded；冷/热启动、撤权、软中断、watch/resume/close、挂起/退休；Team/Fork/后台/普通同一事实源。要求跨代撤销不能重放旧效果。
- [ ] **U02 专业角色/技能/上下文/记忆**（A04/A05/A09/A10/A11；D03/D04/D05/D06/D07；E03/E04）：自定义角色注册、不可增权的授权交集、SKILL.md 指纹版本锁定、过滤 Fork/ArtifactOnly/ScopedSummary、根指令 revision 与 provenance、脱敏/TTL/纠错。
- [ ] **U03 动态规划与混合调度**（A06/A17；D07/D11；E05/E11）：从 Goal 分解 DAG + Team 任务，依赖、持久重规划、幂等接管、可靠调度、撤销/超时/资源预算和跨代理真实结果。
- [ ] **U04 消息协议**（A07/A08；D09/D10；E07）：QueueOnly/TriggerTurn、accepted/claimed/model-submitted/model-replied/member-acked、跨代理投递、转交、断线补偿、分型 shutdown approve/reject、无人可伪造目标。
- [ ] **U05 验证与成果合并**（A12/A13；D14/D15/D16；E08）：独立审核记录、客观测试 exitCode/工件 SHA、冲突/版本/代码审查、三方来源可重放、单一 IntegrationDecision → Goal admission；禁止模型输出替代实测。
- [ ] **U06 强权限/工作区隔离**（A14/A15；D13；E09/E10）：执行前后根授权、ToolRegistry 实际权限、文件路径/符号链接/命令与设备范围；真实每代理隔离空间/worktree、写入隔离、基线 diff、回滚和冲突 merge gate。仅 advisory 和路径静态检查不可通过。
- [ ] **U07 多模型/资源/质量调度**（A16/A17；D11/D18；E11）：显式模型绑定、Token/成本/延迟/P95/吞吐/公平性、抢占/熔断、资源占满对比基准，无不可回收租约。
- [ ] **U08 故障/迁移恢复**（A18/A19；D17；E12）：实际 Android 16/17 的 SIGKILL、OOM、断网、审批撤销、未知副作用、已保存与未保存状态、旧安装覆盖升级、跨进程/跨版本恢复、重复提交/Inbox 水位专项矩阵。
- [ ] **U09 多执行后端**（A20；D12；E13）：真实 isolated-process / trusted-remote 执行器、设备端认证、用户明示授权、租约 fencing、失败未知态与拒绝重放，禁止隐式上传凭据。当前只有 in_process 可执行，不可将协议类冒充远端可用。
- [ ] **U10 UI/基准/自优化**（A21–A24；D18；E00/E14）：真实拓扑/Run/Job/Inbox/证据与历史缺口、人工暂停/撤权/冲突处理、84 场景逐项录入，Codex/Claude/777 **相同任务**的可重现质量/延迟/成本差分；自优化仅在基线通过后灰度、回滚。

## 立即阻塞与工序
- [ ] B01 当前 HEAD 全量 CI（scope、static、architecture、Unit、ARM64、x86）通过。
- [ ] B02 Android 16/17 及 4 KB / 16 KB 四组合仪器真实执行，skipped/cancelled 不算通过。
- [ ] B03 `main` 基线比对/合入最新修复，不丢失本地 3.0/持久化兼容。
- [ ] B04 84 项原始编号与运行结果证据一一对照；无测试时状态保持未验证。
- [ ] B05 差分和主子运行质量达标后，逐文件检查真实 owner/单事实源及安全门禁；审查通过后方可考虑 Ready for Review。

## 状态定义
**未实现**：只有方案/接口/未来契约。
**部分实现**：生产入口/实际代码存在，但某些语义或目标配置不可执行。
**已实现待验收**：可执行且测试已提交，尚无当前 HEAD 的 CI + 设备/E2E 证明。
**完整验收**：绑定 commit SHA、具体 CI Job/设备日志、负例、旧版恢复和两项目对照，不将老 SHA 的通过移植过来。


## 对 PR #864 生产实现的增量核对（2026-10-25）

以下是代码证据，不等于 10 个工程包完整验收：

| 交付包 | 已有生产/测试线索 | 尚未闭环的关键门槛 |
| --- | --- | --- |
| U01 | `LocalAgentControlPort`、`LocalAgentRegistry`、`LocalAgentTreeProjection`、`LocalAgentResidencyProjection`；最近增加历史祖先路径、持久 Job parent/slot 映射 | 完整 PendingSpawn/多模式统一树与 generation，跨级取消 |
| U02 | `LocalAgentRolePolicy`、`LocalAgentSkillBinding`、`LocalSubagentContextPolicy`、`LocalInstructionScope` | 自定义 Profile 注册与持久版本、完整 ContextBundle 与来源撤销 |
| U03 | `LocalAgentTaskGraphController`、`LocalTaskGraphReplanPolicy`、`LocalTeamIdleTaskDispatch` | Goal 自动编排 + Team/TaskGraph 混合端到端结算 |
| U04 | `LocalTeamMessageBus`、`LocalTeamMessageReceipt`、`LocalAgentMessageDeliveryMode` | 所有代理类型统一接收/消费/终态/订阅协议 |
| U05 | `LocalTeamVerificationController`、`LocalWorkIntegrationDecision`、`LocalWorkDeliveryEvidence` | 多人同文件冲突处理 + 有实证的最终集成裁决 |
| U06 | `LocalAgentWorkspaceScopePolicy`、`LocalTeamToolExecutionAdmission`、`LocalToolExecutionCoordinator` | 真正独立 worktree、命令/设备范围及合并回滚 |
| U07 | `LocalAgentSubtreeBudget`、`LocalSubagentResourceAdmission`、模型路由 | 成本/P95/公平抢占实测、调度回滚 |
| U08 | `LocalPersistentJobRecoveryCoordinator`、`LocalSubagentContinuation`、TaskGraph 冷恢复护栏 | OOM/SIGKILL/跨版本/旧版迁移完整故障注入 |
| U09 | `LocalAgentExecutionBackend`、remote grant/receipt 类型 | 已提供未配置拒绝，真实隔离进程和认证远端执行器未建 |
| U10 | Agent Tree/Team UI、Android 递归测试 | 84 场景三方行为质量评测及人工干预与灰度验收 |

**目前：U01–U10 与 B01–B05 依旧保持未勾选。** 只有当前提交的 CI、Android 实测以及按 84 场景逐条追溯的结果才能变更最终完成状态。PR #864 保持 Draft；不得擅自合并。

## Issue #873 与 #864 的对账结论

- #873 是 **Issue（文本待办）**，不存在 #873 的 PR 或独立代码分支。
- #873 中需要迁移的交付标准已经全部纳入本文件，PR #864 描述同步链接本文件。
- 历次源代码修改继续保留在 `refactor/agent-control-plane-20261024`，不需要也不能从 #873 捏造可 cherry-pick 的提交。
- 关闭 #873 的理由是**重复跟踪载体已归并**，**不是功能已完成**。
