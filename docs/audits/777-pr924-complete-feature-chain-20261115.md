# PR924 固定快照独立审计与功能链路修复

## 审计结论与范围

PR924 的主要调整方向合理：业务状态回到各自 Feature、工具执行继续经过同一注册表、UI 通过公开契约消费能力、会话事件承担持久事实、长输入和审批内容完整传递。这些调整改善了功能实现本身。

起始快照仍存在七项确认问题：目录搜索遗漏基础工具、分页限额误报工具不可用、工具定义固定额度误拒合法契约、搜索取消未进入遍历、后台恢复记录裁剪、结果入口误读状态摘要，以及 MCP 错误类别依赖中文报错匹配。对应实现和回归已在本地修正。

本报告区分源码审计、JVM 行为验证、Android 测试代码编译与设备实测。当前宿主缺少 Landlock 和 KVM，进程隔离相关八项测试失败、Android 设备交互未执行；整套验证未达到全部通过。具体事实见末节。

- 仓库：`sy220284/777`；目标 PR：`#924`。
- 固定审计快照：`8a2792e2b5303eac78a811044e728fd052fc9cac`。
- 对照 main：`06455974df58e2a2f5642586949b0e1ce6d454cf`。
- 原 PR 差异：205 个文件，增加 4,918 行，删除 2,685 行。
- 首次审计分支：`audit/pr924-independent`；提交整合分支：`audit/pr924-submit`。修复整合到 PR 原分支，保持 PR 开放。
- 首次审计结束前远端 PR 已推进到 `760bdd3954abe8e7617aa8bef3021b348d12567c`；本报告与测试结论仅覆盖固定快照及本轮本地修改，未将其他会话的新增提交混入。
- 权威依据：`AGENTS.md`、`ARCHITECTURE.md`、`SYSTEM-AUDIT-GUIDE.zh-CN.md`、`SECURITY.md`、`PROTOCOL.md`、`COMPATIBILITY.md`、`VALIDATION.md`、`UI-UX.zh-CN.md`、`DEVELOPMENT.md`、`SHARED-AUDIT-CONCLUSIONS.zh-CN.md`。

## 系统入口、所有权、契约与装配核查

| 功能 | 实际入口与公开契约 | 状态、执行与持久化所有者 | 纵向与横向核查结果 |
|---|---|---|---|
| 启动与导航 | Application → `LocalRuntimeKernel` / Feature Host | Kernel 管启动和进程 scope；`LocalFeatureCatalog` 唯一分配路由 | Kernel 未接管每条业务执行；通知继续定位 Manifest 启动组件 |
| 聊天、人物与群聊 | Conversation UI Port → Chat 发送、队列和人物 Owner | Chat 解释领域状态；Session 保存事件与历史 | 队列、编辑、重发、切换和取消复用原链；被删 UI 适配未形成新的执行通道 |
| 工作任务与计划 | Work UI / 内置工具 → Work Run / Goal / Interaction | Work 拥有计划、目标、问题与审批；Session 保存批准事实 | 长计划、长问题、全部选项完整进入展示、批准和后续执行；空方案不会进入等待态 |
| 工具发现与目录 | `capability_search` / Tools 公开目录查询契约 → `LocalCapabilityDiscovery` | 实时 ToolRegistry 是可用目录来源；选择状态归当前运行 | 目录契约与模型预选共用匹配器；修复 CORE 搜索遗漏及限额误报；未把新增接口写成页面已接入 |
| 工具调用与编排 | 冻结模型工具表 → 执行协调器 / Code Mode → Registry | Registry 统一校验、审批和调用；工具回执保存真实执行事实 | 单调用和批量调用保持同一曝光边界；静态参数在前置写入前校验，动态引用解析后再次验证 |
| 模型与上下文 | Model 路由、请求准备、历史压缩与预算投影 | 路由身份、凭据、用量和资源由原共享能力拥有 | 保留提供方真实容量和取消契约；去除额外 24,000 Token 工具定义硬上限 |
| 技能 | 显式选择 / 固定技能 → 当前模型步骤规则 | Skill Store 保存文档；Work 解释用户选择 | 原快照已取消技能正文静默截断；当前规则不再通过重复历史消息拼接 |
| 独立助手与团队 | Agent UI Port / 模型控制 → 同一 Agent Control Port | Job 保存身份、邮箱和运行状态；Team 管成员与任务板 | 持久启动、控制、消息和恢复复用原 Owner；本轮统一完整结果读取 |
| 后台恢复与结果 | Job 恢复 → 子任务 history checkpoint → 新 Activation | 恢复协调器与 Session 原检查点 | 新 v3 保留全部已选工具与最终结果；读取结果按准确 Job ID 查询同一检查点 |
| 自动任务 | Tasks UI / 规划入口 → `AutomationUiPort` → Scheduler | Automation 统一调度、代次与持久结算 | 已受理与刷新失败分开；列表读取失败不会再次提交创建；取消和失败通过真实回执传播 |
| 会话、排队消息与历史 | Session UI / 编辑队列 / 分页与搜索 Port | Session EventLog、事务索引与可见投影 | 增量编辑与删除进入分页、恢复和 SQLite 可见索引；授权会话搜索有可续查游标 |
| 工作区与归档 | 文件 UI / 文件工具 → Workspace / Archive Reader | 工作区路径与文件读写边界；搜索缓存为派生状态 | 保留完整目录扫描和准确 EOF；本轮把停止与协程取消传入遍历，关闭目录流 |
| 附件与存储 | 导入、事件事务 → Asset 索引和 Session 数据库 | Session 事务提交事件与附件引用 | 提取无状态索引解除互相导入；原事务边界与历史数据恢复继续保留 |
| 记忆、调查与改进 | Memory / Finding / Improvement 当前入口 | 原 Memory Store 与 Session 来源事件 | 撤回线索同步退出工作上下文、重规划与改进档案；显示预览不承担来源身份 |
| 插件、MCP 与账号 | Tools UI / 插件注册 → MCP / 凭据 Owner | 原连接、授权、生命周期和 Registry | 连接与工具注册分开判断；本轮用错误类型贯通自有协议校验和隔离进程回执 |
| Android 权限与通知 | 设置 / 任务提示 → 系统权限与真实服务探测 | Android 系统授权、无障碍和通知监听服务 | 授权与连接分开；已授权未绑定时请求 rebind；未把打开设置视作完成 |

以上矩阵记录当前源码与关联测试的核查范围。真实服务账号、特定厂商后台行为、触屏与实际外部副作用仍需要对应环境的执行回执。

## 确认问题与根因修复

问题共七项：高 1 项，中高 3 项，中 3 项。

### 1. 【中】能力目录有基础工具，搜索结果却没有

`LocalCapabilityDiscovery.catalogue` 包含 CORE 和 OPTIONAL；原搜索复用了只搜索 OPTIONAL 的模型扩展匹配器。公开查询契约按 `read` 或“读取文件”查找时，基础能力从结果中消失。调用核实表明当前 Tools 主界面尚未消费 `discoverCapabilities`，所以此项属于契约缺口，未将它描述为已上线工具名搜索页面的体验故障。当前页面继续按插件、技能和连接器组织。

修复在同一个匹配器增加目录搜索范围，UI 包含 CORE，模型隐式预选继续只选择 OPTIONAL，INTERNAL 始终排除。没有另建目录、匹配规则或执行注册表。

证据：`LocalToolRouterTest.uiCapabilitySearchFindsCoreToolsWithoutExposingInternalTools`。

### 2. 【中】展示限额被误当作能力不可用

精确选择多个工具、搜索结果只展示一项时，原实现用被截短的命中列表推导 `unavailable`，把其他已注册工具误报为不可用。

修复从完整实时名称索引判断注册可用性；`limit` 只控制返回范围。模型执行仍独立消费真实注册、预算与授权事实。

证据：`LocalToolRouterTest.exactDiscoveryLimitDoesNotLabelRegisteredToolsUnavailable`。

### 3. 【中高】请求预算充足，仍被固定工具定义额度拒绝

路由预算和工具筛选各自又设置 24,000 Token 上限。合法的大参数枚举契约、用户明确选择的较多工具，即使得到更大请求预算，仍会被省略；上下文压缩无法改变这一硬编码天花板。

修复移除这两个重复上限，工具筛选消费调用方实际授予的定义预算；原上下文容量计算、基础工具成本扣除、缓存策略和预算不足的结构化回执继续生效。默认选取预算保持原配置，明确请求可按路由剩余容量扩大。

测试将大型契约真正注册进 ToolRegistry 后再筛选；并覆盖实际预算不足仍返回省略原因。

证据：`registeredSchemaCanUseTheCapacityGrantedByTheModelBudget`、`explicitSelectionUsesRealCapacityAboveTheOldDefinitionCeiling`。

### 4. 【中高】关闭搜索后，后台仍遍历目录

原搜索票据只在扫描结束时核验，UI 的 IO 协程也没有把取消传到同步扫描。完整扫描解除旧数量上限后，这个缺口会让用户取消的搜索继续消耗资源。

修复采用 `runInterruptible`，遍历检查线程中断和原查询票据；停止、替换或取消时退出并由 `use` 关闭目录流。查询失败清理自己的票据，不覆盖更新查询的状态。完整遍历、匹配排序和分页能力继续保留。

证据：`LocalWorkspaceTest.interruptedFilenameSearchStopsAndAllowsFreshSearch`，并回归原完整搜索、分页与目录变更测试。

### 5. 【高】后台恢复记录仍裁剪真实任务内容

原子任务 checkpoint 写入时只保留 48 个工具名、最终结果末 65,536 字符。前台放开工具数量后，重启恢复仍会改变已选工具集合；长结果也会丢失开头。

将原 checkpoint 升至 v3，完整保存选择集合和最终结果；继续读取原 v1/v2 记录，原格式的约束隔离在兼容读取中。恢复后的工具仍经过原作用域、allowlist、Team 授权和模型工具表准入，未扩大权限。

证据：`LocalSubagentContinuationTest.recoveryPreservesCompleteSelectedToolSetAndTerminalResult`，覆盖 180 项工具与超过旧结果长度的正文；原兼容、邮箱认领与终态校验测试继续通过。

### 6. 【中高】结果读取入口仍拿状态摘要当成果

Job 的内存输出是有界进度投影，持久摘要另有 8,192 字符裁剪；独立助手 UI 原先读取的 inspect 又只展示前 1,500 字符。即使运行记录保存了全文，团队、模型和独立助手仍读到不同长度的摘要。

新增无状态 `readLocalAgentOutput`，在原 Job 所有权校验后按准确 Job ID 读取原 Session checkpoint。团队结果、独立助手“查看结果”及 Agent 的 `job_output` 共用该读取逻辑。正常进度继续使用 Job 摘要，活跃轮次不会把上轮结果显示为本轮完成。终态缺少可靠结果时返回明确错误；旧格式达到裁剪长度的记录明确标注历史节选。

完整成果继续保存于原检查点，未新增结果文件库、状态同步器或第二套 Job 账本。

证据：`LocalAgentOutputReaderTest` 的完整长结果重开读取、其他 Job 隔离、终态缺结果及新 Activation 进度读取。

### 7. 【中】MCP 协商错误靠报错文字分类

原 worker 检查异常消息是否包含特定中文短句。异常措辞变化会丢失类别；普通错误正文包含相同短句又可能被误分类。

在自有协议校验处产生 `McpProtocolNegotiationException`，worker 仅根据类型发送稳定代码，外层沿原 IPC 还原类型。真实 RPC、传输和其他错误保持各自类别，远端正文不参与本地错误类别决策。

证据：`McpWorkerWireTest.protocolNegotiationFailureUsesSafeCategoryWithoutLeakingDetails` 以及 MCP 协议版本校验回归。

## 调整方向与约束的独立判断

- **应继续的方向**：明确所有者、统一真实入口和回执、内容完整持久化、可继续分页、让取消贯穿执行、由真实注册与请求容量决定能力准入。
- **本次最关键的公共短板**：完整事实与预览混用。同类问题同时出现在恢复、结果展示和目录可用性判断；修复应位于原事实边界，调用方统一读取。
- **合理保留的约束**：用户授权、跨会话归属、真实提供方协议、工作区边界、未知副作用对账、失效运行的提交栅栏、Android 系统权限和资源预算。它们承担真实隔离与正确性职责。
- **本轮消除的障碍**：重复硬额度、静默裁剪、无消费者的错误状态推导、取消只改变界面、通过人类报错文案决定运行类别。
- **修复顺序**：先保证持久记录完整，再统一结果消费；先区分注册状态与展示范围，再调整模型预算；最后验证取消、兼容、UI 与关联功能。
- **复用的审计规律**：SC-001 全入口闭环、SC-002 多入口汇聚和 SC-003 生产端与消费端对账。未为本轮另建治理门禁或重复规则体系。

## 实际验证结果

本地工具和离线依赖取自默认分支成功运行 `43560520369` 的开发工具链 Artifact，组件校验通过。构建采用 JDK 27、Gradle 9.8.1、单 worker 和进程内 Kotlin 编译；首次并行构建 daemon 退出后，已释放下载临时文件并按宿主资源重新执行。未修改项目测试断言来绕过环境失败。

| 模块 | 测试数 | 通过 | 失败 |
|---|---:|---:|---:|
| app | 3,608 | 3,608 | 0 |
| harness-core | 395 | 395 | 0 |
| harness-interop | 98 | 98 | 0 |
| harness-device-android | 35 | 35 | 0 |
| core | 27 | 27 | 0 |
| reference-validation | 5 | 5 | 0 |
| harness-runtime-android | 48 | 40 | 8 |

合计执行 4,216 项测试：4,208 项通过、8 项失败、0 项跳过。最终修改后的应用 3,608 项、MCP 98 项测试和 `:app:compileDebugAndroidTestKotlin` 再次回归通过；同轮 Gradle 返回 `BUILD SUCCESSFUL`。设备交互未在本地执行：宿主没有 `/dev/kvm`，未把测试代码编译写成触屏实测，也未把被远端新提交取消的快照 CI 写成绿色。

进程层失败清单：

1. `AgentTlsBackendTest.tlsToolEffectWritesOnlyTheServerWorkspaceAfterHostApproval`
2. `AgentWorkspaceEnvironmentTest.nativeProcessReturnsRealExitCodeAndRemoteWorkingDirectory`
3. `AgentWorkspaceEnvironmentTest.twoRemoteActivationsCannotBothEditTheSameObservedVersion`
4. `AgentWorkspaceEnvironmentTest.defaultRemoteTerminalUsesAvailableShellAndPreservesExitReceipt`
5. `AgentWorkspaceEnvironmentTest.editingAnExecutablePreservesItsExecutionPermission`
6. `AgentWorkspaceEnvironmentTest.fileEffectsAndFreshObservationBelongToTheRemoteRoot`
7. `AgentWorkspaceEnvironmentTest.anUnobservedRemoteWriteCannotOverwriteExistingFile`
8. `AgentWorkspaceEnvironmentTest.anObservedRemoteWriteMustNotOverwriteAnotherAgentsCommit`

文件操作错误包含 `PROCESS_ISOLATION_UNAVAILABLE: [Errno 38] Landlock ABI unavailable`；进程退出码为 125，TLS 用例因实际文件未写入失败。独立探测 `libc.syscall(444, 0, 0, 1)` 返回 `-1 / errno 38`，确认当前执行宿主不提供所需 Landlock ABI。本轮没有修改失败所在的运行层源码；相关 `AgentWorkspaceEnvironment.kt` 与 `process_sandbox.py` 相对成功工具链基线 `52f5b710` 无源码差异。复验需要支持该隔离能力的 Linux 宿主；本轮没有关闭隔离、跳过这八项或将它们计为通过。

静态核查：架构 3.0 边界、运行性能约束、UI 模块、设计系统、Android 安全、通知展示、构建基线、Kotlin 风险模式、测试质量与 `git diff --check` 已执行通过。全仓扫描覆盖所有受跟踪文件，显式 import 环为 0；静态候选仅用于调用关系复核，不作为无消费者代码的自动删除依据。

回归通过与失败均以本地实际输出和 XML 回执为准。当前代码审计和修复已形成可复核结果；完整运行放行仍需上述进程隔离与设备环境回执。

## 外部契约参考

- [Kotlin runInterruptible](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-interruptible.html)：将协程取消传播为阻塞代码的线程中断。
- [Kotlin cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)：取消需要执行边界配合。
- [Java Files.walk](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html)：目录遍历与流资源关闭。

- [MCP 2025-03-26 生命周期](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-03-26/basic/lifecycle.mdx)：Legacy 适配沿原已支持版本校验；现代 HTTP 仍由现有 SDK 处理当前协议。

## 提交时的并行改动整合

用户要求提交修复后，重新读取远端 PR，整合基线推进到 `e9a567eba074f6cf8ded533a7a05e811549f1967`；此前已对 `453c1e51` 组合版本完成应用/MCP 与 Android 测试代码编译回归。

- 保留远端新增的 `AppServerUiPort`、按需 Feature 装配、统一命令/状态读取及实际 UI 接入；保留按需 Schema 投影与旧团队接口别名清理。
- 远端已补充 CORE 搜索和统一排序；合并时保留其 `Resolution.ranked`，本轮只补充完整目录可用性判断，避免展示限额误报。
- 保留双方回归用例，继续验证 INTERNAL 不进入目录及大型契约按实际预算筛选。
- 上文“目录查询暂无页面消费者”描述固定审计快照；提交基线已经由 Tools 页面实际消费该契约，本轮整合保留这一接入。
- 在原 `android-capability-contract.yml` 增加运行层、core 与 reference-validation 测试，覆盖完整 JVM 模块及 Landlock 进程隔离路径。复用原 GitHub Actions Linux 验证环境，不关闭隔离，不新增定时任务。
- 本地整合后重新运行应用/MCP 单测、Android 测试代码编译、架构与统一入口契约检查；发布提交的 GitHub Checks 是宿主隔离和设备验证的独立回执。
