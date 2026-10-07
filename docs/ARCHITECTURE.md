# 架构 3.0

> 本文是 777 当前唯一系统架构权威文档。
>
> 架构 3.0 的当前边界是：**模块化单体 + 层级化 Feature 组合 + 共享能力契约 + 极薄进程运行内核**。本文只描述现行架构、系统不变量和持续约束。

## 1. 核心结论

架构优化以所有权、依赖方向、状态事实源和执行边界为准，不以拆更多 Coordinator / Runtime 作为目标。

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

共享能力通过中立契约依赖 Platform / Infrastructure。极薄进程运行内核单独负责进程启动、bootstrap / recovery 触发与生命周期协调，不参与每条 Feature 业务调用链，也不解释人物、日记、计划、Todo、GitHub 等具体产品业务。

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
│                                                            │
│ Session / Run ownership │ resource scheduling │ recovery   │
└───────────────────────────┬────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────────┐
│                Platform / Infrastructure                   │
│ harness-core / Android runtime / MCP / LSP / device        │
│ persistence / network / third-party integrations           │
└────────────────────────────────────────────────────────────┘
```

进程启动侧单独存在极薄 `LocalRuntimeKernel`：

```text
DshApplication
→ LocalRuntimeKernel
→ LocalRuntimeBootstrapPort
→ 应用组合根
→ Shared Runtime / Feature 初始化与恢复
```

Kernel 只负责进程 start-once、生命周期 scope、bootstrap / recovery 触发与初始化错误投影，不位于每条 Feature 业务调用链中。

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
- 绕过所属 Feature 的公开 API / Port，直接穿透到其他领域或共享实现；
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

Work 的计划、Todo、目标、审批、问答和运行中心属于同一业务域，由 WorkFeature 统一解释领域语义；共享 Interaction / Jobs 能力只提供中立运行契约和事实。

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

SettingsFeature 负责设置体验和配置入口；真实配置事实仍归对应业务 Owner：Feature 专属配置归所属 Feature，中立跨域配置归对应 Shared Capability。

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

`LocalSessionStorageRuntime` 统一持有 Session snapshot Repository / Coordinator，并复用唯一 `LocalSessionEventLogRegistry`；Feature 通过 Session Capability 完成共享会话持久化，不建立独立 Session 所有权。


负责：

- Session 读取、写入与生命周期；
- Session owner / lease；
- 删除与维护事务；
- EventLog / transcript 权威事实；
- 模型请求可重建证据、脱敏 Message / Context / Tool Surface 版本，以及按 requestUid 自动重建和一致性验证；
- ModelHistory Checkpoint 事件水位与尾部重放；
- Session Projection 提供统一注册底座、状态版本与 `asOfSequence` 时间切面；新接入 Projection 由 Feature 持有强类型句柄，Registry 不提供无类型全局状态读取；现有 Feature 投影按 Owner 渐进迁移，不另起第二套事实源；
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
- 子代理启动规格统一声明稳定 `instructions` 与本轮 `task`，并声明 mutation / continuation / history / virtual screen / depth / tool filter / output schema；稳定身份指令随持久子代理恢复元数据保存，运行和恢复前执行同一套能力校验；
- 共享 Prompt 分层编排只负责稳定/动态层的确定性顺序、预算与缓存指纹，不解释 Chat / Work / Project 等 Feature 的业务语义；Prompt 也不作为权限、安全或副作用边界；
- Structured Result 使用共享 `JsonSchemaValidator`；模型只收到统一的 JSON-only 结果约束，终态先校验再提交，工具参数与子代理结果不允许出现两套 Schema 解释；
- 前台与持久子代理共享 `QueuedAgentInput` 消息契约；Inbox 有界且满载显式拒绝，禁止通过淘汰旧消息伪装成功；
- checkpoint；
- continuation；
- 持久 Child Agent 的稳定身份、durable inbox、history checkpoint 与 cold resume；一次 Activation 结束后显式 continuable Agent 进入 `dormant`，仅在新消息到达后重新激活，恢复保持全局 Step / 动态预算与终态结算语义；
- run recovery；
- tool result continuation 语义。

### Tool Capability

负责：

- 工具注册；
- capability exposure；
- approval boundary；
- tool execution；
- structured result；
- side-effect 语义；
- 工具活动只从 Session EventLog 中现有的 `tool/call`、`tool/execution-started`、`tool/result` 派生只读 Projection；Activity 不建立第二条持久事实流。

产品 ToolsFeature 与 Tool Capability 必须区分：前者是产品功能，后者是运行能力。

### Memory / Resource / Usage / Event

这些能力继续保持单一事实源，并允许多个 Feature 复用；任何 Feature 不得为了局部方便复制第二套账本、调度器或事件源。

## 7. LocalRuntimeKernel

`LocalRuntimeKernel` 是架构 3.0 的极薄进程生命周期内核，不承担领域业务和共享运行事实的所有权。

当前职责只有：

- 进程 start-once；
- 进程级 CoroutineScope / 生命周期；
- 调用中立 `LocalRuntimeBootstrapPort`；
- 触发 bootstrap / startup recovery；
- 初始化与后台 Runtime 失败投影。

Session ownership、Agent run identity、迟到提交栅栏、资源调度、共享持久化与 recovery coordination 属于 Shared Runtime / Shared Capability，由对应单一 Owner 持有。

具体 Feature / Provider 的构造和装配位于应用组合根。

Kernel 不理解：

- 人物、图集、日记、群聊；
- Work 计划 / Todo / Goal；
- Model Provider 业务；
- GitHub Token / Tool 领域配置；
- Settings 页面或其他具体 UI；
- Feature 专属状态与规则。

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

路由归属和页面状态枚举由 `LocalFeatureCatalog` 统一提供，架构门禁持续验证唯一归属。

各 Feature 自己贡献页面渲染、Back ownership、Drawer 入口和恢复策略，Shell 只按 `LocalFeatureCatalog` 与 contribution 契约执行导航。

## 10. Product Feature 与 Runtime Plugin 的区别

两种扩展机制禁止混为一套：

| 机制 | 适用对象 | 生命周期 |
|---|---|---|
| Feature Catalog | Chat / Work / Automation / Tools 等产品功能 | 启动期组合，运行时不可变 |
| PluginCatalog / PluginManager / PluginRegistry | MCP、设备、运行时 provider、可替换工具插件 | 支持受控运行时启停/替换 |

产品 Feature 不采用任意热卸载，避免 Session 正在运行时业务解释器突然消失。

## 11. 单向依赖规则

业务调用依赖：

```text
UI
↓
presentation / Feature API / projection
↓
Feature internal
↓
Shared Capability / Shared Runtime contract（按需）
↓
Platform / Infrastructure
```

进程启动依赖：

```text
DshApplication
↓
LocalRuntimeKernel
↓
LocalRuntimeBootstrapPort
↓
应用组合根
```

边界要求：

- Shared Capability 不依赖 Feature internal；
- Feature internal 不依赖 sibling Feature internal；
- Automation 通过 Chat / Work Execution Port 调用提供方能力；
- UI 不直接依赖领域 Store / Coordinator / Runtime；
- Kernel 不依赖 Feature internal，也不承载 Feature / Provider 构造；
- 跨 Feature 调用必须通过稳定 Port，且 Port 归属于提供能力的一侧。

## 12. 当前运行边界核对

架构 3.0 的当前实现必须持续满足以下边界。

### 12.1 Feature 领域所有权

- ChatFeature 拥有人物、图集、群聊、日记、关系记忆、聊天时间线、回复建议与 Chat 领域解释。
- WorkFeature 拥有计划、Todo、Goal、Work 上下文、Work 恢复语义、运行中心领域投影与 Work 专属规则。
- AutomationFeature 拥有任务目录、调度、计划、Webhook、结算和 Automation 领域状态。
- ToolsFeature 拥有用户可见工具管理与 MCP 设置体验；实际 Tool execution contract 属于 Shared Tool Capability。
- SettingsFeature 只负责设置体验和配置入口，真实配置事实归对应 Feature 或 Shared Capability。

Feature 不持有 sibling Feature internal，不复制其他领域规则，不接收完整可写聚合状态。

### 12.2 Shared Runtime / Shared Capability

共享运行层负责跨 Feature 的中立事实与运行能力：

- Session ownership / lease；
- Agent run identity 与迟到提交栅栏；
- resource scheduling / lease；
- recovery coordination；
- Session snapshot / EventLog / transcript 权威事实；
- Model route/profile 与 credential 边界；
- Tool execution / approval / structured result；
- 模型声明 `tool/call` 与真实 admission `tool/execution-started` 分离，真实执行拥有稳定 execution identity；
- Memory / Usage / Diagnostics 等中立能力。

每项共享事实只有一个 Owner，Feature 通过稳定契约消费，不建立第二套账本、调度器、Session owner 或恢复状态。

### 12.3 应用组合根与 Runtime Kernel

应用组合根负责：

- Feature / Provider 构造；
- Hilt binding 与跨边界 Adapter；
- `LocalRuntimeBootstrapPort` 实现；
- Shared Runtime 与 Feature 的启动装配；
- 平台 Provider / Plugin composition。

`LocalRuntimeKernel` 只负责：

- process start-once；
- 生命周期 CoroutineScope；
- 调用 `LocalRuntimeBootstrapPort`；
- bootstrap / startup recovery 触发；
- 初始化与 Runtime 后台失败投影。

Kernel 不承担具体产品 Feature、Model Provider、Tool 领域、Settings 或 UI 业务。

### 12.4 状态与投影

- `LocalHarnessState` 只作为 Runtime-owned 兼容聚合与只读投影容器。
- Chat / Work / Model / Runtime 等领域状态由各自 Owner 维护。
- UI 只消费窄 presentation / Feature API / projection。
- 高频 streaming preview 独立于低频 Shell / 页面聚合状态。
- 配置、EventLog、Session snapshot、Jobs、Usage 等事实都必须保持单写。

### 12.5 FeatureCatalog 与 UI contribution

- `LocalFeatureCatalog` 是启动期产品路由归属事实源。
- 每个 route 只属于一个 Feature。
- 页面内容、Back policy、Drawer entry、restore policy 由对应 Feature contribution 持有。
- Shell 只负责导航栈、抽屉和系统 Back 等宿主执行。
- Product Feature Catalog 与 Runtime Plugin lifecycle 分离。

## 13. 架构变更约束

任何新增功能、重构或所有权调整都必须按当前边界完成闭环：

```text
确定 Feature / Shared Owner
→ 定义公开 Port / API
→ 明确状态与配置事实源
→ 在应用组合根完成生产装配
→ 接入 FeatureCatalog / UI projection（如适用）
→ 验证 Session / Run / Recovery / 持久化语义
→ 架构门禁与完整 CI
```

要求：

- 同一领域事实只有一个可写 Owner。
- 同一生产能力只有一套有效 DI / Registry / Worker / serializer / route 装配。
- Shared Capability 保持中立，不能吸收 Feature 专属业务规则。
- Kernel 不扩大为产品业务容器。
- UI 不直接依赖领域 Store / Coordinator / Runtime。
- 跨 Feature 调用通过提供方拥有的稳定 Port。
- 兼容数据只在明确 normalize / migration 边界处理，正常运行主链只使用当前模型。
- 任何当前架构债务必须精确记录消费文件、依赖边、退出条件和验证方式。

## 14. 当前必须保留的系统不变量

当前实现必须持续保持以下核心行为：

- `LocalSessionRuntimeRegistry` 的 Session owner 单一所有权；
- Automation `scheduleGeneration` 的提交权；
- `LocalAgentRunCoordinator` 的迟到结果栅栏；
- 模型 Run 冻结 profile / route identity；
- Tool side-effect 不明时禁止盲目重放；
- EventLog 继续作为持久事实流，模型历史缓存 / Checkpoint 只能作为带水位的派生物；
- 每次模型请求必须留下可校验的 request evidence；工具与模型可见 Context Surface 变化必须可追踪；
- Chat / Work UI projection 隔离；
- streaming preview 独立；
- Token ledger 单一账本；
- Plugin lifecycle 继续由 PluginManager 管理。

## 15. 架构门禁

CI 已将架构 3.0 从通用静态检查中独立为 `architecture-3-gates`。范围分类器识别 Feature / Shared Capability / Runtime Kernel 及架构控制文件；完整产品改动必须通过该 lane，main push 也必须重新执行对应控制面验证。

`scope` 在动态分类前固定自举范围分类器与仓库 CI 完整性检查，分类后再独立复核关键控制文件的最低 lane；`check-*.py` 门禁脚本必须能从 workflow / 自动化链路真实到达，`merge-gate` 必须持续依赖并核对全部当前 lane 的选择结果与执行结果。架构权威文件、CI 主流程和架构门禁本身发生变化时，不能通过调整分类规则让自身跳过 `static-gates` 或 `architecture-3-gates`。

架构 3.0 不设置 Kotlin 文件数量门禁，也不设置单文件行数门禁。拆成几个文件、每个文件多少行都不能证明所有权正确；CI 约束真实的 Feature / Shared Capability / Runtime Kernel 边界、依赖方向、状态归属、生产装配和运行不变量。若存在临时架构债务，必须单独记录消费文件、具体依赖边、退出条件和删除条件。

Feature Catalog 与路由门禁现包括：

- Chat / Work / Automation / Tools / Settings 一级模块存在性；
- 重复路由注册拒绝；
- 所有路由必须唯一归属；
- 导航必须通过 Catalog 解析；
- Shell 页面状态枚举必须来自 Catalog。

架构门禁持续验证以下当前边界：

- Local 生产源码的 Kotlin package 必须与物理目录一致；
- Local 架构敏感源码禁止 wildcard import；
- 根层 `LocalHarnessModels.kt` 仅声明 `LocalHarnessState` 与跨 Feature 的 `LocalUsageMode`；
- Work 的计划、目标和任务清单归 WorkFeature；审批/问答与后台任务类型分别归共享 Interaction / Jobs 契约；
- 模型响应、会话/消息、附件、工具文件、Automation 结果与资源状态位于对应契约目录，序列化字段与枚举值保持兼容；
- Feature 不互相导入 internal package；
- UI 不引用领域 Store / Coordinator 等内部实现，只通过 presentation / Feature API / projection 消费；
- Chat / Work 不持有完整 `LocalHarnessState` 可写聚合状态；领域事实由所属 Feature / Shared Capability 单一拥有，聚合适配只存在于应用组合根 / Runtime-owned 兼容投影边界；
- Session ownership、Agent run identity、迟到提交栅栏与 recovery coordination 归共享 Runtime 单一持有；
- 全局审批配置由 `LocalApprovalPreferences` 单一持有，Work 通过流式投影消费；
- Chat / Work / Automation / Tools / Settings 的领域状态、规则和公开 API 均由所属 Feature 单一拥有；
- Session / Model / Agent / Tool / Memory / Resource / Usage / Event / Persistence 等共享能力保持中立单一事实源；
- 应用组合根负责 Feature / Provider 构造装配，`LocalRuntimeKernel` 只承担进程生命周期边界；
- `LocalHarnessState` 只保留 Runtime-owned 兼容聚合与只读投影用途，领域写入通过所属 Owner 完成；
- CI 以所有权、依赖方向、唯一事实源、生产装配、运行不变量和投影边界作为架构放行条件。

## 16. 验证与持续完成标准

任何架构变更都必须完成以下闭环：

```text
实现
→ 单元测试
→ 架构门禁
→ 关联回归
→ Android 构建/设备验证
→ 最新 main + 当前 PR head 完整 CI
```

架构 3.0 长期成立必须同时满足：

- 每个 Feature 拥有自己的领域状态、规则、写入与公开 API；
- Feature 之间只通过稳定能力契约 / Port 协作；
- Shared Capability 保持中立且每项共享事实只有一个 Owner；
- Session ownership、Agent run identity、资源调度与恢复协调保持单一共享运行事实源；
- `LocalRuntimeKernel` 只承担进程生命周期和 bootstrap / recovery 触发；
- Feature / Provider 构造集中在应用组合根；
- FeatureCatalog 的 route owner 与 UI contribution 归属唯一；
- UI 只通过 presentation / Feature API / projection 消费；
- `LocalHarnessState` 只承担兼容聚合 / 只读投影，不成为领域写入口；
- 架构门禁持续验证上述所有权、依赖、装配和运行不变量。

## 17. 相关权威文档

- 系统联审规范：[`SYSTEM-AUDIT-GUIDE.zh-CN.md`](SYSTEM-AUDIT-GUIDE.zh-CN.md)
- 共享审计结论：[`SHARED-AUDIT-CONCLUSIONS.zh-CN.md`](SHARED-AUDIT-CONCLUSIONS.zh-CN.md)
- 验证规范：[`VALIDATION.md`](VALIDATION.md)
- Android Harness 当前状态：[`ANDROID-HARNESS-STATUS.zh-CN.md`](ANDROID-HARNESS-STATUS.zh-CN.md)
- 系统执行规则：[`../AGENTS.md`](../AGENTS.md)
- 系统联审：[`SYSTEM-AUDIT-GUIDE.zh-CN.md`](SYSTEM-AUDIT-GUIDE.zh-CN.md)
- 验证规则：[`VALIDATION.md`](VALIDATION.md)
- 安全边界：[`SECURITY.md`](SECURITY.md)
- UI / UX：[`UI-UX.zh-CN.md`](UI-UX.zh-CN.md)
- 本机 Harness 当前状态：[`ANDROID-HARNESS-STATUS.zh-CN.md`](ANDROID-HARNESS-STATUS.zh-CN.md)
