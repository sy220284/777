# 777 全仓功能逻辑核验与架构 3.0 收敛方案（2026-11-15）

> 仓库：`sy220284/777`。主线基准：`82a5c132`，在本次审计期间 #923 已合入最新 `main`（当时为 `06455974`）。实施分支：`audit/architecture3-full-callgraph-20261115` / PR #924。
>
> **证据分层**：本次 CI 对全部版本控制文件运行逐文件**静态**扫描；对关键 Chat/Work/Automation/Session/Tool/Model 路径进行源码阅读和逻辑复核。静态扫描得到的是导入关系与候选调用，不等同于 Kotlin 编译器解析、全量动态调用覆盖或手机实测。禁止把候选数量直接换算成错误数量。

## 一、结论与治理目标

777 的功能容量已足够，真正需要提升的是**从用户意图到可验证结果的直达率**。完整的功能应该只有明确业务入口、单一业务负责人、真实工具和副作用执行者、唯一可重建事实、完成/恢复结果与一个一致的界面投影。

采用六个产品 Feature（Shell、Chat、Work、Automation、Tools、Settings）和 Shared Capabilities（Session、Model、Agent、Tool、Memory、Resource、Usage、Event）。`LocalRuntimeKernel`只管理进程初始化、生命周期及启动恢复。**不通过新增统一超级调度器解决协调过多的问题；将管理权收回已有 Owner。**

主要评价指标从“模块/类数量”改为：一次真实用户任务需要多少次额外模型回合、重复授权/状态检查、无效工具表重建、跨组件写入，以及失败后恢复到可验证结果的成功率。

## 二、代码级基准及可复核产物

GitHub Actions `777 架构 3.0 全仓调用关系审计` 在 PR #924 上执行现有 `tools/audit/scan-callgraph.py`，保留 `callgraph.json` 与 `callgraph.md` 为 30 天 CI Artifacts；所有生产、测试、支撑、非代码文件均建有目录索引。

| 指标 | 初始 CI 基线 |
|---|---:|
| Git 跟踪文件 | 2,356 |
| Kotlin / Java 生产源码 | 1,173 |
| Kotlin / Java 测试源码 | 718 |
| 静态 import 关系 | 5,576 |
| 词法调用候选关系 | 6,204 |
| 跨产品 Feature 候选（纠正分类后） | 4 |
| 静态 import 强连通环组 | 6 |
| 未被本次 import 关系定位的生产源码候选 | 284 |
| 内部 import 词法无法定位候选 | 113 |
| 包目录与 package 不一致候选 | 1 |

扫描器原先把 `local/tools/*` 当作与 Chat、Work 同级的产品 Feature，误将 **32 条合法 Shared Tool 使用关系**计为跨 Feature 引用。现已把共享 Tool Capability 从产品 Feature 判定中剥离，CI 的候选由 36 降至 4。余下四条均从 Automation 指向 Chat/Work 的公开契约（用户活动 Port、Chat 自动执行 Port、Work 自动执行 Port 和 Chat 自动执行 Policy）；源码抽查证实这是预期的调用方向。

单个包目录候选为 `app/src/main/java/com/labteto/dshmobile/uicontract/model/LocalModelValues.kt`，其实际 package 为 `com.labteto.dshmobile.local.model`。它处于独立 UI 契约目录，当前由多处引用；属于**待评估的包归属问题**，不是凭路径即可修复的运行错误。

**静态不可判定项**：注入装配、运行时动态分派、反射、Android 系统回调、设备权限和网络连接、数据库竞态、模型供应商超时均需要编译/单测/设备/真实场景补充验证。

## 三、所有产品功能的目标使用逻辑与归属

| 功能域 | 用户可见目标 | 实际业务 Owner / 关键入口 | 唯一完成事实 | 收敛重点 |
|---|---|---|---|---|
| Shell | 首页、导航、返回、会话切换、通知打开 | `LocalFeatureCatalog`、`AppRoot`、导航贡献 | 路由与 Feature 状态投影 | Shell 只负责展示和跳转，不自行恢复任务 |
| Chat | 单聊/群聊、人物、关系、场景连续性、分支、日记、主动消息 | `LocalChatExecutionCoordinator`、`LocalChatDirectTurnExecutor`、`LocalGroupChatTurnExecutor`、Chat Automation Port | Chat 状态 + Session EventLog 中真实消息 | 合并同一轮的上下文刷新与完成结算，不跨会话复制隐式状态 |
| Work | 任务规划、步骤、工具、文件、成果、续跑、Agent 团队 | `LocalWorkExecutionCoordinator`、`LocalWorkAgentTurnExecutor`、`LocalWorkRunRegistry`、`LocalWorkTurnToolRuntime` | Work-owned Run/Goal + Session EventLog、执行回执 | 从目标直达工具和可核验成果；压缩重复选择、投影与恢复路径 |
| Automation | 创建/暂停/恢复/执行自动任务、Chat 主动互动、结果通知 | `HarnessAutomationWorker`、`AutomationExecutionRegistry`、`LocalAutomationRuntime` | Automation Store 的任务代次、状态、结算记录 | 只通过 Chat/Work Port 执行，排程负责触发而不重做业务 |
| Tools | 内置工具、MCP、LSP、GitHub、技能、插件、授权、工具信息 | `LocalToolExecutionCoordinator`、`LocalToolRouter`、`LocalToolSchemaProjection`、`ToolRegistry`、Tools UI Facade | 真实已注册工具 + 每轮曝光集合 + 本次执行批准与回执 | 注册、发现、曝光、审批、执行职责分明；用户无需重复配置 |
| Settings | 模型、设备、系统权限、通用偏好、使用情况 | Settings Feature 的 UI/Port；具体配置归各能力 Owner | 各配置 Owner 实际状态 + 系统权限查询 | 页面只发起配置变更并展示权威状态，不充当第二份配置库 |

### 3.1 Chat 主链

`Chat UI → Chat Feature Port/Turn Owner → 会话上下文/人物身份 → 冻结模型请求 → 模型回复或真实工具回执 → Session EventLog → Chat 分支/连续性/消息投影 → UI`。

群聊、消息编辑/重发、回复再生成、人物日记是 Chat 领域语义，负责撤销失效上下文的也是 Chat Owner。主动聊天由 Automation **请求** Chat 公开执行 Port，采用 Chat 原有的事务/结果提交；不另开一套人物记忆和聊天消息账本。

审计重点：普通后续消息与编辑后重发是否使用同一撤销/归并规则；后台归并完成之前的新消息能否正确继承上下文；主会话切换与自动消息投影是否串位。

### 3.2 Work 主链

`Work UI/自动化 Port → 会话和 Run 准入 → 目标/计划/Inbox → 单个 Agent Turn → 模型工具表 → ToolRegistry + 权限审批 → 工具结果/文件变更证据 → Goal 验收 → EventLog/Run 快照 → 进度/成果/结算 UI`。

`LocalWorkRunBinding`拥有本轮 Work 状态和工具选择；`LocalWorkRunRegistry`维护活跃 Work Binding 和展示镜像；`LocalAgentRunCoordinator`维护通用运行身份与检查点；`SessionExecutionRegistry`维护互斥的 Session 执行租约。它们面向不同事实，不按“类数量多”删除。

**已定位的具体干扰**：`capability_search(select:...)`在共享执行器里精确替换工具集合，但 `LocalWorkTurnToolRuntime.schemas()` 后续每一步又无条件将固定插件、刚发现的 MCP、团队/附件工具重新加入工具表。这会抵消用户或 Agent 精确聚焦的行为、增加请求 Token 和选择成本。PR #924 已在 Work 现有工具集合上加入精确聚焦标记：相同任务下后续步骤保持原选择；新真实用户任务则恢复自动发现。未增加额外 ToolRegistry、授权 Owner 或执行队列；真实 ToolRegistry 与每次审批仍为唯一执行事实。

### 3.3 Automation 主链

`Automation Scheduler/Worker → 任务代次与执行所有权 → 合适的 Chat/Work Port → 目标 Session 租约 → Feature 执行与证据 → Automation 结算 → 通知/任务详情 UI`。

`AutomationExecutionRegistry`的锁保护“同一任务重复投递”，`LocalSessionRuntimeRegistry`保护“同一 Session 同时被不同任务修改”，Work Run 的所有权保护“同一 Work Binding 的实际执行”；这几个锁作用范围不同。后续只有发现两者对**同一事实进行写入裁决**时，才合并；删掉任务代次锁会造成旧的定时投递越过新任务状态，删掉 Session 租约会引入并发写入。

### 3.4 Tools / GitHub / MCP / 插件 / Skill 主链

`注册 ToolRegistry → 真实可发现目录 → 按当前任务/用户显式选择模型可见 Schema → 模型调用 → 权限和参数复核 → 单次实际执行 → 类型化结果+副作用回执 → 当前 Run 持久事实`。

- CORE 能力按注册自动可用；OPTIONAL 能力按任务预选或 `capability_search` 精确加载；
- 插件安装、授权、连接状态、工具列表、模型可见能力、每次工具执行授权是不同事实，界面分别展示“已安装”“连接正常”“已授权”“本轮可用”；
- MCP/Code Mode/GitHub 结构化结果与文本呈现分离；嵌套调用仍须逐级使用真实参数与批准身份；
- 工具表 Token 预算在一个 Selection 阶段统一裁决；不会因失败重复让模型猜测工具名称，也不通过假成功绕过真实工具检查。

### 3.5 Model / Context / Memory / Multimedia

`LocalModelRequestCoordinator` 是模型请求、Token 预算、路由冻结、流式预览、溢出恢复和模型证据的组合入口；Provider/Protocol 与请求冻结事实属于 Shared Model。稳定 Prompt 与动态上下文分别处理，减少重复发送；真实账户与模型能力决定可用工具和媒体生成，不让 UI 通过按钮展示猜测能力。

Chat 人物记忆、Work 任务上下文和共享长期记忆具备不同语义：应通过稳定接口组合，避免复制同一记录。图片/视频/音频/附件生成过程以实际工具结果和文件引用驱动，独立进行长任务进度更新，避免把大结果复制进每一步模型历史。

### 3.6 Session / Files / Workspace / Jobs / Agent 团队

Session EventLog 为历史和恢复唯一持久源，分页/检查点/投影从同一水位推导。工作区文件以真实项目根目录、文件版本和工具回执核实。Job Inbox、子 Agent、团队协作共享资源预算与准入，团队的 task/owner/消息分别保有独立事实，子代理不另建跨 Session 账本。

恢复采用**已确认结果直接结算、可安全继续的有界续跑、未知写入效果先对账**三种判定。取消应终止真实 Job，并保持已执行副作用回执；既不重新执行未知写操作，也不把一次工具错误简化成“让模型重新试试”。

### 3.7 Android 设备及 UI 交互

首页优先显示 Chat 和 Work 主入口，工具与设备配置按任务需求就近引导；路径尽量保持三级以内。界面数据来自 Feature Projection / Port，状态明确区分“已打开系统设置页”“系统确实授予权限”“当前设备支持”“当前模型/连接支持”。

系统权限应遵循按需授权、从系统重新检测、缺失项修复的实际流程；通知、无障碍、文件、虚拟屏等能力分别验证。持久执行使用当前 Android 后台/前台服务和 WorkManager 的事实，不设计无法由平台保障的长期驻留状态。

## 四、六组初始静态循环引用的处置

| 关系组 | 复核对象 | 处理原则 |
|---|---|---|
| `MainActivity / HarnessAutomationScheduler / DshNotifications / AppRoot / LocalHarnessScreen / TasksViewModel` | 通知打开、路由、任务 UI 与调度器 | 通知只携带意图和任务身份，具体页面恢复由 Shell / Automation 归口；确认反向调用后解环 |
| `LocalAgentInboxPersistence / LocalQueuedUserMessageEditor / LocalSessionTranscriptPager / LocalSessionTranscriptProjection / LocalTranscriptRuntimeIndex` | Inbox 消息提交与消息历史水位 | 明确同一序号的写入 Owner，读侧只能消费投影，避免读写双向回调 |
| `LocalModelException / ModelAdmissionSafety` | 错误归类与模型准入 | 错误类型与准入判定维持单向依赖，禁止异常处理回调控制准入 |
| `LocalWebProvider / LocalWebSearchClient` | 搜索适配与网络客户端 | 区分中立接口/实现/网络策略，实际依赖方向应是调用方→契约→适配器 |
| `TokenUsageRecordAdapters / DeepSeekBilling` | 用量数据和计费换算 | 把转换逻辑归于 Usage，Provider 只提供原始计费事实 |
| `SqliteLocalAssetCatalog / SqliteSessionEventDatabase` | 附件数据库与 Session 事件数据库 | 明确持久化事实、事务及生命周期共用层，避免两个存储组件互相拥有对方 |

这六组来自最初的文件级显式 import 强连通分量。经逐组查明原因后，现已全部处理：
- 通知从 Android Manifest 的真实 Launcher 组件启动，去掉对 MainActivity 的逆向调用，并加上设备路由测试。
- Session 的持久队列事件标识由共享 Session 所有；Agent 继续处理队列入队、确认和撤回，消除 Session→Agent 的类型依赖。
- 模型请求准入枚举、网页响应返回对象各收归中立数据契约，解除执行器彼此反向引用。
- 用量计费循环中发现的无效 import 已移除；附件索引抽为同一 SQLite 事务使用的无状态投影，数据库与目录管理器依赖单向化。

截至提交 `4fe68bf0`，**静态 import 环已经从 6 组降至 0 组**，架构 3.0 和性能不变量检查通过。静态环数量作为结构回执，功能正确性仍需 JVM、Android 设备与恢复验证。

## 五、具体重构决策：删除什么，保留什么

**优先收敛**：重复的自动工具预激活；每步重新推翻显式工具选择；UI 再建一份可写 Run/权限状态；相同消息/成果的多份持久账本；前台与后台重复判断同一会话的完成事实；已经由 EventLog 推导且没有额外用途的状态复制；绕过公开 Port 的旁路。

**保留并明确责任**：会话租约、任务代次锁、Run 所有权、工具执行签名与回执、权限审批、模型请求身份冻结、真实副作用保护、可恢复 Job Inbox、按需加载、模型上下文预算、多模态适配以及 Android 系统许可检查。这些保护真实正确性，不能机械合并成一个大 Manager。

**暂缓凭空移除**：静态扫描提示的 284 个“未被 import 引用”文件。Android manifest、Hilt DI、注解生成、反射、资源回调、顶层 Kotlin、外部契约等均可使静态入口不可见。逐个完成“注册点/调用方/运行证据/删除前后回归”才可确认为可删除代码。

## 六、可落地改造与执行顺序

| 优先级 | 改造对象 | 目标和交付 | 验收 |
|---|---|---|---|
| P0 | Work 工具表选择 | 一次显式聚焦在同一任务的后续模型步骤继续生效，新任务再自动选择；已在 #924 写入生产代码和测试 | 跨步骤精确选择用例 + 完整 JVM/Android UI 编译 |
| P0 | 全仓调用关系与职责基线 | 将 Shared Tool 从产品域候选中正确排除，输出全文件机器可查证据；已在 #924 完成 | CI 1,173 生产源码，准确区分 4 条公开契约关系 |
| P0 | 3.0 边界守卫 | 在同一审计工作流调用已有架构边界与性能约束脚本，而非另建重复守卫 | 每次相关改动 CI 实际通过 |
| P1 | Session/Agent/Work 运行与恢复 | 逐入口核对 owner、lease、取消、持久 Inbox、未知写入确认；如有重复 Owner，仅保留对应权威事实 | 普通/后台/自动/中断/冷恢复/重复提交交叉测试 |
| P1 | UI 状态源和权限 | 全 UI 模块回查直接读写 Store/Coordinator 的旁路，收归各 Feature Port/Projection | Chat/Work/Tools/Settings/Automation/通知的真实 Android 交互测试 |
| P1 | 6 组循环候选 | 已逐组处理，模型状态、Web 结果、附件索引、通知路由、Inbox 事件、用量导入均已收敛 | 静态环 6→0；完整 JVM/Android 回归按最终 SHA 判定 |
| P2 | 大文件和过时适配 | 在业务 Owner 明确后按稳定职责拆开特别复杂的文件，逐个确认调用关系、性能、UI 一致性 | 无额外管理层，入口、代码覆盖、构建速度与执行链指标改善 |

每项按“当前源代码 → 真实入口与调用者 → 单一事实 Owner → 影响/风险 → 修改 → 负向复核 → JVM/Android/设备验证 → 收敛记录”执行。对于跨文件修改，要检查所有调用方与恢复路径，不能只通过局部单测判完成。

## 七、已落实的职责整合（本 PR 的实际代码变更）

| 实际改动 | 先前问题 | 修改后单一责任 |
|---|---|---|
| 工具精确聚焦 | `select:` 之后 Work 后续步骤把固定插件、MCP、团队工具重新加回工具表 | `LocalRunToolSelection` 随 Work Run 持有选择与任务变化；显式选择一直有效到新任务，工具注册、授权仍归共享 Tool Registry |
| 重复优先级管理 | `LocalWorkToolPriority` 独立保存任务变化，旁边又有工具集合保存聚焦状态 | 合并到 `LocalRunToolSelection`，删除旧独立类和重复测试文件，并保留原任务切换断言 |
| 内置工具精确选择 | 仅选择 CORE 工具时旧 OPTIONAL 扩展残留 | 清空可选工具集合，完整保留实际 CORE 固有暴露，确保提示与模型下一步工具表一致 |
| 主聊天会话采样 | UI 直接注入模型内部 `LocalConversationSampleCoordinator` | `LocalSessionUiFacade` 承接只读会话采样，UI 只调用 Feature 边界 |
| 工具配置界面 | Tools 和主聊天页面直接注入网络搜索配置状态 | `ToolsManagementUiPort → LocalToolsUiFacade → LocalNetworkSearchSettings`；两个 UI 共享同一配置事实而无需自己注入 Store |
| 审批模式界面 | 主聊天页面直接注入 `LocalApprovalPreferences` | `SettingsUiPort` 投影同一审批权威状态；实际审批执行仍由 Work/Tool Owner 核对 |
| 静态审计分类 | 扫描器误把共享 Tool Capability 当作跨产品 Feature，引入 32 条噪声 | 分类按产品所有权识别，保留所有原始边与逐文件证据，缩小实际复核候选 |
| UI 架构校验 | UI 直连协调器可被普通编译忽略 | 使用现有 3.0 边界、性能检查加入 PR 的全仓审计工作流，发现问题后修复源码而非放宽规则 |

该表只记录本 PR 确实变更的代码。以上结果和下述验收应以 PR 最终 SHA 的实际 CI 回执为准；六组静态环和其他业务流程仍按照上一节的证据门槛进一步核实。

## 八、最终验收边界

1. **逻辑**：六大 Feature 有唯一业务归属；UI、通知、后台、自动化入口汇入相同业务合同；无已证明的平行可写状态源和错误工具表重建。
2. **可靠性**：任务被取消、切换 Session、被系统杀进程、网络中断或工具副作用未知时，始终能复原有证据的最终结果或明确的待处理状态。
3. **效率**：按真实任务测量模型回合数、工具 schema Token、重复工具发现次数、无效审批次数、上下文膨胀及冷恢复开销。目标是减少无效中转；安全校验只保留必要且有主责的检查。
4. **用户体验**：一件事一个进度入口、清晰结果与失败补救；安装/授权/模型配置体现已就绪能力；关键交互不多于三级导航。
5. **验证**：全仓静态审计及 3.0 守卫通过；全部 JVM 测试、Android UI 编译、主要模拟器旅程通过；涉及真实模型服务/厂商权限/后台驻留的项目，另记录目标手机实测回执。

当前状态以 PR #924 最新提交与对应 GitHub Actions 判定。已落实工具聚焦、UI 功能接口、通知启动、模型/Web 数据契约、Session↔Agent 事件边界、附件索引、排队消息增量持久化；已确认的显式 import 环清零。完整执行体验及其余动态注册/设备差异仍必须以真实回执逐项验收。

## 九、排队消息长期效率与恢复一致性（实际实现）

**原路径**：用户编辑或移除尚未执行的排队消息时，先扫描已有 Session 历史，再将整段变更后的消息数组重新写入 `agent/inbox/spliced` 的 `transcript` 字段。会话越长，每次操作越占事件日志空间。

**现路径**：仍由原队列 Owner 原子确认操作，事件中只追加一项 `transcript_delta`（消息编号、edit/remove、需要时的完整单条消息）与精确的 `transcript_index`。Session 事件日志继续是唯一持久事实，未建立第二套历史数据库。

- 正向恢复：`projectSessionTranscriptTail` 按事件顺序在原位置替换或移除目标消息；已确认历史顺序稳定。
- 逆向分页：`LocalSessionTranscriptPager` 将最新变更随游标向历史事件传播，仅在读到原始消息时应用，天然合并多个较新的编辑；保留原版全量快照事件读取。
- 索引恢复：`projectLocalTranscriptRuntimeIndexTail` 消费随事件提交的索引，SQL `visible_hint` 将合法的队列增量加入可见分页查询。
- 事件权限：仅 `LOCAL_AGENT_INBOX_EVENT_TYPE` 且 action 为 `LOCAL_QUEUED_USER_TIMELINE_ACTION` 的事件可修改对话投影；工具结果中的伪造同名字段不得更改对话、索引或 SQL 可见提示。
- 资源文件：SQLite 附件引用仍随同一 Session 事务提交，修订消息中的合法媒体资源继续通过原图像块遍历索引。

专项回归已提交：多次编辑和删除、360 条历史消息跨 23 条分页读取、文件事件日志重放、SQLite 关闭重开及可见索引、伪造工具事件不应改动历史，另有通知路由设备测试。该项的实际性能收益来自**事件写入体积不随旧历史总长度重复增加**，不把尚未实测的耗时或倍数写作性能结论。

## 十、明确保留的迁移与兼容能力

`LocalLegacySessionMigration`、`LocalLegacySqliteMigration`、`LegacyUsageProjection` 均参与旧用户本地数据无损迁移与累计用量校准，经过代码复核予以保留。旧的 `LegacyProviderUsageCompat` 只是对 Model 内同名且已存在的 `normalizedForAccounting` 扩展包装；经代码引用核查，无当前功能消费，作为真实冗余删除，且无需改变既有会话和计费行为。

后续按同样证据标准审查静态无引用候选：先从动态入口（Manifest、Hilt、反射、资源、公开 ABI）、数据迁移与测试调用验证，再决定删除。静态未引用不作为直接删除理由。
