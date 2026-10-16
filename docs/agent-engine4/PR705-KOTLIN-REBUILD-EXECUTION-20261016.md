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
