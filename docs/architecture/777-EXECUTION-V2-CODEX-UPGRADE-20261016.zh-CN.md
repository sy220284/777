# 777 本地执行内核 V2：基于 Codex 源码对照的系统升级方案

> 状态：设计与实施计划，尚未实现或验收。制定：2026-10-16。
>
> 目标仓库：sy220284/777 PR #705，核查基线 39c7258fe3e48ba5efa28adce1fe968ec34ba6b9。
> 参考仓库：openai/codex，固定提交 6ae8b8aa7c608730d4315d59c5c081897ae02780（2026-10-16）。
> 范围：原诊断暴露的 Android 崩溃、长任务、模型/工具、预算、多代理和 UI 状态一致性，以及它们共享的执行基础设施。保留 R4 当前交付范围；不恢复 Next、双 UI 包或废弃功能。
> 原则：禁止防御性编程，不得靠吞异常、随意降级、伪造成功、扩大预算、无限重试或重放副作用掩盖设计错误。全部本地持久化，不建设自有云服务，不引入定时任务。

## 1. 结论与目标体验

用户启动工作后，任务能得到 Android 合法执行资格；离开应用、锁屏或回到前台时仍能准确看到真实状态。模型断流、请求预算边界、工具失败或设备生命周期切换后，可恢复的工作从同一任务及已确认检查点继续；未确定是否产生副作用的动作进入待核验状态，未经核验不重放。所有模型档案在发起请求前明确支持的功能；工具、审批、费用和执行结果通过同一权威事件流展示。V6 页面可自由更换视觉实现，业务事实与任务状态不迁移到 UI。

此次升级不推翻 777 已有 Session/Work/EventLog/UI Ports。使用现有 Owner、持久日志、历史检查点和资源门禁，修正跨模块执行契约，将并行状态写入和隐式假设改为明确的拥有者与状态转换。

## 2. 代码与故障证据

| 事实/问题 | 777 位置 | 结论 |
| --- | --- | --- |
| #705 最新 CI 构建失败 | ui-pack-v6/.../V6ConversationScene.kt:600 | maxHeight 跨 Compose DSL 接收者作用域，编译报 DSL_SCOPE_VIOLATION；历史通过记录不能签收该 SHA |
| Work 每步重复提交前台服务启动请求 | LocalWorkAgentTurnExecutor.kt:174,504；LocalExecutionService.kt:256-305 | startForegroundService 调用成功只证明提交成功，未证明服务已完成前台提升；后台更新步骤可撞 Android 限制 |
| 前台服务测试只断言抛异常 | LocalTurnForegroundLeaseAndroidTest.kt | 未验证业务任务、租约、持久化与进程稳定的结果 |
| ChatGPT 套餐返回 tools 不受支持 | OpenAiResponsesClient.kt；OpenAiResponsesToolAdapter.kt | 套餐下仍可序列化 namespace/tools；模型连通性探测使用空工具，不能证明工具授权 |
| DeepSeek 长流无可用输出仍持续 | DeepSeekClient.kt | callTimeout(0)，readTimeout(180s)，只约束底层读空闲；诊断中第 27 步 2327 秒未得到可用输出 |
| 共享执行片配额用尽 | LocalWorkExecutionBudget.kt | 默认 64 次属当前策略，关键缺口是团队协调与续接结算 |
| “内部续接”通过模拟用户输入入队 | LocalWorkContinuation.kt；LocalWorkRunRegistry.kt | 队列内容携带虚拟 user 消息，且通过 continuation- 字符串前缀区分；会话语义与恢复身份不够牢靠 |
| Work 状态投影部分异常只记录日志 | LocalWorkAgentTurnExecutor.kt；LocalWorkUiDocumentPublisher.kt | 进度发布失败可导致用户看见旧状态，缺少明确投影游标/同步结果 |
| 证据读取错误显示为内容缺失 | LocalWorkEvidenceSheet.kt | 读取失败与证据不存在没有区分 |
| Compose layouts not same hierarchy | 旧生产界面与动态文本选择/弹层路径 | 原始堆栈不完整，暂不能给某具体控件定责；必须补堆栈与重放场景 |

附：Android 官方限制说明 [后台启动前台服务](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) 与 [前台服务启动流程](https://developer.android.com/develop/background-work/services/fgs/launch)。specialUse 声明不自动授予任意后台启动权利。

## 3. Codex 源码参考及有条件移植清单

所有链接固定到参考提交，提取设计方式，按 Kotlin/Android 与现有 777 代码实现。

| Codex 源码证据 | 可借鉴机制 | 777 落点 | 不照搬的部分 |
| --- | --- | --- | --- |
| [turn_admission.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/app-server/src/turn_admission.rs) | 进入任务先取得许可，生命周期内有唯一 permit，关闭准入与放行同一所有权边界 | LocalWorkRunRegistry / LocalAgentRunCoordinator / Android 执行租约 | Rust 服务器进程/桌面 daemon |
| [turn_suspension.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/session/turn_suspension.rs) | 暂停前先 flush；取消持有者；关闭 writer 后移交；失败不得报成功 | Work 暂停/恢复和持久事件提交顺序 | Unix 风格进程关闭 |
| [rollout_reconstruction.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/session/rollout_reconstruction.rs) | 从 checkpoint 加有序后缀重建运行历史，保留 turn 身份 | LocalSessionEventLog、ModelHistoryCheckpointCodec、LocalForegroundRecoveryCoordinator | 另造 JSONL/第二套状态数据库 |
| [recorder.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/rollout/src/recorder.rs) | 单写入者、Flush 确认、持久化错误可观察，写入成功才允许依赖该事实转状态 | 现有事件日志及其写入承诺接口 | 桌面文件锁细节 |
| [tools/parallel.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/tools/parallel.rs) | 工具声明并行能力，读操作并行/写操作独占，取消遵守工具终止语义 | 复用 LocalWorkspaceReadWriteGate 和工具调度 | 新建第二个并行门闸 |
| [tools/orchestrator.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/tools/orchestrator.rs) | 工具调用走统一审批、资源约束和执行调度 | LocalToolExecutionCoordinator + LocalWorkTurnToolRuntime | Codex 桌面沙箱/权限模型原样搬入手机 |
| [tools/executed_tool_calls.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/tools/executed_tool_calls.rs) | 真实工具调用身份与完成元数据分开记录，模型输入与实际回执保持可追溯 | 本地 callId、执行回执、工具结果历史与文件版本 | 将 trace 当作已执行成功凭据 |
| [rollout_budget.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/rollout_budget.rs) | root-tree 共享消耗核算、跨成员阈值提醒、确认交付后记账 | LocalWorkExecutionBudget + 多智能体根任务预算 | 直接移植 Codex 的额度/加权参数 |
| [agent-graph-store](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/agent-graph-store/src/store.rs) | 持久父子代理关系与状态，恢复时能按拓扑找回子任务 | 复用 777 的 LocalAgentTeamRuntime、事件和恢复扫描 | 重做一个并列 team store |
| [responses_retry.rs](https://github.com/openai/codex/blob/6ae8b8aa7c608730d4315d59c5c081897ae02780/codex-rs/core/src/responses_retry.rs) | 重试过程清晰上报给 UI，尊重 Retry-After | 777 模型网络状态与 UI 进度投影 | 其中可选的无限连接重试、自动更换传输/重复模型请求不可直接照搬 |

特别约束：Codex 的实现随版本变化，参考设计必须回到 777 的实际凭据、服务端协议和 Android 生命周期验证。保留已有禁止已受理模型请求盲重放的费用安全规则。

## 4. V2 六个相互配合的升级单元

### U1. 统一执行身份与准入

基于 LocalAgentRunCoordinator 和 LocalWorkRunRegistry 增强 typed 身份：sessionId / runId / turnId / rootRunId / parentRunId / ownerEpoch / routeFingerprint；拥有者一次获得执行权，续轮只切 turn。各入口提交用户操作、进入前台服务、接入模型、领取工具都验证同一 run 所有权。

建立明确状态转换：
CREATED -> ADMITTING -> RUNNING -> (WAITING_APPROVAL | SUSPENDING | SUSPENDED | RESUMING) -> (COMPLETED | FAILED | CANCELLED | OUTCOME_UNKNOWN)。
这些是建议的统一内部状态，映射到现有 UI 执行阶段，不直接替换所有领域状态。状态转换必须有合法前驱；被拒绝不能偷偷进入 RUNNING。任何持久事实不得由两个 Owner 同时提交。

首期只改动生命周期交界处，已存在的 Work、Chat 独立业务执行循环继续保留；执行准入由共用 Port 协调，不将两种模式合成万能 Agent。

### U2. Android 前台执行租约重构

将 LocalExecutionService 中“启动请求”“服务已提升前台”“租约引用”“步骤/通知更新”拆分成不同操作和状态。用户界面允许的启动时机发起一次 startForegroundService；Service 完成实际 startForeground 后经明确确认通道返回 READY，随后才许可长任务运行。任务执行中的步骤、进度变化经同一服务实例的 Binder/进程内有所有权的接口更新，不调用 startForegroundService 重新启动。最后一个租约释放后再结束服务。

多任务租约以唯一令牌为键，禁止只按 sessionId 错误释放其他回合。启动被系统拒绝时提交明确 FAILED_TO_START 结果，并完成启动事务的状态回滚；不抛未捕获异常，不在后台偷偷绕过平台限制继续工作。进程死亡后按持久快照恢复显示状态；只有再次获得 Android 合法执行资格才能恢复执行。

Android 16/17 验收含前后台切换、锁屏、旋转、多 Work 并发、拒绝启动、服务被系统结束、通知状态、退出回到前台。

### U3. 模型路由能力契约 + 有效进展

Model Gateway 统一维护 route/account/model/protocol 四维能力快照：纯文本、函数工具、托管工具、图像输入、输出、长流、受理状态、收费策略。区分“已验证支持、明确拒绝、尚未验证”，未知能力不能被表述成已支持。

ChatGPT 套餐 text probe 不等于 tools probe。只有当用户确实使用工具能力或显式要求能力检测时，验证该具体请求契约（避免无意义额外收费）；与其不匹配的模型须在调用前反馈并保留用户任务，不猜测本该移除哪些工具，不默默换模型。服务端返回能力拒绝后，按实际账号和路由更新能力事实。

分离 transport alive / first useful output / last useful progress / provider admitted / response settled；根据端点协议定义读取与有效进展边界。失去进展时退出受理中的尝试，记录 MAYBE_ADMITTED/ADMITTED 与真实使用量；已受理请求禁止无证据整体重放。满足安全条件时产生 typed continuation。

### U4. 原生断点续接与代理树协调

替换 LocalWorkContinuation 中伪装为用户消息的内部续接输入及 LocalWorkRunRegistry 的 continuation- 前缀分支判断。使用独立的 ResumeCommand，包含 originRunId、turnId、checkpointSequence、resumeReason、sourceMessageId、routeFingerprint、continuationIndex、所有权代次。

先在现有 SessionEventLog 中写明 checkpoint，再切换 Owner；只重建 checkpoint 与事件后缀中尚未完成的事实，不重发已执行的工具调用，也不复制伪造 user 历史。原用户要求与续接控制指令分别储存：模型请求可显式获得必要的恢复上下文，但用户时间线不添加虚拟用户发言。

多智能体有一个根 Owner 负责执行片切换、团队预算、子任务收敛。子代理维持独立 childRunId 与真实成员结果，恢复时凭原有 Team 事件和父子关系定位，不因一个子代理预算耗尽而让全队同时各自抢占恢复。完成/暂停/失败必须能按成员和工具核验。

### U5. 工具回执协议及权限边界

原有 LocalToolExecutionCoordinator 继续是执行入口，升级每次调用的权威记录：callId、runId、ownerEpoch、toolName、argumentsHash、approvalReceipt、startedAt、executionStatus、resultId、exitCode、artifactPath、artifactHash、sideEffectStatus、verifiedAt。

结果至少区分 NOT_STARTED、RUNNING、SUCCEEDED_VERIFIED、FAILED_CONFIRMED、CANCELLED、OUTCOME_UNKNOWN。只读工具可以依据其真实契约重新查询；写操作、GitHub 提交、文件替换、外部请求等存在未知副作用时必须先读取远端或本地实际结果核验，不得整体重放。执行成功与验收成功各自有证据。

复用已有工作区读写锁；执行器按可并行读/独占写划分，不另起锁与队列。Shell 126/127、网络超时、命令非零退出及 MCP 拒绝都返回准确的原始操作、阶段和错误来源，异常不能笼统折叠为“工具失败”。

### U6. 事件驱动的 UI 进度与可复现诊断

保留现有 EventLog 作为唯一业务事实来源。新增严格版本化的 run/turn/tool/result 投影协议，由 Work、Chat 各自 Owner 发布；UI Host 使用只读 cursor/revision 获取结果，V6 只负责展示与交互。进度卡的显示版本可以落后，但必须能从事实日志重新构建；投影失败应显示投影同步错误，不改变实际工具回执，更不能伪造完成或“结果缺失”。

LocalWorkUiDocumentPublisher 当前只扫最新 256 事件并取最多 24 工具结果，可作为界面摘要；完整证据提供按游标读取。将结果“确实不存在”与“读取失败”明确拆开。所有诊断事件携带时间、同一个 runId/turnId/callId、完整异常堆栈、当前前后台状态、服务资格和有效输出进度。密码、令牌、用户文稿正文不得明文写入诊断。

Compose 旧界面 layouts hierarchy 崩溃先采集真实完整堆栈与复现脚本，再修对应的选择、弹层、布局坐标所有权；不允许大面积 try/catch。独立 V6 接入同样的 Host 状态契约并验收输入法、点击/焦点、动画转场、页面切换与无障碍。

## 5. 执行顺序与阶段门槛

### G0 — 恢复可验证性（阻断级）
修复 V6ConversationScene.kt:600 的 BoxWithConstraints maxHeight 作用域错误：在合法作用域先计算局部变量供嵌套 composable 使用；补编译回归。重跑同一最终 SHA 的 arm64/x86 构建、单测与 Android 16/17；区分实际编译故障与仿真器镜像、设备启动故障。不得降级门禁或跳过失败 lane。

### G1 — 所有权和 Android 生命周期（最先实施的内核重构）
实现 U1 最小运行身份证据 + U2 前台租约。Work 主流程及共享 Chat foreground 路径都必须真实确认 ready，验证后台步骤更新不会重复启动服务；加拒绝服务启动、前后台变更、锁屏、双并发实例、正常释放和进程死亡回归。

### G2 — 模型与工具契约
实现 U3 统一能力档案和套餐工具实际准入；同时完成 U5 typed 执行回执、用户审批及副作用验证。优先覆盖 ChatGPT 套餐/ API Key / DeepSeek / MiniMax 的已配置合法路由，不臆测第三方镜像能力。

### G3 — 恢复和团队执行
实现 U4 typed 续接、checkpoint 重建、root 预算配额协调；不要简单调大 64 次上限。回归 100+ 步任务、五个子代理同时触达边界、弱网、请求已受理后中断、用户中途发送新消息、切换模型和同一工具结果未知的恢复。

### G4 — 状态投影、Compose 与诊断
完成 U6、真实堆栈定位并修 Compose 布局层级冲突。按 R4 权威清单继续独立 V6 75 Scene / 173 Action 与可访问性、IME、变更显示；不得将新运行内核任务自动当作 UI 场景已验收。

### G5 — 收口与正式验收
最终 SHA 的全部必需 CI 通过、Android 16/17 真实设备复现-修复-回归闭环、外部真实授权工具实测、恢复结果和费用证据齐备后才推进 PR #705 的稳定性签收。CI 编译通过只证明可构建；测试代码存在只证明有测试，均不能替代运行验收。

## 6. 严格验收矩阵（失败即不签收）

| 场景 | 期待的可观察结果 |
| --- | --- |
| Android 17 应用从前台进入后台持续 50+ 步 | 仅第一次取得服务租约；后续只更新现有服务状态，无后台重复启动，真实完成并释放 |
| 系统拒绝前台启动 | 任务未进入 RUNNING，状态和资源归还，无未捕获异常和虚假“已启动” |
| App 在工具写入后被杀死 | 唯一 callId 可查真实结果，恢复不重复写入，未知副作用明确待核验 |
| 模型已受理请求后 SSE 中断 | 记录原请求状态、已用用量与最后有效进度；不盲重试，同任务安全续接 |
| 64 请求执行片临界 + 多子智能体 | 根 Owner 唯一协调执行片，成员输出和工具回执无遗漏/重复，任务可继续 |
| ChatGPT 套餐禁止工具 | 探测与实际调用返回明确限制，不出现“连接成功=工具可用”的误导 |
| Shell 退出 126/127、GitHub HTTP 失败 | 带阶段、命令/调用身份、真实原因和处理建议；证据可复查 |
| 工作进度投影失败后重新打开 | 从事件 cursor 重建；展示实际进度及投影错误历史，不篡改完成事实 |
| Compose 消息选择+弹层+键盘+导航 | 原布局崩溃能复现并修复；旧 Shell 与独立 V6 路径均通过针对性测试 |
| CI 不通过 / Android 仿真器没启动 | 不报告设备测试已通过；不绕过 merge gate |

## 7. 可实施的代码改动清单（不按文件表面“修一个点”拆任务）

- Runtime：LocalExecutionService、LocalAgentRunCoordinator、LocalWorkRunRegistry，新增单一执行租约适配接口；保证合法准入/服务 ready/ownership fence。
- Work：LocalWorkAgentTurnExecutor、LocalWorkContinuation、LocalForegroundRecoveryCoordinator、LocalWorkModelHistoryRuntime、LocalWorkExecutionBudget、LocalAgentTeamRuntime/TeamReducer；实现 typed resume、root 预算与成员一致性。
- Model：LocalModelGateway、LocalEffectiveModelContracts、OpenAiResponsesClient、OpenAiResponsesToolAdapter、DeepSeekClient；按授权构建原生合法请求，区分传输活跃与有效输出，保持费用结算规则。
- Tools：LocalToolExecutionCoordinator、LocalWorkTurnToolRuntime、LocalWorkspaceReadWriteGate、ToolResultRuntime；协议化 call/result/approval、副作用验真、原始错误可观测。
- UI：LocalWorkUiDocumentPublisher、LocalUiDocumentSessionStore、相关 UI Port、LocalWorkEvidenceSheet、V6ConversationScene；只读投影、错误分型、Compose 具体根因修复。
- Tests：Android 服务状态机、模型能力真实/模拟契约、工具副作用恢复、多智能体执行片、UI 数据投影、设备生命周期/IME/动画/高频交互、最终 SHA 的 CI。

## 8. 开发硬约束和范围边界

1. 禁止基于任意异常使用 catch-all 后假装成功；仅在明确的系统边界将已知错误转换为有类型的失败回执。
2. 禁止静默切模型、移除用户指定工具、伪造模型回复、跳过用户审批、无限重试、提高上限掩盖预算协作缺陷。
3. 禁止创建与 LocalSessionEventLog、WorkRegistry、Feature UI Port 并行的第二份业务真相，禁止为 V6/Legacy 分别实现运行内核。
4. 禁止强制自动播放或降低 UI 动效；本专项针对运行时稳定性，保持既定 UI 视觉和动效体验。
5. 无自建云同步，无用户不可见后台 AI 执行服务，无定时任务。系统允许的正常生命周期和实际用户动作可作为唤起来源。
6. 不扩大 #705 已明确取消的 Next/双 UI 全量开发范围。此文为“稳定性内核专项”实施规范，与 R4 UI 方案并行依赖；G0/G1 是 R4 可用性前置条件。
7. 每阶段给出：根因证据 → 修改范围 → 可运行测试 → 真机/真实路由证据 → 回归结果 → 未通过项。未完成不得标“已闭环”。

## 9. 当前完成状态

已经完成：对照 Codex 固定提交、映射 777 源码、制定 V2 目标契约/迁移顺序与验收矩阵。
尚未完成：任何业务代码修改、当前失败 CI 修复、Android 真机复现、模型授权实测及最终签收。此文提交不等于功能实现。
