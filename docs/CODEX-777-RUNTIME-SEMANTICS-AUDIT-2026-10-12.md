# Codex × 777：运行语义与代码级差异复审（2026-10-12）

> 对照基线：777 `main` 于本次审计启动时为 `e21c07ae09a44ba0df4a22e496a078b67dc49fd4`；上游 Codex `openai/codex` 于同日为 `ddb33e34b278ca1b7d16d1e68f8ad363a2042f2e`，并与用户此前提交的 `codex-main-3.zip` 中的对应源码交叉复核。
> **不是**功能名称清单；只记录具有对应运行代码的结论。Codex 是桌面/CLI Rust 引擎，777 是 Android Kotlin App；“相同目标，不同宿主”不应强行复制实现。

## 0. 范围与判断标准

逐项审计「入口与所有权 → 接纳/授权 → 模型/工具执行 → 事件证据 → 取消/恢复 → 用户可见交付」；**已经实现/部分实现/缺失**指代码证据，不等同于真机体验认证。

| 维度 | Codex 主要依据 | 777 主要依据 | 审计结论与剩余差距 |
| --- | --- | --- | --- |
| Turn/运行所有权 | `codex-rs/core/src/session/`、`state/`、`agent/control/spawn.rs` | `local/runtime/LocalAgentRunCoordinator.kt`、`local/work/LocalWorkRunRegistry.kt` | **核心已对齐**：每个会话/Turn 的执行与持久检查点有明确 owner；777 的 Work 绑定只应作为单一工作流投影，不再另造 owner。 |
| 运行中输入/Steer | `session/input_queue.rs` 区分 `TurnInput` 的用户输入、工具输出及 Agent 通信；watch Steer/Mailbox | `harness/agent/AgentInputQueue.kt`、`LocalWorkModelSteering.kt`、`LocalWorkRunRegistry.kt` | **部分对齐**：777 已持久化排队与取消当前模型采样；Codex 的类型化输入来源/接纳顺序及独立 mailbox 语义更细。不可把取消模型误认为回滚工具。 |
| 工具并行与授权 | `tools/parallel.rs` 使用工具声明的并行能力，读/写锁在执行准入时获取，并有取消终态语义 | `LocalWorkspaceReadWriteGate.kt`、`LocalToolExecutionCoordinator.kt` | **核心对齐且范围不同**：777 在共享 workspace 上统一读写 gate，已等待人工审批后获取；仅审核通过的本地纯读工具并行。Codex 有每种工具单独的 `finishes_on_cancellation` 标记；777 的跨插件终态证据仍要加强。 |
| ToolCall 身份与结果 | Codex `tools/router.rs`、`tools/parallel.rs` 保留 call-id 与执行/产物状态 | `LocalWorkHistoryCheckpoint.kt` v5+、`LocalWorkToolResultRuntime.kt`、`LocalToolExecutionCoordinator.kt` | **核心对齐**：结构化工具 ID 和副作用不确定状态已实现；检查点人类摘要不可替代真实事件证据。 |
| 子代理 History Fork | `agent/control/spawn.rs::keep_forked_rollout_item` 明确过滤父级工具调用/结果、隐藏推理和使用量，保留用户可见对话；`retain_forked_developer_message` 清理已过时动态授权提示 | `local/agent/LocalSubagentContextPolicy.kt` | **发现 P1 语义差距，本分支修复**：此前指定 parentCallId 但找不到时，会静默返回全部父历史；已改成明确拒绝。继承模型上下文从完整事件改为面向子代理的对话投影，排除旧 tool role、assistant tool_calls、reasoning/replay 私有字段。父级动态 developer 授权的来源类型仍需进一步显式建模，不能做脆弱的字符串过滤。 |
| 子代理模型/权限快照 | `agent/child_config.rs::build_agent_spawn_config` 依据当前 Step/Turn 快照刷新模型、reasoning、cwd 和 sandbox approval | `LocalSubagentRunnerFactory.kt`、`LocalSubagentRunner.kt`、`LocalSubagentCapabilities` | **部分对齐**：777 会按子代理运行读当前 model route/capability，并通过注册表审批，尚缺 Codex 级别的完整类型化 child runtime profile/cwd/sandbox snapshot。父对话中的“已批准”并非权限来源。 |
| Work/世界状态压缩 | `compact.rs`、`session/rollout_reconstruction.rs` 对压缩窗口、world-state baseline、替换历史有专门的恢复规则 | `LocalWorkHistoryCheckpoint.kt`、`LocalStructuredWorkStateProjection.kt`、`SessionEventLog.kt` | **部分对齐**：777 已有约束撤销、决策来源、工具 ID、Checkpoint，但 Work 事实投影与 Codex world-state 更新并非同构；避免旧规则复活需继续测试多次压缩/授权重新授予。 |
| 模型请求/重复计费 | Codex `client/`、`session/` 保存每一步请求配置、响应元数据并做恢复 | `LocalModelRequestCoordinator.kt`、`model/ModelAdmissionSafety.kt`、`LocalModelRequestAttemptPolicy.kt` | **核心安全语义已对齐**：777 已冻结路由、追踪 NOT_SENT/MAYBE_ADMITTED/ADMITTED，并对无法证明未发送的故障禁止盲目整请求重放；单次尝试证据独立可测。Coordinator 仍较庞大，但仅凭文件行数不构成拆分理由。 |
| 持久执行与恢复 | Codex `session/rollout_reconstruction.rs` 及 agent graph store，恢复不冒充当前 runtime | `LocalAgentRunCoordinator.kt`、`LocalPersistentJobRecoveryCoordinator.kt`、`PersistentTerminal.kt` | **部分对齐**：777 有事件高水位恢复、旧终端运行代次、未知工具副作用阻断；还缺全执行宿主（shell/MCP/虚拟屏）的统一 epoch 证明和真实设备重启回放。 |
| 独立工作树隔离 | Codex 子代理配置独立 `cwd`/sandbox/profile；运行时按调用边界生效 | `LocalSubagentRunnerFactory.kt` 固定 `workspacePath`，文件/Shell/MCP 工具共用执行根 | **未实现**：777 的 Team `write_scopes` 是协调提示，不是文件沙箱。单纯 git worktree 命令不会隔离工具；必须在所有工具路由、规范化 realpath、MCP 和后台任务恢复中强制对应 root，验收前不得宣传为“隔离”。 |

## 1. 关键状态转移对照

### A. 用户边执行边纠偏

Codex：`TurnInputQueue / InputQueue.watch(Steer)` → 活动 Turn 消费 → 中断或继续模型采样 → 工具依据 runtime cancellation policy 结算。

777：`LocalSendCoordinator` 明确标记 steer → `LocalWorkRunRegistry` 持久化输入并递增 revision → `sampleUntilWorkSteered` 取消模型采样 → 消费队列、重投影模型上下文 → 工具执行前复核 revision。

**不变量：** 用户纠偏不是对已产生副作用的工具回滚；新生成结果不能和旧代次工具调用混写。777 已在提交/审批后执行前复核；真实网络取消后供应商计费仍不确定，不能 UI 显示“已退款”。

### B. 工具调用

Codex：工具声明并发能力 → 准入/取消检查 → 读写锁 → 执行器 → ready/aborted/unknown 结果。

777：工具可见性及 Schema 预算 → 用户审批 → `LocalWorkspaceReadWriteGate` → 注册工具执行 → `tool/execution-started`、`tool/result` → 输出引用、恢复。

**不变量：** `read_only` 不能仅凭工具名称或自报标签推定无副作用；777 使用明确 `parallelReadSafe` opt-in 是合理差异。后续可考虑资源键/文件 scope，但必须先有真实命令执行边界而不是另建全局锁。

### C. fork 子代理

Codex：保留用户可见对话和可信 Turn 上下文，过滤工具事务/私有推理/过时动态开发者提示 → 构造子代理 runtime config（含权限与 cwd）→ 子代理独立执行。

777（本分支调整后）：给定 `parentCallId` 必须找到确切截点 → 历史只投影系统/用户/可见 assistant 对话，不携带 tool 消息、旧工具调用和推理私有字段 → 明确子代理身份和权限来源 → 独立工具准入。

**不变量：** 不能把父代理工具“曾被调用”或“曾被批准”投影成子代理的授权。若 fork 边界不见了，拒绝错误继承，不猜测最近位置。开发者上下文中需要动态许可标记时，应以**结构化来源标签**去除，不能全靠自然语言正则。

### D. 冷启动与长会话恢复

Codex：读取持久 rollout、最近有效 compaction 和 WorldState baseline，恢复已有上下文/线程关系。

777：事件日志扫描到固定 high-water mark，Checkpoint/Tool Result 还原；对缺页、未知副作用和运行代次不一致 fail closed，并保留可诊断原因。

**不变量：** 不得把“旧工具已经启动但结果未知”当作“从未启动”，也不得丢弃用户已经持久化但未消费的输入。

## 2. 优先级与不重复建设

| 优先级 | 结论 | 处理 |
| --- | --- | --- |
| P1 | fork 位置缺失静默继承全部父历史 | 本分支已修复，并补回归 |
| P1 | fork 将父级工具事务/私有推理直接带给子代理 | 本分支已为继承上下文增加投影过滤，保留单独的真实 tool output/event ledger |
| P1 | #660 的 Work 授权恢复和真实 ToolCall 证据仍在并行验收 | **不在本分支重复重写**，以已存在 #660 最新 CI/合并为准 |
| P2 | 子代理动态 developer / 审批来源需要类型化过滤 | 后续基于结构化 provenance 做，不盲目删文本 |
| P2 | 工具取消何时算终态、shell/MCP 各宿主的 epoch | 继续以真实事件和工具能力验证后扩展 |
| P2 | 超长会话投影性能与恢复重放 | 已有批量扫描与 high-water；需量化内存/磁盘/长时运行指标 |
| 可选 | 真正 per-agent worktree sandbox | 未达强制执行条件前**不提供假隔离** |
| 不做 | 用一个 777 模块对应一个 Codex Rust 文件、增加第二个 Goal/任务状态所有者 | 破坏现有 Android 架构与单一事件事实源，收益不足 |

## 3. 验收合同

本分支以 `LocalSubagentContextTest` 验证：无效 fork call-id 不能扩大继承范围；父级 tool/result、tool_calls、隐藏 reasoning/replay 字段不进入 child 模型输入；用户可见父对话与当前子任务仍保留。

最终合并需以**本分支最新 head** 的 Kotlin 单测、静态/架构、Android ARM64 与需要的仪表测试为准，不得拿此前 #651/#656/#658 的 CI 代替。本报告是静态源码语义对照，不代表实测出具体性能加速或完整平台沙箱隔离。

## 4. 执行器复审后的实装修复：注册身份、审批及资源锁三阶段一致性

Codex 的 `ToolCallRuntime` 绑定发起工具调用时的 `StepContext.tool_router` 快照，执行准入完成后才进入 handler；不能让一次工具审批悄悄授权新的处理器。

777 原来的 `LocalToolExecutionCoordinator.executeScoped` 先检查注册表中的工具权限、计划模式和 read-only 特征，`ToolRegistry.executeTracked` 再获取处理器。此间若工具被热替换，或者人工审批过程中插件被卸载，旧审批可能匹配上新的 handler，乃至继续执行已撤销的 handler。这属于**运行时 TOCTOU（检查与使用分离）漏洞**，不能仅靠工具名字或提示词解决。

本 PR 除 fork 语义外，现已增加以下**真正的执行器修复**：

- `ToolContext.expectedRegistration` 将权限预检时的原始注册对象交给真正的 registry，工具变动立即返回 `TOOL_REGISTRATION_CHANGED`、`executionStarted=false`。
- `ToolRegistry` 在用户审批返回后核实 handler 仍然属于原注册对象；如果审批过程中注销或替换工具，不会继续调用旧 handler，也不会把旧批准转借给替代工具。
- 在 Android `onExecutionStarted` 中，经过公平读写锁等待后，**写入持久的 execution-started 事件之前**再检查工具身份。资源等待期间热卸载也不会执行过期工具，不会错误声称已执行。
- 错误为明确的**未开始、无副作用、不可自动重试**，要求重新发现工具及核验权限。已经开始的工具执行沿原有事实和未知副作用恢复，不伪造撤销。
- `ToolRegistrationAdmissionTest` 覆盖审批期间卸载、替换及原始权限快照失效；`LocalToolExecutionCoordinatorTest` 覆盖审批阶段以及读写锁排队期间注册变动，无旧 handler 执行。

这与 Codex 的工具 handler/StepContext 快照目标对齐，同时保留 Android 单一 `ToolRegistry` 与 `LocalToolExecutionCoordinator`，不增设第二个执行注册中心。

**尚未实现**：Codex 基于工具能力的 `finishes_on_cancellation` 模式与独立 Worktree 沙箱。此 PR 不以固定等待超时假装完成有副作用工具，也不宣称插件卸载能撤销已经进入执行器的副作用。真正宿主取消终态仍需按工具/进程能力分别验证。
