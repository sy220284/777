# 架构 3.0

> 本文是 777 当前唯一系统架构权威文档。旧版“UI → capability runtime → LocalHarnessEngine → coordinator”的架构描述已经被本版本替代。
>
> 架构 3.0 的目标是：**模块化单体 + 层级化 Feature 组合 + 共享能力契约 + 极薄运行内核**。迁移按阶段推进；本文同时记录目标边界和当前迁移状态，禁止把尚未完成的迁移描述成已完成。

## 1. 核心结论

777 不再把“拆更多 Coordinator / Runtime”本身视为架构优化。

架构 3.0 固定以下所有权模型：

```text
子功能
  ↓ 注册到
所属 Feature
  ↓ 注册到
LocalFeatureCatalog
  ↓ 由
Application Shell / Feature Host
```

产品 Feature 之间禁止直接依赖内部实现。跨 Feature 协作只能通过共享能力契约或明确的 Feature Port。

共享能力继续向下依赖运行内核；运行内核不理解人物、日记、计划、Todo、GitHub 等具体产品业务。

## 2. 总体架构

```text
┌────────────────────────────────────────────────────────────┐
│                     Application Shell                      │
│ Android / Activity / Compose / Navigation / 生命周期宿主   │
└───────────────────────────┬────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│                    Feature Composition                     │
│                  LocalFeatureCatalog                       │
│                                                            │
│   ChatFeature   WorkFeature   AutomationFeature            │
│   ToolsFeature  SettingsFeature  FutureFeature...           │
└───────────────┬───────────────┬───────────────┬────────────┘
                │               │               │
                ▼               ▼               ▼
          Feature 内部子功能聚合与业务状态所有权
                │               │               │
                └───────────────┼───────────────┘
                                ▼
┌────────────────────────────────────────────────────────────┐
│                  Shared Capabilities                       │
│                                                            │
│ Session │ Model │ Agent │ Tool │ Memory │ Resource         │
│ Usage   │ Event │ Runtime │ Persistence │ Diagnostics      │
└───────────────────────────┬────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│                   LocalRuntimeKernel                       │
│                                                            │
│ Run identity / ownership / transaction / cancellation      │
│ resource lease / recovery / lifecycle                      │
└───────────────────────────┬────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│                Platform / Infrastructure                   │
│ harness-core / Android runtime / MCP / LSP / device        │
│ persistence / network / third-party integrations           │
└────────────────────────────────────────────────────────────┘
```

## 3. Gradle 模块边界

当前构建基线使用 Kotlin 2.2.10、JVM 21 与 JDK 21+；开发工具链版本与安装方式由 `docs/DEVELOPMENT.md` 维护。构建工具版本变化不改变本文的 Feature / Shared Capability / Kernel 所有权边界。

架构 3.0 不把每个产品 Feature 都拆成 Gradle module。

现有模块继续承担真正的二进制、平台和协议边界：

| 模块 | 职责 |
|---|---|
| `app/` | Android 组合根、产品 Feature、UI、运行内核装配 |
| `core/` | 远程 Harness Web 协议链 |
| `harness-core/` | 平台无关 Agent、工具、资源与会话核心契约 |
| `harness-runtime-android/` | Android 进程运行时与持久终端 |
| `harness-interop/` | MCP / LSP / GitHub 等互通能力 |
| `harness-device-android/` | Android 设备能力 |
| `mock-harness/` | 协议与行为测试服务端 |
| `reference-validation/` | 官方语义与差分验证 |

只有当某个能力拥有明显更小且稳定的依赖集合、独立测试/发布价值，并且不会制造循环依赖时，才升级为新的 Gradle module。

## 4. Feature 层

Feature 是产品业务的一级所有权边界。

一个 Feature 必须拥有：

- 自己的业务状态；
- 自己的子功能组合；
- 自己的领域规则；
- 自己的 UI / 路由贡献；
- 自己的持久化适配边界；
- 自己的诊断与测试；
- 对外最小 API 或 Port。

Feature 不得：

- 直接读取另一个 Feature 的 Store / Coordinator / mutable state；
- 把跨 Feature 规则复制到多个入口；
- 为调用方便直接穿透到 `LocalHarnessEngine`；
- 通过全局 Service Locator 获取任意内部对象；
- 把 UI 页面本身当成业务所有权。

### 4.1 ChatFeature

目标聚合：

```text
ChatFeature
├─ Persona
├─ Gallery
├─ GroupChat
├─ Diary
├─ CharacterMemory
├─ CharacterLife
├─ BehaviorTuning
├─ ReplySuggestion
├─ ChatSend
├─ ChatRecovery
└─ ChatQuality
```

人物、图集、日记、关系、生活状态和群聊属于 Chat 领域。其他 Feature 只能通过 Chat 暴露的能力契约使用这些行为。

### 4.2 WorkFeature

目标聚合：

```text
WorkFeature
├─ WorkSend
├─ Plan
├─ Todo
├─ Goal
├─ Approval
├─ Question
├─ RunCenter
├─ BackgroundJob
├─ WorkContext
├─ WorkRecovery
└─ OutputQuality
```

Work 的计划、Todo、目标、审批、问答和运行中心属于同一业务域，不继续散落在 Engine、UI 和 jobs 包之间形成多点所有权。

### 4.3 AutomationFeature

目标聚合：

```text
AutomationFeature
├─ TaskCatalog
├─ Scheduler
├─ Planner
├─ ChatAutomation
├─ WorkAutomation
├─ Webhook
├─ Settlement
└─ Recovery
```

Automation 不允许进入 Chat / Work 内部实现。需要执行聊天或工作时，依赖明确 Port，例如 `ChatExecutionPort`、`WorkExecutionPort`。

### 4.4 ToolsFeature / SettingsFeature

ToolsFeature 负责工具管理、MCP 集成和用户可见工具入口；运行时真正的 Tool execution contract 属于 Shared Capability。

SettingsFeature 负责设置体验和配置入口；模型身份、凭据解析、运行时资源等真实能力仍由对应 Shared Capability 所有。

## 5. 子功能注册与 LocalFeatureCatalog

架构 3.0 将“功能页列表”升级成正式的产品功能组合关系。

规则：

1. 每个子功能路由必须且只能属于一个 Feature。
2. Feature 注册到启动期不可变 `LocalFeatureCatalog`。
3. Catalog 是产品功能组合事实源，不负责具体业务执行。
4. Catalog 在应用运行期间不可动态增删。
5. 新 Feature 可以通过编译期组合加入系统，但不能靠运行时任意 `register/unregister` 改变产品结构。

当前已建立完整的启动期 Feature Catalog：

```text
LocalFeatureCatalog
├─ Shell
│  └─ Home
├─ Chat
│  ├─ PersonaGallery
│  └─ Diary
├─ Work
│  ├─ Workspace
│  └─ RunCenter
├─ Automation
│  └─ Tasks
├─ Tools
│  └─ Tools
└─ Settings
   └─ Settings
```

当前 Catalog 已成为本机功能导航解析和功能页面状态枚举的事实源；页面内容、Back policy、Drawer entry 与 restore policy 均由各 Feature contribution 持有，Shell 只保留导航宿主执行。

## 6. Shared Capability 层

Shared Capability 是多个 Feature 可以安全复用的纯能力边界。

### Session Capability

`LocalSessionStorageRuntime` 统一持有 Session snapshot Repository/Coordinator，并复用唯一 `LocalSessionEventLogRegistry`；Feature 的持久化不得再借道 Engine 私有 Session 对象。


负责：

- Session 读取、写入与生命周期；
- Session owner / lease；
- 删除与维护事务；
- EventLog / transcript 权威事实；
- 恢复时的所有权裁决。

Chat、Work、Automation 都可以依赖 Session Capability，但不得各自建立第二套 Session 所有权。

### Model Capability

负责：

- profile / account / auth identity；
- provider / protocol / base URL；
- 冻结 route；
- provider adapter；
- streaming / replay；
- 模型能力快照。

一次 Run 启动后模型身份不可因前台设置变化而漂移。

### Agent Capability

负责：

- Agent run identity；
- 主 / 子代理运行契约；
- checkpoint；
- continuation；
- run recovery；
- tool result continuation 语义。

### Tool Capability

负责：

- 工具注册；
- capability exposure；
- approval boundary；
- tool execution；
- structured result；
- side-effect 语义。

产品 ToolsFeature 与 Tool Capability 必须区分：前者是产品功能，后者是运行能力。

### Memory / Resource / Usage / Event

这些能力继续保持单一事实源，并允许多个 Feature 复用；任何 Feature 不得为了局部方便复制第二套账本、调度器或事件源。

## 7. LocalRuntimeKernel

`LocalRuntimeKernel` 是架构 3.0 最底部的跨 Feature 运行时核心。

最终只保留：

- Run identity；
- Session / run ownership；
- 跨域事务边界；
- cancellation propagation；
- resource lease；
- recovery 入口；
- 系统启动、关闭与 Feature 生命周期协调。

Kernel 明确不应该理解：

- 人物、图集、日记；
- 群聊成员；
- Work 计划 / Todo / Goal；
- GitHub Token；
- MCP 设置页面；
- 具体 UI 页面。

当前旧 `LocalHarnessEngine` 已删除。`DshApplication` 显式启动 `LocalRuntimeKernel`；Kernel 仅持有进程 start-once、生命周期作用域、bootstrap / recovery 触发与初始化错误投影。具体 Feature / provider 装配位于应用组合根，通过中立 `LocalRuntimeBootstrapPort` 接入；Runtime Kernel 不反向依赖产品 Feature internal。

## 8. 状态模型

现有 `LocalHarnessState` 仅保留为 Runtime-owned 的兼容聚合与只读投影容器；领域状态已经拆分，Feature 不得持有完整聚合可写入口：

```text
KernelState
├─ lifecycle
├─ owner
└─ resources

ChatState
├─ persona
├─ gallery binding
├─ continuity
├─ group
└─ reply suggestions

WorkState
├─ plan
├─ todo
├─ goal
├─ approval
├─ question
└─ jobs

ModelState
└─ route / profiles / configuration
```

UI 继续只消费窄投影，例如 Chat surface、Work surface、Shell state；高频 streaming 保持独立，不重新塞回聚合状态。

## 9. UI 与导航

UI 只依赖 Feature API / projection，不依赖 Store、Coordinator 或 Kernel 内部实现。

目标导航链：

```text
Compose Shell
  ↓
LocalFeatureCatalog
  ↓
Feature route contribution
  ↓
Feature UI
```

路由归属和页面状态枚举已切到 `LocalFeatureCatalog`，并由最终架构门禁永久约束。

中央 `LocalFeaturePageContent` 的产品页面 `when` 已删除；各 Feature 自己贡献页面渲染、Back ownership、Drawer 入口和恢复策略，Shell 只按 `LocalFeatureCatalog` 与 contribution 契约执行导航。

## 10. Product Feature 与 Runtime Plugin 的区别

两种扩展机制禁止混为一套：

| 机制 | 适用对象 | 生命周期 |
|---|---|---|
| Feature Catalog | Chat / Work / Automation / Tools 等产品功能 | 启动期组合，运行时不可变 |
| PluginCatalog / PluginManager / PluginRegistry | MCP、设备、运行时 provider、可替换工具插件 | 支持受控运行时启停/替换 |

产品 Feature 不采用任意热卸载，避免 Session 正在运行时业务解释器突然消失。

## 11. 单向依赖规则

允许：

```text
UI
↓
Feature API
↓
Feature internal
↓
Shared Capability
↓
Kernel / Platform
```

禁止：

```text
Kernel → Feature
Shared Capability → Feature
Chat internal → Work internal
Work internal → Chat internal
Automation → Chat/Work internal
UI → Coordinator / Store
Feature Runtime → LocalHarnessEngine 纯转发继续增长
```

跨 Feature 调用必须通过稳定 Port，且 Port 归属于提供能力的一侧。

## 12. 迁移阶段

> 本节记录架构 3.0 从旧 Engine 架构迁移到当前最终边界的历史时序，用于解释来源和设置永久防回归门禁。
>
> 本节中的“仍需”“完成时”“后续”等措辞均指对应阶段当时的迁移条件，不代表当前允许旧 Engine / bridge / 跨层依赖继续存在，也不作为现行审计的合法中间状态。当前审计以本文前述最终所有权、当前阶段状态、§15 架构门禁和当前代码事实为准。

### 阶段 1：冻结 Kernel / Shared Capability 边界

状态：**已完成。**

- Session ownership 正式进入 `local.runtime` 共享运行层，前台、Automation、维护和删除继续共用同一 owner 事实源；
- Agent run identity、迟到提交栅栏与 recovery coordinator 正式进入 `local.runtime`，Feature 不拥有第二套 run owner；
- 进程唯一 `HarnessResourceScheduler` 从 `LocalHarnessEngine` 移交 `LocalRuntimeStateStore`；
- 前台 transcript 投影游标由 `LocalRuntimeStateStore` 统一持有，Feature 后续持久化不再依赖 Engine 私有游标；
- 共享 Runtime 自行投影可见 Kernel 资源状态；`LocalWorkRunRegistry` 单向订阅资源快照并维护脱离前台的 Work 绑定，`LocalHarnessEngine` 不再承担 Runtime → Work 的资源桥接；
- Automation 规划模型请求直接从共享 Runtime 获取 `MODEL_REQUEST` 租约，对应 Engine 转发入口删除；
- cancellation / recovery 继续以 Session owner、Agent run checkpoint、Foreground interaction owner 为共享边界；
- 门禁锁定上述所有权，禁止 Engine 重新持有资源调度器。

### 阶段 2：领域状态拆分

状态：**已完成，继续保持单一事实源。**

- `LocalHarnessState` 已拆出 Chat / Work / Kernel / Model 领域状态；
- UI 已通过 Chat / Work / Shell 投影消费状态，高频 streaming preview 独立；
- UI 对 `LocalHarnessState` 的聚合状态豁免已清零，门禁同时检查 stale allowlist，已迁 UI 不得重新回读聚合状态；
- 已迁字段禁止重新平铺回聚合状态，不保留长期双读/双写。

### 阶段 3：建立 ChatFeature / WorkFeature

状态：**代码闭环；实时验证状态以 PR / CI 为准。**

- Runtime 的 `engine.xxx()` 行为逐项迁入所属 Feature；
- 每迁走一项立即删除对应 Engine 入口；
- Feature internal 只依赖 Shared Capability / Kernel 契约；
- 禁止用“Port → Engine 原样转发”冒充完成；
- 完成时 ChatRuntime / WorkRuntime 对 `LocalHarnessEngine` 直接引用必须为 0。

当前 Work 审批与后台任务子阶段已落实：

- `LocalWorkApprovalCoordinator` 拥有全局自动审批切换、待审批授权、回合设备授权及撤销；WorkRuntime 直接消费该能力，Engine 对应业务入口删除。
- `LocalApprovalPreferences` 与 `LocalSessionEventLogRegistry` 为共享注入对象；全局自动审批以设备级持久配置为权威，Session 快照不再为审批配置变化重复写盘。
- 待审批操作在交互所有者内校验真实、未完成的等待者；过期或已回答点击不能改变全局模式或授予设备授权。开启全局自动批准会同步所有活跃 Work 状态并结算其审批等待。
- 设备授权只修改目标会话的运行绑定；撤销不影响其他会话，前台恢复与后台取消继续使用各自交互所有者。
- 插件组合根持有 GitHub 凭据操作和 Web 工具构造，Engine 不再直接依赖对应平台存储/Provider。
- 进程唯一后台任务管理器由共享 Runtime 持有并继续使用原 `local-harness/jobs.json` 持久化；Work 单向订阅任务快照负责会话投影和前台通知，Engine 删除后台任务 UI 代理与任务管理器所有权。
- WorkRuntime 对 Engine 的直接引用已由 4 → 2 → 1 → 0 清零；前台 Job、pending inbox、投影游标及取消顺序由 Shared Runtime 接管。旧 Engine 已在阶段 6 删除，对应旧访问器与路径由最终门禁永久禁止回归。
- 前台与 Work 取消时，即使 inbox 取消日志写入失败，也必须取消真实 Job、清理排队投影和交互授权；Work teardown 等待真实 Job/mirror 退出后再发布空闲并释放运行引用，写盘错误继续向调用方反馈。
- 计划模式由 `LocalWorkPlanModeCoordinator` 持有，维护租约隔离前台/Automation；先提交 `plan/mode` 权威事件，再发布 Work 状态与更新模型历史，事件写入失败不得留下已切换的界面。
- Chat 已迁出人物选择、图集绑定、默认人物同步、行为调节、纠正撤销、回复建议结果提交、群聊成员配置/移除、前台停止、发送事务、直聊主回合、群聊前台准入/执行、时间线编辑与重生成、分支变体选择及 post-turn 归并；ChatRuntime 对 Engine 的直接引用已清零。`chatTurnPort` 已脱离 Engine，由 `LocalChatTurnStarter` 在组合根直接提供；上述 Chat 业务实现不得回流 Engine。
- 后台任务快照的通知与持久化按统一提交顺序执行；Work 订阅重放和绑定接入在投影锁内读取当前任务事实，防止旧快照覆盖取消或完成终态。任务返回后再次验证取消状态与合法终态提交权，阻塞执行的迟到成功或异常均不得覆盖已取消终态。回归覆盖晚接入绑定、跨会话隔离、重启中断投影、并发取消与迟到成功/异常。
- 已迁出的 Chat 领域写入统一采用“Session MAINTENANCE owner → durable Chat domain/timeline event → runtime projection → Session snapshot cache”提交顺序。人物/行为调节等跨文档写入在权威事件提交前失败必须恢复原文档；群聊成员、回复建议和分支选择不得再出现 UI 已更新但 EventLog/模型历史仍停留旧状态的半提交。
- 回归覆盖多会话等待、全局模式启停、过期/已完成点击、设备授权隔离/撤销、显式审批工具与前台切换；本子阶段须通过最终 Head 的完整 CI 验证。

当前阶段三已知 Engine Feature 业务根已清零，Chat / Work 的发送、主回合、时间线 / 分支、Work AgentLoop 与 Tool runtime 等真实实现已归所属 Feature。Shared Context 只保留中立 Policy / DTO，Work 的 structured state 与 cue 解释位于 `local.work`；Shared Agent recovery 只负责恢复安全与通用 continuation，Work checkpoint 的语义装饰由 `LocalWorkRecoveryContextPolicy` 持有。Chat post-turn coordinator 已归 `local.chat`，`chatTurnPort` 也已脱离 Engine。阶段三五个回合／管理组合 Port 已脱离 Engine；下一轮唤醒由独立应用组合协调器路由，Chat／Work 各自拥有队列消费与启动。Session 生命周期、Tools/plugin composition 与 Diagnostics 已有独立所有者。人物、群聊、分支恢复与 Chat 事件解释归 Chat；计划、Todo、目标回放归 Work。旧共享 Memory coordinator 已删除，Automation 的关系记忆请求也复用 Chat 唯一实现。Session envelope 的既有领域序列化字段继续保留原格式，不在共享层解释领域规则。

队列续跑采用锁内持久提交，写盘失败保留原输入和顺序；Work 启动失败或空队列会释放预取 Session 租约。群公告采用维护租约内先提交 Chat domain event、再发布投影、最后物化快照；快照失败不得撤销已提交事实。运行中批准计划通过单个 `plan/approved` 事件同时提交计划和退出规划模式。

阶段三代码出口已经闭环：Chat / Work Execution Port 均具备显式目标 Session、timeout / recovery、结构化终态以及按 Session cancel / cancelAndJoin；阶段三 Engine composition bridge 已清零并由门禁锁定。阶段三仍需以 PR 最新 Head 的完整 CI / 设备 lane / merge-gate 完成最终验收，UI contribution 和 Engine 最终退出分别属于阶段五、六。

阶段 3 的完成状态只以 PR 最新 Head 的 `architecture-3-gates` 与完整 CI 为准；旧 Head 的成功、失败或取消结果都不能替代当前 Head 验收。实时 Head 与 Actions 编号属于 PR/CI 运行信息，不写入架构权威文档。

### 阶段 4：建立 AutomationFeature

状态：**代码闭环；实时验证状态以 PR / CI 为准。**

- Automation Runtime → `LocalHarnessEngine` 已清零；
- `LocalChatAutomationExecutionPort` / `LocalWorkAutomationExecutionPort` 通过 Adapter 进入完整 `ChatExecutionPort` / `WorkExecutionPort`；
- Automation 不再进入 Chat / Work internal Coordinator、Store、runner 或 writable state；
- 执行结果统一为 delivered / skipped / blocked / cancelled / failed 结构化终态；
- `ENGINE_STAGE4_AUTOMATION_BRIDGE_ALLOWLIST` 与 Automation internal migration allowlist 均已清零，并由架构门禁阻止回归；
- Session owner、scheduleGeneration、run ownership 与迟到提交栅栏继续属于共享运行边界。

### 阶段 5：FeatureCatalog + UI Contribution

状态：**代码闭环；实时验证状态以 PR / CI 为准。**

- 不可变 `LocalFeatureCatalog` 与唯一 route owner 已建立；
- `LocalFeaturePageContent` 只解析 Feature owner / contribution，中央产品页面 `when(page)` 已删除；
- Shell / Chat / Work / Automation / Tools / Settings 分别拥有页面内容 contribution；
- contribution 契约同时拥有 Back policy、Drawer entry 与 restore policy，Shell 只保留导航栈、抽屉开合和系统 Back 的宿主执行；
- Shell / Chat contribution 各自消费窄 `StateFlow`，其他 Feature 不接收无关 conversation state；所有 contribution 通过窄 state/actions 契约组合，不接收整个 `LocalHarnessViewModel`；
- UI 对 Feature 顶层策略函数/常量以及 Store / Coordinator / Service / Runtime 等内部实现的直接穿透由门禁禁止，UI 只通过 presentation / Feature API 消费。

### 阶段 6：收缩为 LocalRuntimeKernel

状态：**代码闭环；实时验证状态以 PR / CI 为准。**

- 旧 `LocalHarnessEngine.kt` 已删除，类型和旧路径均由门禁永久禁止回归；
- `DshApplication → LocalRuntimeKernel → LocalRuntimeBootstrapPort` 已成为进程启动链；
- Kernel 只负责 start-once、生命周期 scope、bootstrap / recovery 触发与初始化错误投影，具体 Feature / provider 构造装配留在应用组合根；
- Shared Session envelope 对 Chat / Work 复杂领域字段仅保存不透明 JSON，typed decode / normalize / summary projection 分别由 Chat / Work domain codec 所有；Session / Memory / Model / Quality / Tool / Usage 等 Shared 业务包不直接依赖 `LocalHarnessState` 或 Feature internal，聚合状态只由 Runtime owner 持有并向兼容投影提供只读事实；
- Settings 对 Chat 风格守卫只通过 Chat-owned 稳定 Port 配置，Chat 的开关、过滤词、命中事实与持久化归 Chat 所有；
- Chat / Work Feature 不持有 `MutableStateFlow<LocalHarnessState>` 或完整聚合写 transform，聚合适配只存在于应用组合根；
- Kernel 不理解 Persona / Gallery / Group / Todo / GitHub Token / UI 页面。

## 13. 迁移约束

迁移必须遵守：

```text
新边界接管
→ 调用方切换
→ 旧路径删除
→ 测试/门禁锁定
→ 再进入下一块
```

禁止长期：

- 双读；
- 双写；
- 新旧状态互相同步；
- Feature 和 Engine 同时拥有同一业务事实；
- 为通过门禁只移动代码不移动所有权。

## 14. 当前必须保留的系统不变量

架构迁移不得破坏已经验证的核心行为：

- `LocalSessionRuntimeRegistry` 的 Session owner 单一所有权；
- Automation `scheduleGeneration` 的提交权；
- `LocalAgentRunCoordinator` 的迟到结果栅栏；
- 模型 Run 冻结 profile / route identity；
- Tool side-effect 不明时禁止盲目重放；
- EventLog 继续作为持久事实流；
- Chat / Work UI projection 隔离；
- streaming preview 独立；
- Token ledger 单一账本；
- Plugin lifecycle 继续由 PluginManager 管理。

## 15. 架构门禁

CI 已将架构 3.0 从通用静态检查中独立为 `architecture-3-gates`。范围分类器识别 Feature / Shared Capability / Runtime Kernel 及架构控制文件；完整产品改动必须通过该 lane，main push 也必须重新执行对应控制面验证。

架构 3.0 不设置 Kotlin 文件数量门禁，也不设置单文件行数门禁。拆成几个文件、每个文件多少行都不能证明所有权正确；CI 只约束真实的架构边界、依赖方向、状态归属和运行不变量。本轮已清零的跨层依赖、旧 Engine 路径、聚合可写入口和 UI 穿透均使用永久禁止规则；后续若出现新的临时例外，必须单独记录债务、出口和删除条件，不能复用历史迁移白名单。

Feature Catalog 与路由门禁现包括：

- Chat / Work / Automation / Tools / Settings 一级模块存在性；
- 重复路由注册拒绝；
- 所有路由必须唯一归属；
- 导航必须通过 Catalog 解析；
- Shell 页面状态枚举必须来自 Catalog。

包名与领域契约收口已增加：

- Local 生产源码的 Kotlin package 必须与物理目录一致；
- Local 架构敏感源码禁止 wildcard import；
- 根层 `LocalHarnessModels.kt` 仅声明 `LocalHarnessState` 与跨 Feature 的 `LocalUsageMode`；
- Work 的计划、目标和任务清单归属 Work；审批/问答与后台任务类型分别归属共享 Interaction / Jobs 契约，避免 Runtime 反向依赖 Work internal；
- 模型响应、会话/消息、附件、工具文件、Automation 结果与资源状态归位到对应契约目录，既有序列化字段与枚举值保持兼容。

包名、领域契约与运行状态边界当前均按架构 3.0 最终门禁维护：

- Feature 禁止互相导入 internal package；
- UI 禁止引用领域 Store / Coordinator 等内部实现，只通过 presentation / Feature API / projection 消费；
- Chat / Work 不持有完整 `LocalHarnessState` 可写聚合状态；领域事实由所属 Feature / Shared Capability 单一拥有，聚合适配仅存在于应用组合根 / Runtime-owned 兼容投影边界；
- Session ownership、Agent run identity、迟到提交栅栏与 recovery coordination 归共享 Runtime 所有，Feature 不建立第二套 run owner；
- 全局审批配置以 `LocalApprovalPreferences` 为共享权威事实，Work 通过流式投影消费，不再从 Session 快照复制第二事实源；
- Chat / Work / Session / Model / Tools / Automation / Settings Runtime 已清零的 Engine 依赖永久禁止回归；
- 旧 Engine composition bridge、外部直接消费者与已迁 Feature 业务根均属于历史墓碑；不得以新代理、改名方法、0 预算或历史迁移白名单重新引入；
- `LocalHarnessState` 只保留 Runtime-owned 兼容聚合与只读投影用途，已迁字段不得重新形成长期双读 / 双写；
- CI 不使用 Engine 方法数、构造依赖数、聚合状态字段数、UI projection 字段数或文件行数作为架构放行条件；真正门禁以所有权、依赖方向、唯一事实源、运行不变量和旧路径退出为准。

## 16. 验证与完成标准

架构阶段完成不以“文件移动完成”为准。

每一阶段必须完成：

```text
实现
→ 单元测试
→ 架构门禁
→ 关联回归
→ Android 构建/设备验证
→ 最新 main + 当前 PR head 完整 CI
```

整个架构 3.0 只有在以下条件同时成立时才完成：

- Feature 拥有真实业务所有权，不是 Engine proxy；
- Feature 之间只通过能力契约协作；
- 共享能力只有一个事实源；
- Kernel 不包含产品业务；
- UI 由 Feature contribution 驱动；
- 旧 Engine 业务入口退出；
- 没有长期兼容双路；
- 架构门禁能够阻止重新中心化。

## 17. 相关权威文档

- #448 后续实施计划：[`ARCHITECTURE-3-EXECUTION-PLAN.zh-CN.md`](ARCHITECTURE-3-EXECUTION-PLAN.zh-CN.md)；仅记录执行工作包与验收，不替代本架构定义。
- 系统执行规则：[`../AGENTS.md`](../AGENTS.md)
- 系统联审：[`SYSTEM-AUDIT-GUIDE.zh-CN.md`](SYSTEM-AUDIT-GUIDE.zh-CN.md)
- 验证规则：[`VALIDATION.md`](VALIDATION.md)
- 安全边界：[`SECURITY.md`](SECURITY.md)
- UI / UX：[`UI-UX.zh-CN.md`](UI-UX.zh-CN.md)
- 本机 Harness 当前状态：[`ANDROID-HARNESS-STATUS.zh-CN.md`](ANDROID-HARNESS-STATUS.zh-CN.md)
