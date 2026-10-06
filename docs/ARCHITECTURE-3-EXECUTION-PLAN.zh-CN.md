# 架构 3.0 后续执行方案（#448）

本文把架构 3.0 的剩余迁移转成可执行工作包。它是实施计划，不替代 [ARCHITECTURE.md](ARCHITECTURE.md)，也不把计划项计为已实现。阶段编号沿用架构权威文档；阶段 3 内细分工作包，不新增另一套架构阶段。

## 1. 依据与代码基线

- 编制日期：2026-10-05。
- 目标 PR：[#448](https://github.com/sy220284/777/pull/448)，分支 `refactor/architecture-3-feature-layer-phase1`，保持 Draft。
- 主线基线：`6e1b96369ef7701c1acd74527b04a2a766f51721`。
- 本方案核对的产品代码：`f849150113e363bb172fbded70fe18c3ecc4d549`。
- 规则依据：[AGENTS.md](../AGENTS.md)、[系统联审](SYSTEM-AUDIT-GUIDE.zh-CN.md)、[架构 3.0](ARCHITECTURE.md)、[验证](VALIDATION.md)、[安全](SECURITY.md)、[UI / UX](UI-UX.zh-CN.md)、[协议](PROTOCOL.md)、[兼容性](COMPATIBILITY.md)。
- 实现事实由当前代码、构建配置、锁定文件和 CI 确认；文档中的旧版本号及旧 Engine 链路描述不能覆盖新架构和当前代码。

本轮本地验证覆盖 architecture / execution 门禁、Kotlin 编译及关联回归。当前产品 Head 的完整 CI 尚未验收；旧 Head 的单测、构建或设备验证结果不能替代本轮结果。arm64、x86_64、Android 16 / 17 与 merge-gate 必须在同一最新 Head 上全部成功后，才能把 P0 计为闭环。

## 2. 当前进度与真实剩余量

| 官方阶段 | 当前事实 | 后续处理 |
|---|---|---|
| 1：共享能力 / Kernel 边界 | Session owner、run identity/recovery、资源调度和 Session 存储已有统一所有者 | 保留已建立边界；继续删除残留跨层适配 |
| 2：领域状态 | Chat / Work / Kernel / Model 已拆分；Chat timeline rewrite 已停止承载 Work plan/todo/goal/planMode | 继续收缩聚合状态迁移适配层 |
| 3：Chat / Work Feature | 阶段三主体迁移已落地，验收待闭环：7 类 Runtime→Engine 依赖与已知 Engine 业务根永久归零；`chatTurnPort`、`workTurnPort`、`sessionLifecyclePort`、`toolsManagementPort`、`diagnosticsPort` 均已脱离 Engine；横向下一轮唤醒迁入独立协调器；共享 Tools/plugin composition、Session 前台恢复与 Diagnostics 均已有明确 owner | 保持阶段三退出路径永久禁止回归；本轮补齐队列失败保护、群公告权威事件、原子计划批准和领域恢复 owner；当前 Head 完整 CI 仍须单独验收 |
| 4：Automation Feature | Automation Runtime→Engine 已永久归零，但组合根仍由 Engine 提供 Automation Chat / Work coordinator | 迁出 `automationChatCoordinator`、`automationWorkCoordinator` 两个阶段四桥并切换后台入口 |
| 5：UI contribution | Catalog 不可变，路由归属及页面枚举已接管 | 中央页面 `when`、页面依赖、Back、入口及恢复继续下沉 |
| 6：Runtime Kernel | 共享运行状态已接管运行身份、资源、前台句柄等事实 | 删除剩余产品装配/迁移适配后再替换 Engine；仅更名不算完成 |

当前守卫基线已经切换到架构 3.0：

- Chat / Work / Session / Model / Tools / Automation / Settings Runtime → `LocalHarnessEngine` 已归零路径全部为永久禁止项。
- 阶段三已迁出的 Work task / agent-control 工具分发和 `sendChat`、`runAgentTurn`、`runChatTurn`、`runWorkAgentTurn`、`regenerate*`、`workSubagents` 等旧业务根进入永久禁止回归集合；不再用“空 allowlist”表达完成状态。
- 阶段三组合桥与阶段四 Automation 桥分开维护具体 allowlist，只能缩小，不能用等量新代理替换。
- Chat / Work provider Feature 禁止导入 sibling Feature internal；Shared Context 已改为中立 `LocalRequestContextPolicy` / DTO，由 Work 提供语义投影策略。
- Shared→Feature、Automation→Feature、Settings→Feature 与 UI→内部实现的剩余迁移债务全部按“消费文件 + 具体 import”精确锁定；新增、替换或 stale 边均失败，只允许单向缩小。
- UI 的 presentation facade / projection 可以继续作为展示边界；直接 Store / Coordinator / Service / 非 presentation Runtime / Manager / Repository / Tracker / Executor / Registry / Gateway / Port 属于阶段五迁移债务。
- Chat timeline durable state 只承载 Chat 领域状态，不再通过 Chat event 写回 Work 控制状态。
- 已删除的 Engine 迁移死代码建立永久禁止回归集合；Engine 外部直接消费者与 composition bridge 使用精确白名单单向收缩。方法数、构造依赖数、聚合状态字段数和 UI projection 字段数不再作为架构放行门禁。

阶段完成必须由真实 Feature 所有权、Shared 依赖方向、领域单写、旧实现删除、组合桥清零和当前 Head `architecture-3-gates` / 完整 CI 共同证明。

当前需要补齐：

1. 阶段三主体迁移已落地，`ENGINE_STAGE3_COMPOSITION_BRIDGE_ALLOWLIST` 已收缩为空；继续把这些退出路径作为永久禁止回归规则维护。
2. 阶段四单独处理 Automation 两个 Engine coordinator bridge，以及 persistent / automation subagent 等后台装配，不再混入阶段三进度。
3. 继续删除迁移后无调用旧实现，并同步收紧门禁。
4. 本地只要求 architecture / execution 门禁与 Kotlin 编译通过；仓库完整 CI 仍负责当前 Head 的 arm64、x86_64、Android 16 / 17 与 merge-gate 最终验收。旧 Head 成功、失败或取消均不能替代。

### 本轮审计修复与仍未完成项

- 已修复 Chat／Work 续轮消费先改内存后写盘问题：持久提交失败保留队列顺序，历史和数量投影只在事件成功后更新；派生快照失败不阻断已提交续轮。
- 已修复 Work 前台续轮启动异常／空队列的 Session 租约释放。
- 群公告统一到维护所有权与 Chat domain event；计划批准改为一个可回放的 `plan/approved` 事件。
- Chat 恢复与 Chat／Work 事件解释回到提供方领域；删除旧共享 Memory coordinator、Engine 旧包装并收紧永久禁止回归规则。
- Session envelope 保留既有领域序列化类型与文件格式；这些精确数据边界不允许替换为 Store／Coordinator／执行器依赖。
- 本轮未完成后台目标会话 Execution Port；其真实实现仍是 3-B 出口与阶段四后台接入的依赖，保持未勾选，不通过调整阶段口径计为完成。
- 当前 Head 完整 CI 尚待验证；旧 Head 的成功不能替代。

## 3. 总体推进顺序

执行顺序：**稳定当前 Head → 3-A 共享契约与状态边界 → 3-B Work 执行 → 3-C Chat 执行 → 3-D Chat 子功能 → 3-E 其他代理与横向边界 → 阶段 4 Automation → 阶段 5 UI → 阶段 6 Kernel**。

每个工作包都必须完成：新所有者接管 → 全部调用方切换 → 旧方法及回调删除 → 关联回归 → 门禁收紧 → 最新组合验证。后续工作包依赖前一包的真实出口条件，不按文件数量、提交数量或预计日期放行。

本方案不要求为每个 Feature 新建 Gradle module。平台无关语义继续属于 `harness-core` 等既有模块；Android 产品 Feature 继续在 `app` 中按 API、internal、UI 和组合根形成边界。

## 4. P0：稳定 #448 当前已完成部分

当前基线以 PR #448 最新 Head 为准；Head 与 Actions 编号只在 PR/CI 中实时追踪，不固化进执行计划。任何 architecture-3-gates 失败都先按真实所有权、依赖方向和运行不变量核查，不用旧 Head 或旧预算解释覆盖。

进入后续迁移前完成以下收口：

- [ ] 当前产品 Head 完整 CI：static-gates、architecture-3-gates、单测 / conformance、arm64 lint 与 APK、x86_64 测试产物、Android 16 / 17、merge-gate 全部成功。
  - 当前最新验证：static-gates、architecture-3-gates（含 ownership / dependency 与 execution invariants）、单测 / Harness conformance、JVM 21 字节码验证已成功；arm64、x86_64、Android 16 / 17、merge-gate 尚未全部结束。
- [ ] 确认本轮修复回归：取消日志故障仍停止真实 Job；维护发送保留草稿；启动失败释放租约；入队落盘前不能消费；资源重入不回放旧预算；设备授权撤销不残留；全局审批日志故障不阻断其他等待者；Work 进度先落盘；群聊异步结果不能覆盖新运行；设置最新值持久化。
- [ ] 核对 WorkStatePort / InteractionStatePort 的全部生产调用方与测试迁移，无旧字段或旧构造残留；复验新迁出的群聊成员、Chat stop / post-turn Job 所有权及取消时序。
- [ ] 补齐本次必要的故障 / 时序用例，不扩大迁移期 bridge / consumer allowlist，不绕过 architecture-3-gates 或设备 lane。
- [ ] 更新 #448 描述中的 Head、进度、验证与未完成项。当前“编译修复已提交”不能代替“当前 Head 编译和测试通过”。

P0 不做新的大块迁移。CI 失败先查根因与同类调用路径，修复后重新锁定基线。

## 5. 阶段 3-A：共享契约和领域写入口

### 功能与迁移

| 当前边界 | 目标所有者 / 契约 | 实施动作 |
|---|---|---|
| Runtime 聚合 mutableState 暴露给 Feature | 领域 StatePort + Shared Runtime 命令 / 投影 | 按读、领域写、运行命令拆分；业务协调器不得获得整个可写聚合 |
| WorkRunBinding 内聚合状态、队列、Job、历史混合 | Work 领域运行对象 + 单一共享运行句柄 | Work 保留计划、目标、交互策略；运行句柄保留 owner、Job、inbox、取消、资源和身份 |
| Session 持久化 / 恢复依赖 Work binding 和 Chat 内部函数 | Session envelope、领域快照及领域 codec / projection 契约 | Session 负责文件、顺序、事务和版本；Feature 负责领域编码、解释与回放；在组合根装配 |
| Runtime 诊断依赖 WorkRunBinding / Work 策略 | 中立诊断 DTO + Feature 诊断贡献 | Runtime 输出运行事实；Work 输出领域预算解释，不反向导入领域内部对象 |
| Engine 捕获的闭包、完整 state、全局“当前会话” | 显式 sessionId / runId / profile / revision 操作上下文 | 只传本次操作实际需要的能力和身份，禁止复制 Engine 全部成员到新 coordinator |

契约名可随实现调整，但职责固定。`LocalRuntimeProjection` 只投影已确定的事实，不能承接 Chat / Work 业务规则；新 Port 的实现不得继续调用 Engine 同名方法。Session envelope 可以存领域数据，Session 基础设施不得通过领域内部解码函数执行业务决策。

已迁出的 Chat 写入口统一遵守：先取得 Session MAINTENANCE 所有权，再提交 Feature-owned durable domain/timeline event，随后发布运行态，最后物化 Session snapshot 缓存。跨人物库/图集等多文档写入在权威事件提交前失败必须按原值回滚；EventLog 已成功后，snapshot/checkpoint 失败只能作为派生投影故障处理，不能把已提交事实回报成未执行。

### 出口

- [ ] Shared Capability 不反向依赖 Feature internal；领域 codec / Port 在组合根注入。
- [ ] 新 coordinator 无完整聚合写入口；迁移适配器白名单只减不增。
- [ ] 运行与领域单写所有者明确，foreground 和 detached Work 不建立第二套 Job / inbox / history。
- [ ] 保持现有 Session 文件、EventLog、jobs 文件格式和恢复语义；必要升级采用单向版本迁移并覆盖旧文件回归。

## 6. 阶段 3-B：Work 主执行链完整接管

### 功能迁移矩阵

| 功能 | 现有入口 / 核心实现 | 目标归属 | 必须一起迁移的关联链 |
|---|---|---|---|
| 发送 / 排队 / 附件 | `queueHumanTurn`、`queueWorkTurnLocked`、`recordUserTranscript` | WorkSend + Shared admission / inbox / transcript | UI Work 发送、Automation 后排队续跑、草稿反馈、落盘失败、取消前启动 |
| 主代理运行 | `LocalWorkAgentTurnExecutor` 已接管 AgentLoop；Engine 仍有 `syncVisibleWorkRun` 等横向投影适配 | Work 执行协调器 + Shared Agent / run handle | 多 step、完成、失败、步数延长、切换前台、结束快照、排队下一轮 |
| 工具与审批 | `executeBuiltin`、`approve`、`askUser`、`exitPlanMode` | Shared Tool execution + Work 工具贡献 / 审批策略 | step 暴露集合、参数副作用、设备授权、Plan/Todo/Goal、问答、恢复 |
| 子代理 / workflow | `workSubagents`、持久子代理工厂、`runWorkflow` | Shared Agent 执行 + Work workflow 策略 | 普通 / fork / 持久子代理、并行 / 流水线、父子取消、子代理模型身份 |
| 上下文 / 输出 | history budget、Work request projection、compaction、quality | Shared Model / context 机制 + Work 策略 | prefix cache、工具结果 spill / read、完成声明门禁、typed checkpoint |
| 重新生成 / 恢复 | `regenerateWorkReply`、中断恢复与 safe-job 入口 | WorkRecovery + Shared recovery adjudication | 未开始 / 结果未知副作用、历史重建、重复提交、进程死亡、任务恢复 |

当前 Work send、主 AgentLoop、重生成、请求上下文策略以及 task / agent-control builtin 分发已经迁出 Engine；`ENGINE_STAGE3_WORK_BUILTIN_ALLOWLIST` 已清零。后续集中迁出共享 Tools/plugin composition 装配以解除 `workTurnPort`，再处理可见 Work 投影 / Session 横向适配；阶段四 persistent / automation subagent 装配继续单独处理。每次删除旧路径同步缩紧门禁。

建立提供方拥有的 `WorkExecutionPort`：接收明确目标会话、输入、超时 / 恢复选项，返回结构化完成 / 阻塞 / 取消 / 失败结果。所有实际执行复用唯一 Session owner、run coordinator、冻结模型身份和 Tool execution；端口不提供 `MutableStateFlow<LocalHarnessState>`、binding 或内部 runner 给调用方。

### 出口与回归

- [ ] ViewModel / UI 的 Work send 和 regenerate 调用 Work API；Chat 不再作为 Work 执行的产品入口。
- [ ] Engine 不再定义 Work 主回合、计划目标规则、Work 编排和恢复业务。
- [ ] WorkExecutionPort 具备真实实现；多会话 Work、后台继续运行、切回投影、真实取消 / join 和下一轮队列均通过。
- [ ] 使用现有 conformance / recovery / Tool tests 补真实迁移差异，覆盖供应商 overflow、副作用未知、只读 / 计划模式与长上下文。

## 7. 阶段 3-C：Chat 发送和回合执行

Chat send、直聊回合、人物纠正、关系恢复、时间线编辑 / regenerate、分支和 post-turn 已有 Feature owner；`runChatTurn` 等旧 Engine 业务根已清零，`chatTurnPort` 也已脱离 Engine，由 `LocalChatTurnStarter` 在组合根直接提供。Chat 主回合结束后的续跑已经由 `LocalChatQueueRuntime.finishTurnAndStartNext` 负责。当前重点是迁出 Engine 内仍用于启动恢复、Session 切换 / release、Automation release 和 Work 可见队列恢复的 `startNextQueuedTurnIfIdle()` 横向唤醒逻辑，并继续清理群聊 / Session 横向适配，保持 foreground owner 与迟到提交语义不变。

目标分工：

- ChatSend 拥有聊天准入、人物上下文和发起回合；Shared Session / inbox 负责事务和持久顺序。
- ChatTurn 协调人物、场景、记忆、输出质量和回合交付；Shared Agent / Model 负责模型请求、重试、取消及资源。
- ChatPostTurn 的后台 Job 已由 `LocalChatPostTurnJobOwner` 接管；继续迁出 Engine 的调度 / 装配回调及聚合状态访问，完整拥有人物状态、日记、生活状态、关系记忆与迟到提交策略；沿用本回合冻结 profile，不重新读取当前账户。
- ChatExecutionPort 接收目标会话及触发上下文，暴露运行结果与必要领域能力；后台调用不能取得人物 Store / 协调器内部对象。
- foreground Job 仍由共享运行所有者持有。Chat stop 可下发运行取消，并取消本领域 post-turn；不能创建第二个停止通道。

出口：Chat 的发送和回合规则从 Engine 退出；已接管的停止能力继续通过共享运行所有者执行；普通单聊、附件、Vision、用户追加输入、切换 / 删除、维护时拒绝发送、post-turn 取消 / ABA / 迟到结果和恢复链路均与迁移前语义一致。

## 8. 阶段 3-D：Chat 子功能闭环

按依赖顺序迁移以下工作包：

| 工作包 | 迁移对象 | 调用方 / 旧路径删除 | 专项验收 |
|---|---|---|---|
| D1 回复建议 | `generateReplySuggestions` 的生成和调度；保留已有结果提交 fence | ChatRuntime 改调 Chat 建议能力；删 Engine 建议入口及工厂桥接 | 会话 / 人物 / 对话 revision 变化作废；失败可见；冻结 profile |
| D2 时间线与分支 | 变体选择已有 ChatBranchCoordinator；继续迁 `editAndResendUserMessage`、Chat regenerate、分支物化及日志重写 | UI 改用 ChatTimeline API，发送复用 3-C；删 Engine 分支入口和重建回调 | 编辑历史、切分支、重生成、回放、图片和工具结果保留；分页有界；失败不半提交 |
| D3 群聊 | 成员配置 / 移除已由 `LocalGroupChatMembershipCoordinator` 接管；继续迁公告、`runGroupChatTurn` 工厂、群聊人物纠正和系统提示刷新 | ChatGroup API 接管剩余链路；复验已迁成员能力；SessionRuntime 不再决定群聊成员规则；删剩余 Engine 群聊工厂 / 回调 | 部分 / 全部失败、单成员重试、异步成员修改、公告持久化、删除人物、单聊切换 |
| D4 人物关联功能 | 图集 / 故事、导入导出、日记、生活状态、关系记忆、行为调节与纠正撤销 | 已迁出能力先保留；清理 UI 直连 Store、跨 Feature 内部调用及聚合写适配 | 历史兼容、资料删除、肖像引用、晚间故事、生活事件续期、回滚与导入事务 |

这四包均要同时处理前台、后台、历史恢复、导入导出和 UI 入口。D4 是完整性及边界收口，不把已有能力再重写一遍。ChatRuntime 已永久保持 Engine 依赖为 0，阶段三已知 Chat 业务根和 `chatTurnPort` Engine 组合桥均已清零；后续重点处理群聊 / Session 横向适配和残余 coordinator 回调装配。

## 9. 阶段 3-E：其他代理出口及横向边界

### Model / Settings

1. 模型配置、选择、删除、清凭据和 ChatGPT 账户模型同步归 Model Capability；`LocalModelRuntime` 和 SettingsController 直接使用 Model API。
2. 身份变更准入通过共享运行状态契约判断，不通过 `engine::requireChatGptAccountSelectionAllowed`；运行中 profile / route / credentials 保持冻结。
3. Session 存储诊断、压缩、导出归 Session / Storage 能力；环境与诊断报告归共享诊断能力及 Feature 贡献。
4. Settings 只拥有设置流程和 UI 投影；不保留 Engine 转发。验证多账户、同路由不同 profile、断开 / 移除、旧配置升级、保存失败及后台调用身份。

### Session / Files

1. 新建、切换、删除、handoff / fork、恢复和排队模式切换直接由 Session 生命周期能力提供；Chat 人物 / 群聊初始化通过提供方 Port / codec 注入。
2. transcript page / tail / export 改为窄读取能力；workspace / conversation files / preview 和附件导入使用文件能力，不穿透 Engine。
3. 保留统一 transition 与 Session 删除租约，先真实 cancel / join 再删除；切换可见会话不能停止 detached Work。
4. 验证新建 / 切换 / 删除与 Automation 竞争、持久失败、旧 Session 迁移、长历史分页、路径 / 媒体边界和导出隐私。

### Tools / Plugins

1. ToolsRuntime 直接消费现有 plugin composition / 管理契约，迁出 GitHub 凭据管理、MCP HTTP / stdio 连接 / 断开、插件列表 8 个代理。
2. 凭据和平台 provider 留在受信任组合根 / 平台存储；工具执行继续只有一个 Capability Registry 和 execution boundary。
3. 工具管理的产品页面与 Agent 调用的运行机制分别归 ToolsFeature 和 Shared Tool Capability；MCP / LSP / 终端关闭须释放真实资源。
4. 验证连接失败 / 重连 / 取消、注销时现有运行、凭据隔离、workspace 路径及 Android 权限。

出口：Session 12、Model 3、Tools 8、Settings 7 的直接代理归零；反向导入、业务工厂闭包和旧适配器同步删除。保持既有远程 HTTPS 中继协议及独立凭据边界，不将本机内部重构顺带变成远程协议升级。

## 10. 阶段 4：Automation Execution Port

前置：3-B / 3-C 提供可运行的 WorkExecutionPort / ChatExecutionPort；Session 生命周期与共享身份 / recovery 边界已明确。

| 功能 | 保留给 Automation | 移交提供方 / 共享能力 |
|---|---|---|
| TaskCatalog / Scheduler | 任务配置、触发器、调度、立即运行排队、任务展示 | 共享 run identity、Session owner 和资源租约 |
| Planner | 任务规划、规划候选和提交生命周期 | 共享 Model admission / retry / profile 冻结；Chat 提供窄规划上下文 |
| ChatAutomation | 触发时间、quiet hours / silence 配置、任务状态与回执 | Chat 拥有人物 / 主动互动判定、内容生成、去重、场景和消息交付 |
| WorkAutomation | 任务执行请求、超时预算、回执及后续调度 | Work 拥有 Work 执行 / 恢复；Session 拥有创建 / 删除 / 持久事务 |
| Webhook / Settlement / Recovery | 输入鉴权、触发去重、任务结果结算、恢复调度 | Shared recovery 裁决可重试性；领域提供恢复执行端口 |

调用迁移顺序：LocalAutomationRuntime → Work / Chat adapter → Worker / Scheduler / 立即运行 / Webhook → 中断恢复。删除 Engine 的 `prepareAutomationWorkSession`、`runAutomationWork`、`runAutomationChat` 入口与 Automation 执行工厂。

重要约束：总超时包含队列 / owner 等待；继续使用单一 `scheduleGeneration` 提交权；取消 / generation 失效后模型和 Tool 新副作用前及迟到提交前检查有效性。Chat 主动消息落盘前复查用户消息与 durable inbox 活动；不能绕过人物领域策略。

出口：AutomationRuntime 3 → 0；Automation 禁止导入 Chat / Work internal Store、Coordinator、runner 和 writable state。Port 的返回值区分 delivered、skipped、blocked、cancelled、failed，避免“调用成功但未执行”的回执。

当前阶段四代码迁移已完成：Engine 的 `automationChatCoordinator` / `automationWorkCoordinator` 组合桥与 Automation→Chat/Work internal 精确迁移债务均已清零；Chat / Work 分别提供 Automation 执行 Port、恢复与领域策略 owner，`LocalAutomationRuntime` 只保留调度与结果适配。本地 Architecture 3.0 所有权门禁、执行不变量门禁与 `compileDebugKotlin` 已通过；完整仓库 CI 继续作为提交后的远端验收。

## 11. 阶段 5：Feature UI contribution

业务 API 稳定后按页面迁移，保持 `LocalFeatureCatalog` 唯一路由 owner 和启动期不可变组合。

| Feature | 页面 / 入口贡献 | 领域外仅保留 |
|---|---|---|
| Chat | 人物图集、日记、人物调节、群聊入口、Chat surface | Shell 的导航容器、共用样式与媒体拾取宿主 |
| Work | Workspace、RunCenter、审批 / 问答、Work surface | Shell 通用窗口与导航 |
| Automation | Tasks、任务筛选、任务详情、Webhook / Planner 入口 | 宿主导航与跨 Feature Port |
| Tools | Tools、GitHub / MCP / plugin 设置入口 | 平台授权宿主 |
| Settings | Settings 页面、账户 / 网络 / 存储 / 诊断入口 | Model / Session 等能力 API |

实施：定义 page、entry、Back、恢复和设置 / 诊断贡献契约 → 在各 Feature 内装配页面所需窄 state / actions → 每页切换真实路由 → 删中央分支及宽参数 → 完全删除 `LocalFeaturePageContent` 的产品页面 `when`。

`HOME` 仍由 Shell 容纳当前 Chat / Work surface；业务内容由 Feature 贡献。Shell 不获得任意 Feature internal 对象，不引入运行时任意产品 Feature 注册 / 卸载，也不与 PluginManager 混用注册表。

验收包括 Back / Drawer / 页面恢复、会话跳转、媒体 / 权限返回、任务打开会话、嵌套设置返回；复验手机单列、TalkBack、130% 字号和系统大字、长时间线、明 / 夜 / 墨及高对比壁纸。设备 UI 复验不能用静态截图或单元测试替代。

## 12. 阶段 6：Engine 收缩为 Runtime Kernel

前置：所有 Feature / capability runtime 对 Engine 直接引用为 0；领域业务与 UI contribution 已迁出；共享能力无反向 Feature internal 依赖。

最后处理顺序：

1. 把模型、Tool、Session、Feature、provider 的构造装配交给应用组合根，删除 Engine 的 coordinator 工厂和巨大闭包集合。
2. 删除领域专属 getter、状态写入口、helper、旧回调和未使用 imports；Kernel 不再知道 Persona / Gallery / Group / Todo / GitHub Token / 页面。
3. 将剩余职责逐项核对为 run identity、Session / run owner、跨域事务、真实取消、资源租约、recovery 裁决、启动 / 关闭与生命周期协调。
4. 在职责成立后更名 / 替换为 LocalRuntimeKernel；同步 Hilt graph、Service / Worker、测试、诊断与文档。仅重命名不算完成。
5. 将守卫从迁移预算改为最终禁止依赖规则；删除过渡 allowlist 和 Engine 代理路径。

出口：旧 Engine 业务及代理全部删除；聚合状态只为只读 UI / 兼容快照投影，不成为业务写入口；新增 Feature 不修改 Kernel 产品分支或中央页面表。

## 13. 执行批次与交付物

建议按以下依赖顺序提交小型、可验收工作包；不预先要求把全部变更塞进一个巨型提交，也不在本方案中直接合并 #448。

| 批次 | 范围 | 必需交付物 / 删除证据 |
|---|---|---|
| 0 | 当前修复稳定 | 当前组合完整 CI、失败回归、准确 PR 状态 |
| 1 | 3-A | 状态 / 运行 / Session codec / 诊断契约、反向引用清单和关闭证据 |
| 2 | 3-B 发送与 run | WorkSend、真实执行 owner、UI 路由、旧 Work 回合删除 |
| 3 | 3-B 子代理 / Tool / 恢复 | WorkExecutionPort、全部关联调用切换、无副作用重放回归 |
| 4 | 3-C | ChatSend / Turn / PostTurn、ChatExecutionPort、旧 Chat run 删除 |
| 5–7 | 3-D | 建议、分支、群聊 / 人物关联逐包关闭，ChatRuntime 归零 |
| 8–10 | 3-E | Model / Settings、Session / Files、Tools 逐包关闭及代理归零 |
| 11–12 | 阶段 4 | 前台 / Worker / Scheduler / Webhook / recovery 全部端口化 |
| 13–14 | 阶段 5 | 页面按 Feature 迁移，中央页面分发删除及设备体验复验 |
| 15 | 阶段 6 | Kernel 最终职责、旧 Engine 删除、最终边界门禁和完整验收 |

如拆分 PR，基础正确性和共享契约先行；依赖实现只能基于已验证基础推进。每合入一个批次后刷新 main 与剩余 PR，语义解决冲突，不能整文件覆盖另一侧。

每个工作包附：功能清单、旧入口→新入口映射、状态 / Job / 副作用所有者、前台 / 后台 / 历史 / 恢复调用方、删除清单、回归结果、Head 和 main SHA、尚未完成项。工厂、闭包和 UI helper 同样计入旧调用方清单。

## 14. 统一验收矩阵

| 维度 | 最少验证 |
|---|---|
| 主链 | Chat / Work / Group / 主子代理 / workflow / Automation 的输入→owner→持久事实→执行→交付→恢复 |
| 并发 | 同会话竞争、多会话并行、切换 / 删除、维护、重复点击、ABA、迟到返回、取消前启动、下一轮队列 |
| 故障 | 日志 / 快照 / 设置写盘失败、模型断流 / overflow / 超时、Tool 部分失败、设备权限拒绝、MCP 断连、进程死亡 |
| 身份与安全 | 冻结 profile / route、同路由多账户、只读 / plan、副作用未知、step 暴露工具、路径 / SSRF / Webhook / 凭据脱敏 |
| 数据兼容 | 旧 Session / EventLog / jobs / model profile、图集和故事导入导出、历史分支、tool output recovery、长期事件 |
| 性能 | 有界分页 / 扫描、独立 streaming、模型历史缓存、资源占用 / 预算、工具产物、cache 连续性与 usage 单账本 |
| 用户结果 | 接受 / 排队 / 拒绝真实、草稿保留、错误可见、运行中不假空闲、完成与 Todo / Goal 一致、后台结果可找到 |
| 架构 | Feature 无 Engine 代理、无 cross-Feature internal、Shared 无反向依赖、领域单写、Port 无可写聚合、无长期双路 |
| 构建与设备 | VALIDATION 规定的完整产品 CI，以及受影响真实 UI / 权限 / 生命周期的 Android 16 / 17 专项复验 |

阶段验收只针对最新 main + 当前产品 Head。CI pending、失败、取消、旧 Head 成功均不能勾选完成。纯文档方案提交的 scope / merge-gate 成功也不能替代上述产品基线完整 CI。

## 15. 下一步可直接执行的任务

1. 阶段三主体迁移已落地：四个剩余 Engine 组合桥、横向唤醒、共享 Tools/plugin composition、Session 前台恢复与 Diagnostics owner 已迁出；最新 architecture / execution 门禁与 `compileDebugKotlin` 通过。提交阶段三批次后由仓库 CI 继续做完整产品验证。
2. 进入阶段四：先迁出 `automationWorkCoordinator`，让 Automation 仅通过 `LocalWorkExecutionPort` 获取结构化执行结果，并消除 Automation→Work internal 依赖。
3. 再迁出 `automationChatCoordinator`，把主动互动、人物/场景/去重/消息交付归还 Chat 提供方，仅保留 Automation 的触发、调度与回执。
4. 阶段四结束时将 `ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST` 收缩为空，并验证 Worker / Scheduler / 立即运行 / Webhook / recovery 全部走 provider-owned Port。
5. 阶段四本地门禁与 Kotlin 编译通过后单独提交一批，再进入阶段五 UI contribution。

后续执行者按已完成工作包和出口条件更新本方案，不以正文计划或某次旧 CI 判断架构 3.0 已完成。
