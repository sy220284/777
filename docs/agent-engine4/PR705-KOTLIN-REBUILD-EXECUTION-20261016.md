# Agent Engine 4.0：PR #705 Kotlin 原生重构的正式执行基线

> 2026-10-16；以 PR #705 的 `feat/ui-r4-pluggable` 分支为**唯一施工基线**，本次检查 SHA：`cd0dbc0604996cf0b64fee8fd5a484dbbed42b85`。主线对照：`e03573fed4a3a77f9f6db850fb0522e367a678d2`。此文是执行计划，非已完成或 CI 验收证明。任务机器台账见同目录 JSON。

## 1. 执行范围、冻结边界与权威关系

此轮按用户明确要求：保留 Android Application Shell、V6 UI 及 ChatFeature 的业务语义；全面重审重构 WorkFeature、AutomationFeature、ToolsFeature、Settings 后端、Shared Capabilities、Agent/Tool/Model/Session、资源与恢复、MCP/LSP、终端、文件和设备 Provider。最终 Kotlin 原生实现；设计错误、无真实消费者或有重复 Owner 的旧机制允许在迁移后彻底删除。

`docs/ARCHITECTURE.md` **仍是唯一系统架构权威**；本实施单是架构 3.0 的具体施工计划。极薄 `LocalRuntimeKernel` 只管启动、进程范围、恢复触发与初始化失败投影，不因“重构内核”升格为 Work/Agent 领域 Owner。

PR #705 同时维护 510 个文件（此数字为核对时快照），为 V6 UI Draft，大量 Host Port、Work / Session / Automation / Tool 改动在进行中。**每批改动先读取 705 最新 head 再施工**，不要覆盖同分支其他人的进度；保留 `v6.migration.accepted` 未签收状态，不提前合并，不扩展应用层/聊天业务改造范围。

## 2. 与实际代码对照后确认的事实（非泛泛清单）

| 入口/源码 | 已确认事实 | 重构决议 |
| --- | --- | --- |
| `harness-core/.../agent/AgentLoop.kt` | 模型无工具调用则产生 `TurnCompleted`；带 step 预算和 EventSink | **仅代表 Turn 正常结束**；Work Goal 任务终态必须受业务验收及真实工具证据约束；不得改变 Chat 已使用的回合语义 |
| `app/.../work/LocalWorkCompletionEvidence.kt`、`LocalWorkDeliveryEvidence.kt` | 有 Goal/Todo/Team/审批/问题、文件哈希、测试真实 tool/result 的非空检查与来源关联 | **保留并重构**：增加声明级、需求级、证据级 Verification，对未定义验收的开放任务明确等级；绝不将已有能力称作缺失 |
| `harness-core/.../workflow/HarnessWorkflowRunner.kt` | 清洗子任务后 `.filter(String::isNotEmpty).take(maxTasks)`，静默丢空条目与超限任务 | 第一批修复为**原子准入拒绝**；后续设计持久 TaskGraph 队列，保证超过容量任务不丢失 |
| `app/.../work/LocalWorkflowCoordinator.kt` | 显式限制为 1～4 条只读子任务，验收依赖委托输出包含指定文本 | 这是目前产品准入契约，不把扩容与保真修复混作一件事；后续改为可持久调度的动态 TaskGraph，禁止把文本命中冒充外部真实性核验 |
| `app/.../work/LocalAgentTeamRuntime.kt` | 任务 revision/CAS、依赖、结果ID 有实现；`writeScopes` 显式说明 `advisory_only`、`file_isolation=none` | 保护已有 CAS / durable inbox；仅将真实资源/权限 Scope 用于强隔离，无法实现时明示不支持 |
| `app/.../runtime/LocalRuntimeKernel.kt` | 进程 kernel 极薄（65行） | **原样保留职责**，不为重构重写正确实现；Session lease、RunEpoch 和 Resource lease 各归 Shared Runtime |
| `app/.../work/LocalWorkAgentTurnExecutor.kt` | 生产真实使用 AgentLoop，工作模式有 steer revision、EventLog、工具执行协调及输出质量检查 | 新 Kotlin 运行契约要从实际生产路径切入；不得只建未调用的通用接口 |
| `app/.../LocalModelRequestCoordinator.kt` | 已持有请求级证据、尝试、stream 与 route identity 语义 | 分离模型 provider 适配与执行准入，冻结实际路由；保护 Chat 的协议和现有配置 |
| `docs/design/ui3/R4-13-GAPS-ROOT-REWRITE-20261016.zh-CN.md` | #705 的 V6/UI 仍有真实 Owner/证据、MCP/技能和 Host 服务缺口 | 4.0 修改不得宣称完成 705 的 UI 工作，也不能覆盖 V6 的身份、授权和取消回执 |

以上已检源码路径为当前 PR 读到的代码；**未宣称对所有源文件逐行审计**。后续每个工作包必须补生产调用点、事实 owner、迁移、测试与最终 SHA 证据。

## 3. 新 Kotlin 运行时唯一语义

- **领域目标**（GoalSpec、AcceptanceSpec、Work TaskGraph）由 WorkFeature Owner 持有；**执行事实**（Run/Turn/Step/GoalRound/ToolAttempt/ChildActivation）由 Shared Agent Runtime 持有，不能把 Work 业务规则放进内核。
- **一次模型 Turn 完成 ≠ 用户 Goal 完成**。Work 验收根据用户目标、真实工具执行/外部结果、文件内容版本与测试/检查要求作出 PASS/REMAINING/BLOCKED/UNKNOWN 结论。模型原文不能直接作为验收签名。
- **副作用身份**包含 sessionId、runId、epoch、goalRevision、toolCallId、executionId、handlerRegistration、lease。用户 steer/审批延迟/资源等待/取消/进程恢复都要检查当前 Owner 身份。
- **零丢失**：有界 admission 必须全部接受或明确拒绝；后续持久 TaskGraph 必须可查询未执行的每一个任务。禁止 `.take(maxTasks)` 这种隐式丢失。
- **终态**：TurnEnded、GoalCompleted、Partial、Blocked、WaitingUser、WaitingApproval、Cancelled、UnknownSideEffect 分清；取消不意味着外部工具已终止。
- **单事实源**：会话 EventLog 及 Owner 的领域事实，Checkpoint/投影不是另一条并行写入系统；一套合法审批/请求/任务版本控制。
- **Android**：Node/Python/Git/Shell/MCP/LSP 只是受托工具运行环境；新 Agent / Workflow / Goal 都用 Kotlin，不引入桌面内核/辅助服务。
- **App/Chat**：其业务文件冻结，只通过现有公开 Port 和共享契约适配；任何共享变更必须保持人物故事、群聊、Chat 多模型和用户存量数据行为。

## 4. 24 工作包依赖与可合并单位

保留 V3 的 AE4V3-01～24 编号和依赖，台账 JSON 是施工任务的唯一进度记录；每项至少具备：源码证据、唯一 Owner、真实生产消费者、契约、正反例、迁移、测试、回退/清理退出条件。

**批次 A**：01 边界与基线 → 02 全功能入口盘点 → 03 任务基准 → 04 Run/Turn/Tool/Goal 契约。批次 A 可以先落实没有跨层侵入的既有缺陷修复，但不将它算成新引擎完成。

**批次 B**：05 Session/Event → 06 Run Owner → 07 Model → 08 Turn/Steer → 09 Tool settlement。最小可工作路径为 **Work 任务 → 模型请求 → 真实工具 → 持久事件 → 取消/恢复**，任何切片必须进入生产调用链。

**批次 C**：10 Tools 管理 → 11 GoalDriver+Verifier → 12 Work 全面重构 → 13 Context/Memory。优先实现“用户无需反复发继续”的可验证任务闭环。

**批次 D**：14 Agent Team DAG → 15 Resource/Jobs/Interaction → 16 Automation → 17 Settings 后端 → 18 Usage/Event/Diagnostics。

**批次 E**：19 FS/Git/Shell → 20 MCP/LSP/Network/Device → 21 #705 Host Port/Chat 保护适配 → 22 旧数据迁移 → 23 删除旧语义/失效功能 → 24 自动化签收。

**现有功能的优劣判定**：正确就保留、可改善则重构、重复 Owner 则合并、误设计且无合法消费者才删除。永不以改文件名、拆类或增加接口数量计完成。

## 5. 第一批代码变更与退出条件

本次先修复 `HarnessWorkflowRunner` 的**静默输入丢失**，不改 V6 UI、不修改 ChatFeature、不改变现有 Work 1～4 个子任务准入。核心在调用 `execute` 前执行完整任务列表校验：

1. 列表为空 → 明确拒绝。
2. 任一条目为空或全空格 → 明确拒绝，禁止过滤后错位。
3. 超过配置容量 → 明确拒绝，**未执行任何子任务**。
4. 允许容量内全部执行、结果顺序保持输入一致；并发度仍有界。
5. 新增 JVM 回归：空项/容量溢出原子拒绝、6 条允许任务不截断，保留原并行/流水线/取消测试。

这个修复属于已核实缺陷的生产路径改进，**不代表 GoalDriver、动态 TaskGraph 或跨层重构已完成**。下一批才会从 `AgentLoop` 实际被 `LocalWorkAgentTurnExecutor` 调用的地方设计 typed Run/Turn/Step/Steer 契约，而不是为了画架构图创建空包。

## 6. 验收、保护和文档规则

- 核心代码变更：按当前 `docs/VALIDATION.md` 和风险分类器跑相关 JVM 测试、架构/性能/安全门禁、Kotlin 构建和最新 SHA 的必要 CI；**本用户本轮要求不安排人工/设备验证作为任务里程碑**。仓库已有与发布绑定的独立 CI，不擅自更改其要求。
- 加入 Chat 保护回归：人物身份/记忆隔离、群聊历史、模型路由、取消和会话回放；App Shell / V6 保留现有 75 场景和 173 动作真实性目标，不对 UI 清单状态作虚假升级。
- 每次代码切片明确“新增真实消费者/替换旧路径/旧路径退场”；每项提交校验当前 head 与 PR 合并资格。仍保留 PR #705 Draft 与旧 R4 台账；4.0 工作台账独立记录进度但同在 #705 分支。
- 新功能发生实际增删/归属变化时，同次同步 `docs/FEATURE-TREE.zh-CN.md` 等权威功能索引；本次属于现有工作流内部输入准入纠错，不新增/移除产品功能叶，故无需变更功能树。

## 7. 不允许提前宣布完成

本次完成预期仅为：PR #705 分支上有可审计的 V4 实施 ADR、24 项任务台账以及第一个可证实的执行语义修复。**整套 4.0 重构并未完成。** 任何 Kotlin 编译、CI、旧版兼容或真实多模型表现，只有本提交 SHA 实测通过才可标记通过。

## 8. 第二批：Work 目标语义接入真实 `turn/end`（增量）

本轮在 `LocalWorkCompletionEvidence` 中声明强类型 `LocalWorkGoalDisposition`：`NO_GOAL` / `PAUSED` / `BLOCKED` / `WAITING_APPROVAL` / `WAITING_USER` / `WAITING_TEAM` / `NEEDS_EXECUTION` / `READY_FOR_SETTLEMENT` / `COMPLETED`。它完全**从 Work 当前真实状态及已有检查证据派生**，不新建任务状态数据库。

- `recordWorkCompletionQuality` 的结果同时提供 `LocalWorkGoalAssessment`，避免重复读取文件哈希与 EventLog；`LocalWorkAgentTurnExecutor` 在原生产 `turn/end` 事件附带 `goal_disposition` / `goal_id` / `goal_status` / `goal_outstanding_count` / `goal_ready_for_settlement`。`reason=completed` 继续表示 *Turn* 正常结束，绝不等价于 Goal 完成。
- `READY_FOR_SETTLEMENT` 只意味着**当前已知义务清零**，不自动把 Goal 状态写为 `completed`；用户审批/提问、正在运行的团队成员及暂停/阻塞状态都禁止被当作需要立即再开模型请求。
- 即使旧 Goal 状态为 `completed`，如文件证据过期仍投影 `NEEDS_EXECUTION`，不制造成功。
- 新增有开放 Todo、有证据缺口、目标暂停、团队执行中及无 Goal 场景的 JVM 状态断言。此提交没有实现 GoalDriver 自动续轮、真实 SideEffect Unknown 状态裁决、Agent Team 统一 TaskGraph，不能把 AE4V3-04 / -11 判定完成。

## 9. 第三批：Work Goal 有界进展续轮接入生产（代码待 CI）

该切片在 `harness-core/agent/AgentInputQueue` 中新增 `offerIfEmpty`（内嵌原持久提交/失败回滚），让自动接续不能排在已受理用户指令之后；保留现有可取消的 `WORK_CONTINUATION` 和 `AgentContinuationRef` 持久格式，不引入新持久事实源。

Work Run Owner 的 `finishTurn` 在本轮历史 checkpoint 成功、尚未取消、下一条用户输入尚未入队时调用受约束的 `onIdle`。真正生产中的 `LocalWorkAgentTurnExecutor` 记录 ToolFinished 的成功回执与错误，`TurnCompleted` 留存**清理临时审批之前**的 GoalDisposition；仅当本轮正常完结且 Goal 当时及当前均为 `NEEDS_EXECUTION`，由 `LocalWorkGoalContinuation` 对照冻结目标身份与验收合同，再使用原子空队列提交 `work-goal-progress-queued`。

准入条件：明确非空 goalId 且目标未改变、status=active、非 Plan mode、真实成功工具回执、无工具失败、无取消、无持久化错误、用户输入队列为空、steer revision 未变；采用既有的最多两次内部自动续轮政策。目标待审批/待用户/待团队/已完成或仅凭模型空口答复均不得自动重开请求。对未知副作用、存储错误保持停止而非重放。

JVM 回归新增：
- `AgentInputQueueTest`：新用户输入优先、失败持久化提交回滚。
- `LocalWorkGoalContinuationTest`：真正工具回执、有无进展、失败、取消、steer、排队、目标替换、暂停/完成/等待、计划模式、连续续轮上限，以及 queue codec 恢复提示词与身份。

**本阶段边界**：工具成功回执只是启动下一轮进一步核查的准入信号，**不等于已证明用户目标有效进展**；尚未实现跨无限长任务的动态进度度量、不确定外部副作用的查询/核销、严格持久 Goal revision 与全面多进程恢复。AE4V3-11 只能标 `in_progress`；最终 Kotlin 编译/单测必须以最新 SHA 的 CI 为准。App Shell/ChatFeature 业务未改动，本次新增 Shared/Work 行为不会自动变更 R4 UI 交付状态。


## 10. 第四批：持久 Goal 版本栅栏与用户输入优先（代码待 CI）

此批在**原有队列及 Work Run Owner**上增强恢复语义，不额外建立任务执行器。

1. `AgentContinuationRef` 新增可选 `goalId`、`goalFingerprint`，旧式模型失败续轮不需要两字段；`LocalAgentInboxPersistence` 对两字段执行成对验证、原队列事件版本不变。
2. Work Goal 指纹使用 `LocalGoal` 的可序列化完整合同 SHA-256（目标 ID、描述、状态、note、startedAtSequence、产物和验收命令均参与），首次入队和恢复后出队重新比较。仅凭同一个 `runId` 或目标名称不允许继续旧任务。
3. `LocalWorkRunRegistry` 消费持久输入时执行 `shouldDiscardWorkGoalContinuation`；合同过期、待处理审批/提问、团队仍在活动，或已有之后到达的用户输入，均通过 `agent/inbox/spliced` 原子记录弃用，并且不向模型历史追加过时续轮提示词。失败续轮仍走旧兼容机制。
4. 新增 Work Goal 测试覆盖身份变化、验收命令变化、暂停、用户队列抢占、团队阻塞、持久编解码；另测格式只存在 goal ID 而缺 SHA 时 fail-closed。

**仍未实现**：Work 的跨进程自动接管与主动重试完整事务、真正增量进度的持久证据模型、UNKNOWN_SIDE_EFFECT 外部核销。此批不提高 AE4V3-11 完成度、不变更 ChatFeature 与 App Shell 业务。构建/单测结果仅以最新 SHA 的 CI 判定。


## 10. 第四批：工具副作用确定性和失败续轮原子准入（CI 待验证）

1. Work Goal 自动续轮在取得当前模型成功工具回执后，还扫描相同 Goal 在正式 EventLog 中的 `tool/call`、`tool/execution-started` 和 `tool/result`。对已开始未结算或 `TOOL_OUTCOME_UNKNOWN` / `is_error+side_effect=possible` 的调用，**阻止新自动续轮**，直到权威工具结算可以证实结果；不凭模型的后续成功回复覆盖先前未知副作用。
2. 修复模型错误自动续轮的原子性竞态：原先判断用户队列为空后使用普通 `offer`，并发用户输入可插入后导致内部续轮排队。现用与 Goal 自动接续相同的 `offerIfEmpty`，用户新要求始终优先。
3. 新增 JVM 回归：执行已开始但无结果、工具恢复报告未知副作用、后继成功结果不得掩盖旧未知、其他 Goal 隔离、工具声明但未开始与正常已结算路径。
4. 本轮没有新增后台任务队列或改变 App Shell / ChatFeature；工具副作用裁决的独立核销接口、复杂长任务的真实进度证明及跨进程完整恢复仍待后续工作包。保留 AE4V3-11 `in_progress`，以当前提交 SHA 的自动 CI 为验收依据。


## 11. 执行核心真实替换：AgentTurnEngine（2026-10-16）

本次执行策略调整为按模块完整迁移：删除原 `AgentLoop.kt` 运行实现，拆分为强类型 `AgentExecutionContracts.kt`（现有兼容事件与接口）、`AgentTurnState.kt`（Turn/Step 受约束状态与动态预算）、`AgentToolDispatch.kt`（同步、连续并行组与结果结算顺序）、`AgentTurnEngine.kt`（Kotlin 原生核心生命周期和取消/异常终态）。

**生产消费者已全部替换**：`LocalWorkAgentTurnExecutor` 和 `LocalSubagentRunner` 直接调用 `AgentTurnEngine`；`NativeConformanceRunner` 以及 harness-core 的行为回归同样改为新引擎。旧执行实现从源码退出，不能再使用 AgentLoop 构造器。新引擎的 ToolStarted 仅是模型声明事件，真正 Tool executor admission、租约与外部副作用结算保持 Shared Tool Runtime 为唯一 owner。

新实现添加 Tool ID 空值检查、Turn Step 显式状态推进、工具批次结果与 durable event 先后约束，以及同步/并发混合工具顺序回归。保留既有 EventLog payload、StepBudget、Cancel 与 Tool 异常语义，避免影响存量 Session/Work 历史与 Chat 业务。

此批 **只是执行内核真实替换的第一部分**：旧 Work/Agent 领域 orchestration、GoalDriver、TaskGraph、多模型专项等工作仍待分批重构。编译、参考差分、Android 构建必须等待最新 PR HEAD CI 通过，不能仅凭提交宣称完成所有 24 项。

## 2026-10-17：Shared Session 执行所有权接管

核对基线为 `9124e056a2571de54b87c3b6b38e23b71d1ffc80`，主线仍为 V3 的 `e03573fe`。本轮开始时台账 9 项 in_progress、15 项 not_started、0 项 completed；已有 AgentTurnEngine、TaskGraph 和 Goal 续轮切片不能视为 V3 全层重构完成。

Session 的互斥、等待者、释放、输入准入和身份事实改由 `harness-core/session/SessionExecutionRegistry` 唯一持有。Android `LocalSessionRuntimeRegistry` 保留现有消费者 API，仅映射产品运行类型，不再持有第二份所有权状态。租约带 Session、进程 incarnation 和单调 epoch；释放后重入同会话不会复用身份，误传跨会话/跨类型/另一 Registry 的租约在进入执行体前拒绝，且不会释放别人的合法租约。

`withOwner` 将确切租约放入协程上下文，前台 Work 与后台 Work Automation 使用同一合同；工具在审批、文件资源等待之后重验所有者，并将相同身份附到平台执行上下文和已有 `tool/execution-started`。已启动工具抛出异常时保留 executionId，异常回执与正常回执用同一执行实例归属。原 Session 日志、用户资产、模型凭据不迁写，ChatFeature/App Shell 文件不改。

参考依据：Codex ZIP `codex-rs/core/src/agent/control.rs` 的会话绑定 Runtime；DeepSeek ZIP `packages/goal/goal-round-driver/src/index.ts` 的精确 Agent 生命周期及 queued/claimed/admitted 竞争语义；Claude 官方 ZIP `mods/README.md` 的扩展与权限分层。第三方历史还原包仅保留作研究材料，本轮没有移植其实现。

验证新增：Shared Session ABA/错误会话/错误 Owner/外来 Registry/取消等待者/删除交接，Android 兼容入口及子协程身份传播，工具批准等待后旧租约失效，异常回执 executionId。静态架构、性能不变量及各自自测、测试质量检查已通过。Kotlin 编译/单测以本提交 CI 为准；AE4V3-06 保持 in_progress，持久恢复、Run/Goal epoch 提交合同及未知副作用核销仍未完成。无需新增功能树节点：既有 Session/Tool 能力的内部所有权迁移；源码索引随本次刷新。

## 2026-10-17：Run 提交权与冷恢复事实统一

本次在 `1974a886f` 上接续重构，同时保留同期 R4 工具表单、Host 文件导入/媒体选择/工作区导出及证据文件版本读取改动。

- Shared `AgentRunOwnership` 替换 Runtime 私有前台 Map；前台、后台 Automation 和子代理使用明确的 Session/slot/Run 身份。检查与持久提交、终态释放和交接在同一槽内完成；持久化失败不夺走旧 Owner，另一会话的 IO 不被全局锁阻塞。
- Work/Subagent 真实执行入口传入 Run 协程上下文；AgentTurnEngine 在采样返回后核对原 Run，失效回复不能进入正文或派发工具。工具在审批与资源等待后再次核对同一 Run，并把 Run/parentRun 身份随现有 execution-started 交给平台与事实日志。
- 真实执行准入才可记录 `read_only`，Shared ToolAttemptLedger 以强类型阶段区分声明、读取、外部执行待结算、已结算及外部结果未知。SessionRecovery 对核实的只读调用保留安全重读语义；缺乏旧合同的历史调用仍保留原保守语义。结果明确未知时，只读标识不能抹去未知事实。
- 恢复排队/阻塞沿用原检查点内容，仅变更恢复状态，保留精确模型档案、凭据引用、原始任务和配置。再次启动按 Run 起点之后的持久工具事实单次流式折叠，移除原先按 provider callId 的另一套集合与全会话 materialize。上次 repair 已经结束时，未知副作用也不能因临时 repair 为空而失去阻塞。后台旧恢复路径会重新执行任务而非恢复完整执行历史，因此已知写入仍需核销，不能直接重放。

新增回归源码覆盖：持久认领失败、同槽交接与跨会话并发、终态一次提交、模型迟到、工具审批中 Run 交接、只读冷恢复、来源身份、未知结果不能清除、恢复模型身份保留、连续两次恢复未知写入、排队后的后台副作用重判。按用户要求不安装工具、不运行本地编译/单测；当前检查仅为源码、架构、性能不变量、测试质量、安全及功能树静态复查。整项完成计数不提升，完整 GoalDriver、执行历史恢复与外部副作用核销仍需后续实现。
