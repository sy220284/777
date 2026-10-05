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

当前第一阶段已经建立：

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

当前 Catalog 已成为本机功能导航解析和功能页面状态枚举的事实源；页面渲染贡献将在后续阶段继续从中央 `when` 分发下沉到各 Feature。

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

当前 `LocalHarnessEngine` 仍承担一部分 Feature 业务和共享能力编排，因此它是迁移中的旧中心，不是架构 3.0 的最终形态。

## 8. 状态模型

现有 `LocalHarnessState` 仍是迁移中的聚合状态。目标是拆成领域状态并由 UI 按需投影：

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

阶段 1 已把路由归属和页面状态枚举切到 `LocalFeatureCatalog`。

后续阶段将继续删除中央 `LocalFeaturePageContent` 的巨大 `when`，由 Feature 自己贡献页面渲染、Back ownership、入口和恢复策略。

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
- 已迁字段禁止重新平铺回聚合状态，不保留长期双读/双写。

### 阶段 3：建立 ChatFeature / WorkFeature

状态：**进行中。**

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
- 插件组合根持有 GitHub 凭据操作和 Web 工具构造，Engine 不再直接依赖对应平台存储/Provider；构造依赖预算维持 17。
- 进程唯一后台任务管理器由共享 Runtime 持有并继续使用原 `local-harness/jobs.json` 持久化；Work 单向订阅任务快照负责会话投影和前台通知，Engine 删除后台任务 UI 代理与任务管理器所有权。
- WorkRuntime 对 Engine 的直接引用已由 4 → 2 → 1 → 0 清零；前台 Job、pending inbox、投影游标及取消顺序由 Shared Runtime 接管，Work 停止通过共享运行所有者执行。Engine 仍有迁移期访问器，后续必须继续收缩，不能据此宣称 Kernel 已完成。
- 前台与 Work 取消时，即使 inbox 取消日志写入失败，也必须取消真实 Job、清理排队投影和交互授权；Work teardown 等待真实 Job/mirror 退出后再发布空闲并释放运行引用，写盘错误继续向调用方反馈。
- 计划模式由 `LocalWorkPlanModeCoordinator` 持有，维护租约隔离前台/Automation；先提交 `plan/mode` 权威事件，再发布 Work 状态与更新模型历史，事件写入失败不得留下已切换的界面。
- Chat 已迁出人物选择、图集绑定、默认人物同步、行为调节、纠正撤销、回复建议结果提交、群聊成员配置/移除、前台停止及分支变体选择；ChatRuntime 的直接 Engine 引用预算降至 4。人物选择与默认同步持有 Session 维护租约至快照写入完成，按会话、模式、人物/图集/故事身份、上下文代次及最新对话拒绝迟到结果；取消与失败必须释放租约。
- 后台任务快照的通知与持久化按统一提交顺序执行；Work 订阅重放和绑定接入在投影锁内读取当前任务事实，防止旧快照覆盖取消或完成终态。任务返回后再次验证取消状态与合法终态提交权，阻塞执行的迟到成功或异常均不得覆盖已取消终态。回归覆盖晚接入绑定、跨会话隔离、重启中断投影、并发取消与迟到成功/异常。
- 回归覆盖多会话等待、全局模式启停、过期/已完成点击、设备授权隔离/撤销、显式审批工具与前台切换；本子阶段须通过最终 Head 的完整 CI 验证。

剩余：Chat 的分支、发送、群聊与部分运行协调仍依赖 Engine；其他 Feature 的代理、跨 Feature 内部引用和完整可写聚合状态还需按阶段收敛。WorkRuntime 引用清零只证明本次 Work UI 边界迁移，不能把阶段 3 或整个架构 3.0 标记为完成。

### 阶段 4：建立 AutomationFeature

状态：**待完成。**

- Automation 只通过 `ChatExecutionPort` / `WorkExecutionPort` / Session Capability 工作；
- 不进入 Chat / Work internal Coordinator；
- Session owner、scheduleGeneration、run ownership 与迟到提交栅栏继续属于共享运行边界。

### 阶段 5：FeatureCatalog + UI Contribution

状态：**部分基础已提前落地，待按本阶段完成验收。**

- 不可变 `LocalFeatureCatalog` 与唯一 route owner 继续保留；
- 页面渲染、Back ownership、Drawer 入口、Settings/Diagnostics contribution 迁入 Feature；
- 删除中央 `LocalFeaturePageContent` 巨大分发。

### 阶段 6：收缩为 LocalRuntimeKernel

状态：**待完成。**

- `LocalHarnessEngine` 的 Feature 业务规则、Feature API 与迁移期代理全部退出；
- Kernel 只保留 Session/run ownership、跨域事务、取消传播、资源租约、run identity、recovery 与生命周期协调；
- Engine 构造依赖、internal API、直接消费者门禁持续向下 ratchet，最终更名/替换为 `LocalRuntimeKernel`；
- Kernel 不得理解 Persona / Gallery / Todo / GitHub Token / UI 页面。

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

CI 必须逐步从“控制热点不继续变大”升级为“证明 3.0 边界真的成立”。

架构 3.0 不设置 Kotlin 文件数量门禁，也不设置单文件行数门禁。拆成几个文件、每个文件多少行都不能证明所有权正确；CI 只约束真实的架构边界、依赖方向、状态归属和运行不变量。

阶段 1 已增加：

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

这轮包名和契约归位不代表运行状态边界已经完成。仍须移除 Feature 的完整可写聚合状态、收紧 Work run 所有权、建立审批配置的单一流式投影，并在实现完成后锁定跨 Feature internal 与聚合写入口门禁。

后续随迁移继续增加：

- Feature 禁止互相导入 internal package；
- UI 禁止引用领域 Store / Coordinator；
- Runtime 对 Engine 的代理引用预算持续下降直至清零；
- `LocalHarnessState` 字段预算持续下降；
- Engine 构造依赖 / internal API / 直接消费者约束持续收紧。

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
