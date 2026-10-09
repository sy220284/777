# Validation

本文描述当前主线的验证闭环。

目标不是“某个测试跑过”，而是确认：

```text
实现正确
+ 架构未退化
+ 性能未退化
+ Android 16 / 17 可运行
+ 最新 main 组合无回归
```

## 当前基线

```text
777: 版本以 `.github/release-version` 为准（运行时以 `.github/release-version` 及实际构建产物为准）
Android: min 36 / target 36 / compile 37
Source language: Kotlin
Build JDK: >= 27（CI / Artifact 基线为 JDK 27）
JVM target: 21（class major 65）
Node: >= 24
Local Harness semantic reference: 0.2.1-alpha.1 / 5badb150...
Remote protocol baseline: 0.1.6-alpha.1 / 0d1f5000...
```

## 仓库验收边界（与产品功能清单分离）

仓库可执行验收索引只收录 **126 个已有 Kotlin @Test 直接证据的功能叶节点**，其他 301 个叶节点继续保留在 427 项权威功能树中，不进入本仓库 CI 验收目标、不可计作验收失败或通过。真实第三方账户/套餐、付费联机、物理设备辅助技术、真实远程主机、长期电量/跨天行为由环境专项验证，不能作为仓库 CI 必过项。见 `docs/ACCEPTANCE-PLAYBOOK.zh-CN.md`。

## CI

CI 采用 **四级风险分流、独立合并门禁、发布资格严格隔离**。分类由 `.github/scripts/classify-ci-scope.py` 决定，混合变更取检查并集；未知或关键底层改动回退完整矩阵。

- L0 文档：scope 控制面与 merge-gate；修改功能树 / 验收索引时增跑 static-gates，检查树与验收凭据的一致性。
- L1 普通源码：static-gates、受影响的 architecture-3-gates、unit-tests、build-arm64。普通 UI 和模型无关业务不重复执行 Relay 与双模拟器。
- L2 跨功能底层、Android 平台/ABI、安全边界、CI/发布控制面：完整单测、架构、Relay、ARM64/x86 与 Android 16/17。androidTest 变更验证双设备，导航入口变更补 Android 16。
- L3 正式 APK 发布：必须在**同一发布 SHA** 找到 `main` push 对应成功的完整 CI 矩阵（含 merge-gate、两套 APK、双模拟器、真实 Relay），并与最近已发布的正式版本比较，确认实际存在影响 APK 的变更。文档/自动化独立改动不会制造正式 Release。

普通 PR 的产品源码在确保真实 Kotlin 编译、相关单测及专项检查的基础上执行精准集合；`main` 的所有影响发布产物的 push 强制执行完整矩阵，`workflow_dispatch` 同样强制完整（含 fixture-provenance）。发布判断与一般 CI 成功解绑，轻量 CI 无法授权 APK 发布。CI 和发布工作流自身变更必须经过真实完整矩阵，不能依赖静态关键字扫描冒充验证。

**执行顺序**：scope 自举和范围防降级 → static-gates + architecture-3-gates 并行 → 成功后 unit-tests / relay-conformance / ARM64 / x86 构建并行；选中的 Android 16/17 模拟器同步从当前 Run 的 x86 产物执行，最后由 merge-gate 汇总。本次必需检查必须 success，未选中可 skipped。只有 PR 的旧 CI 因更新自动取消，main 的发布候选验证不被后续文档提交取消。

`fixture-provenance` 仅官方 fixture 来源变更或手动全量验证时运行。`dev-toolchain` 只在工具版本、构建依赖、缓存 Action、安装与打包脚本变更或定时/手动要求时打包；更新开发说明文档不得重新生成工具链。

**验收重点**：分类器正反例（文档、资源、UI、模型、Relay、会话恢复、底层、Android 16/17、CI/Release 控制面、混合与未知路径）、编译与架构违规注入、同 SHA 发布资格、完整 Android 矩阵，以及优化前后 PR 中位/P95 时长与总 Runner 分钟。不能以单个成功 PR 推断性能收益。

### scope

`scope` 是所有 CI 的固定入口，动态范围识别之前先执行不可跳过的控制面自举：

- 对 `classify-ci-scope.py` 与 `check-ci-repository-integrity.py` 做 Python 语法校验；
- 执行范围分类器完整自测，逐类验证架构权威文件、CI 控制文件、完整 Android 验证脚本和 fixture 来源的最低验证集合；
- 执行仓库 CI 完整性检查：外部 Action 固定 SHA、Gradle 供应链边界、单测模块覆盖、Android 共享产物、权威文档引用、CI lane / merge-gate 契约和门禁脚本可达性；
- 收集本次真实 changed files 并执行 `git diff --check`；
- 动态分类后再由 workflow 内独立规则复核：架构权威文件 / 控制面必须选中 `static-gates + architecture-3-gates`，fixture 与 Android 验证控制脚本不得降级所需矩阵。

因此，即使范围分类器或门禁脚本本身被修改，也必须先通过当前控制面的自举验证，不能依赖“分类结果”决定是否验证分类器自己。

### static-gates

该阶段只保留快速、与产品架构实现位置无关的静态产品检查：

- actionlint 工作流语义校验；下载版本和 SHA-256 固定。
- Python / Shell 自动化语法校验。
- Android Manifest / exported component / FileProvider / 模型 HTTPS-or-loopback 安全边界。
- UI 硬编码、Design System、通知、Kotlin 风险与构建基线。
- 测试质量门禁：禁止禁用测试、伪断言、未审计的环境跳过与真实阻塞等待；真实 Relay 一致性测试不得携带工作站本地默认路径。
- Runtime 压缩器自测。
- 发布版本格式。

### architecture-3-gates

该 lane 是架构 3.0 的权威结构与执行门禁，分成两个独立步骤：

1. `check-local-architecture-boundaries.py`
   - Feature / Shared Capability / Runtime Kernel 单向依赖；
   - cross-Feature internal 禁止；
   - Shared Capability 保持 Feature-agnostic，不依赖 Feature internal；
   - 领域状态单写与 UI projection 边界；
   - Feature Catalog 与 Runtime Plugin 生命周期分离；
   - Feature / Shared Capability / Runtime Kernel 的 Owner、公开边界与依赖方向必须与 `ARCHITECTURE.md` 一致；
   - FeatureCatalog 路由唯一、UI projection 边界、应用组合根装配、领域状态单写与 Session / Run ownership 必须由当前门禁持续验证。

2. `check-local-performance-invariants.py`
   - transcript / Session Event 历史访问有界；
   - streaming preview 有界；
   - model history 缓存与单一写入口；
   - Session snapshot / recovery 顺序；
   - Agent inbox、统一 Model / Tool Gateway 与冻结 profile 的 Vision 路由保持为当前执行入口，前台执行不得建立平行旁路。

这两份脚本不再冻结 UI 样式、Prompt 文案、固定方法体、构造依赖数量、文件行数、字段数量或具体变量名。业务语义由单元 / conformance / 设备测试证明；架构门禁只证明 3.0 边界与关键运行不变量。

架构门禁执行前先运行 `--self-test` 反向样例，确认全限定名直接访问不会绕过依赖检查、历史事件全量扫描和已迁移检查目标缺失能被拦截。UI contribution 依据实际注册文件及 `LocalFeatureCatalog` 的 Owner 动态核对，不再维护固定文件名列表。CI 完整性检查覆盖 `uses:` 两种合法写法，并从实际 `run` 命令追溯门禁调用路径。


### fixture-provenance

只有官方 fixture 来源、刷新脚本或上游锁定发生变化时才执行：

1. 使用 Node 22 / corepack 刷新锁定官方黄金 fixture。
2. 要求刷新结果与仓库中的黄金结果完全一致。

普通产品代码变化继续运行 `:reference-validation:test`，但不再每次额外联网刷新固定 fixture。

### unit-tests

一次 Gradle invocation 覆盖全部 JVM / Android unit tests 与 Harness conformance：

```text
:core:test
:harness-core:test
:harness-runtime-android:test
:harness-interop:test
:harness-device-android:testDebugUnitTest
:mock-harness:test
:app:testDebugUnitTest
:reference-validation:test
```

其中本机语义单测必须覆盖：模型请求证据与 Surface 关联、V2 requestUid 自动重建 / digest 校验 / 多模态脱敏 / V1 evidence-only 兼容、失败/取消 `assistant/attempt` 不进入正式历史、ModelHistory Checkpoint 水位与 V1 兼容重写、压缩来源证据、工具真实 admission execution identity、统一 Agent Inbox 落盘/回滚/满载拒绝、Child History Checkpoint 与完成态再激活/Cold Resume、终态原子结算、Session Projection 同版本共享 / 引用计数 / 异版本拒绝 / stateVersion / asOfSequence、SubagentCapabilities 启动前拒绝、toolAllowlist 实际工具面过滤、稳定 instructions 与 task 分离及持久恢复 V1/V2→V3 兼容、三级审批 DEFAULT/MANUAL/AUTO 迁移与安全决策、Tool Activity 由原 EventLog 有界派生且未知副作用状态不丢失、Structured Result 纯 JSON / Schema mismatch / 未支持关键字拒绝 / 校验后终态提交、持久 resume payload 超限显式失败、Agent Teams roster/mailbox/task DAG/revision/blockedBy、`fresh|fork` 创建语义、Child step=0 fork seed 恢复与官方 V2 whole-value event 兼容。基础 AgentLoop 和存在稳定官方可执行 seam 的高级 Projection 使用锁定官方源码生成的 golden；Android 进程恢复等平台特化语义继续由本机契约测试证明，不把自测包装成官方输出。

### relay-conformance

该 lane 驱动真实 `dsh-relay`，用于证明 Android 客户端与真实中继实现的一致性，避免客户端与 Mock 同时理解错误却仍然全绿。

- 上游固定为 `sorsama/deepseek-harness-relay` 的 `0.2.1` 对应提交 `10c2758e77192413d9450a4d641daafb2a675286`；
- CI 显式设置 `DSH_RELAY_CONFORMANCE_REQUIRED=true`，缺少源码、Node 或可用网络地址时直接失败，不允许通过 JUnit assumption 静默跳过；
- 覆盖真实配对、Bearer 认证、未认证拒绝、WebSocket 升级与 TLS 公钥固定；
- Relay 测试本身、CI Relay 控制面和完整产品改动必须执行该 lane。

### build-arm64

同一次 Gradle invocation 执行：

- `:app:lintDebug`
- `:app:assembleOptimized`
- arm64-v8a APK 结构 / ABI / ELF / 16 KiB 对齐 / 签名验证
- optimized APK 体积预算

当前 CI APK 上限：

```text
94371840 bytes（90 MiB）
```

Runtime 下载复用 `setup-runtime-cached` 组合 Action：在完整 APK 构建前分别按 OS、ABI、Runtime 脚本及其下载/签名校验逻辑的哈希恢复缓存，调用原有 Gradle 任务完成 Node.js、Python、Git、HDiffPatch 的签名、版本与 SHA-256 校验，并立即保存已校验的包。后续 Lint、APK 构建或设备测试失败，不会再导致本轮已准备的 Runtime 缓存因 Job 结束失败而无法保存。命中缓存仍执行既有完整性校验；缓存缺失或不匹配时按原脚本重新下载，禁止放宽签名或校验条件。

`build-arm64` 与 `device-artifacts-x86` 分别使用单一 ABI 缓存，Android 16/17 仍只消费同一组 x86_64 测试 APK。运行时间和缓存命中情况写入对应 Job 的 `GITHUB_STEP_SUMMARY`；这仅用于性能诊断，不参与放行。Gradle 已启用 Build Cache 与 Configuration Cache，本次不另建不受控的构建缓存、不跨 PR Head 复用 APK。

验收应按改造前后可比样本分别记录冷/热缓存、ARM64/x86_64 构建、Android 16/17 模拟器、整个 CI 的中位与 P95 耗时。目标是完整 CI 中位耗时减少至少 30%、P95 减少至少 20%；这些是验证目标，未经重复实测不宣称达标。静态、架构和单测仍尽早给出失败反馈，全部产品源代码改动照常执行完整矩阵。

### device-artifacts-x86

一次构建生成：

- `app-debug.apk`
- `app-debug-androidTest.apk`
- `app-optimized.apk`

并先对 x86_64 optimized APK 执行结构 / ABI / ELF / 16 KiB 校验，再作为短期 Artifact 交给两套 Android 模拟器。Android 16 / 17 因而验证同一套二进制，不再分别调用 Gradle 重建 App。

### Android 16 / Android 17

两条 lane 在 `scope` 后开始模拟器准备工作，与 `device-artifacts-x86` 构建重叠；两套设备均需在共享 APK 当前 Run 下载成功后才开始安装和仪器化测试：

- 下载同一 `android-x86_64-test-apks`。
- 安装 debug + androidTest APK。
- 直接调用 `AndroidJUnitRunner` 执行 instrumentation。
- Compose 高风险交互回归启用 Accessibility Test Framework 自动检查；TalkBack 等真实辅助技术体验仍保留设备复验。
- 验证 debug 启动。
- 安装相同 optimized APK 并执行 startup smoke。
- Android 17 继续使用 Android 37 / 16 KiB page-size 系统镜像。

### merge-gate

`merge-gate` 根据 scope 只要求本次选中的 lane 必须成功；未选择 lane 可以合法 skipped，失败或取消不能放行。完整产品改动要求：

```text
static-gates
architecture-3-gates
unit-tests
relay-conformance
build-arm64
device-artifacts-x86
android-16-instrumented
android-17-instrumented
```

fixture 来源变化再额外要求 `fixture-provenance`。

## 官方差分验证

`upstream/deepseek-harness.lock.json` 锁定本机语义参考。

`reference-validation` 的目标：

- 使用相同测试向量驱动本机核心。
- 对比官方黄金结果。
- 在官方基线更新时显式暴露行为差异。

日常 CI 不自动追随上游最新版，防止无意改变产品语义。基础 golden 覆盖 AgentLoop 核心调度；advanced runner 直接运行锁定官方源码，当前验证 Session Projection Registry 的同版本共享、引用计数、异版本拒绝、stateVersion / asOfSequence，并验证官方 Agent Teams `agentTeam` Projection stateVersion=4、V2 member/task/message whole-value 事件、任务依赖与 queued-minus-delivered mailbox。Request Reconstruction、Continuable Agent 的 Android 进程恢复、Structured Result 等没有等价官方可执行 seam 的平台语义继续由对应模块契约测试证明；禁止把本机自测包装成官方 golden。

## 架构门禁

`architecture-3-gates` 分成两类可独立诊断的检查：

- **所有权与依赖门禁**：Feature / Shared Capability / Runtime Kernel 单向依赖、跨 Feature Port、FeatureCatalog 唯一路由归属、领域状态单写、应用组合根唯一装配、UI 只消费 presentation / Feature API / projection、Shared 保持中立、Kernel 保持极薄。
- **执行不变量门禁**：有界历史访问与 streaming、模型历史缓存、Session / Agent run ownership 与迟到提交、进程唯一资源调度器、MODEL_REQUEST 租约、工具执行唯一策略入口、模型 route/profile 冻结、Session 持久化与恢复顺序等运行时行为。

当前架构门禁以真实结构和运行不变量为准：

- 每个 Feature 的领域 Owner、状态 writer、配置 writer 和公开 Port 必须唯一；
- Shared Capability 只承载跨 Feature 的中立能力，并通过稳定契约被消费；
- Runtime Kernel 只承担进程 start-once、生命周期 scope、bootstrap / recovery 触发与初始化错误投影；
- Feature / Provider 构造集中在应用组合根，生产装配不得出现同一能力的多个实现；
- UI 只通过 presentation / Feature API / projection 消费，不能直接穿透领域 Store / Coordinator / Runtime；
- FeatureCatalog 的 route owner、页面 contribution、Back / Drawer / restore policy 必须唯一；
- `LocalHarnessState` 只作为 Runtime-owned 兼容聚合 / 只读投影，不作为领域权威写入口；
- Session ownership、Agent run identity、迟到提交栅栏与 recovery coordination 保持单一共享运行事实源；
- 当前若存在架构债务，必须精确记录消费文件、依赖边、退出条件和验证方式。

架构完成条件不使用方法数、构造依赖数、聚合状态字段数、UI projection 字段数或文件行数等代码形状指标。

门禁失败必须修复真实所有权、依赖方向、装配关系、状态归属或运行不变量，不能通过提高数字预算、改名、等量代理替换或移动文件规避。

## 性能门禁

性能门禁防止重新引入：

- transcript 全量物化。
- Session 历史热路径全扫描。
- streaming 字符串无界拼接。
- model history 无缓存重复计数。
- 已知高频路径的阻塞 / 重算模式。

## 功能专项验证

新增能力必须在通用 CI 外补自己的专项测试。

### 运行诊断与 Work 上下文

- 同一 Session 的前台 Work、Automation Chat / Work、会话删除必须做对抗时序验证：前台 Work 在用户消息落盘和 binding 快照前取得 Session owner；Automation 占用时只允许持久排队；删除必须等待既有 Automation owner 并在 durable 删除完成前阻止新 owner 进入。
- Automation Chat 必须保持“可见 turn → Session owner”的唯一锁顺序，并把可见 turn / Session owner 等待计入该次任务总超时；不得恢复反向取锁。
- “立即运行”撞上同任务已有执行时必须保留为重试/排队，禁止返回成功但实际不执行；旧 `scheduleGeneration` 必须在抢任务执行租约前淘汰，拿到租约后再次复核。
- Automation Worker 取消或 generation 失效后，模型和工具边界在开启下一次副作用前及迟到结果提交前都必须检查取消状态。已经发送到外部且不可逆的动作不声明可回滚，但不得继续开启新的副作用或把迟到结果提交到本地运行链。
- 主动互动生成期间出现新的真实用户活动时，无论活动落在 `user/message` 还是持久 Agent inbox，本轮旧上下文生成结果都必须在助手消息落盘前作废。
- 前台 transcript bootstrap / older batch 除单页大小外必须保留整个调用的页数与原始消息扫描预算；预算耗尽时保留 `nextCursor`，后续继续分页，禁止通过一次调用重新形成无界历史扫描。

- 分享诊断必须区分当前导出版本与持久日志真实来源版本/进程，旧版本日志不得被误包装成当前版本事实。
- 当前 Session 的 run / turn / step / tool / request / retry / continuation / compaction 等持久事件应可按 sequence 串联；分享报告只输出结构化白名单字段和 payload 大小，不复制任意消息、工具结果或凭据正文。
- Token 诊断复用 `TokenUsageAnalyticsStore` 请求级账本，至少保留 action、run/parent/agent/step、真实 route、input/cache/output/reasoning 与 Prompt 构成；禁止再建第二套 Token 事实源。
- Work 大上下文在请求前派生“可信检查点 + 近期原文”的有界投影；SessionEventLog 和持久模型历史仍保留完整事实。Chat 不继承 Work 专属稳态投影阈值。
- Chat / Work 必须通过共享请求上下文治理入口进入模型请求；模式策略可以不同，但请求压力、缓存连续性、overflow 与 checkpoint envelope 不得再各建平行事实源。
- 主 Agent / 子代理必须使用冻结的 `LocalRunModelSurface` 解析 route capabilities，并经共享模型请求资源边界发起 provider 调用；统一 `LocalAgentModelStepRuntime` 必须负责 bounded retry、取消传播和恢复外循环，领域代码只提供具体 recovery policy；运行期间修改当前模型选择不得改变已启动 Run 的工具面更新语义。
- Chat 定时事件规划不得直接形成无治理的模型调用旁路：必须冻结实际 route、经过共享 `LocalAgentModelRequestRuntime` 输入 admission，并复用 Model Step 的有限重试/取消语义；辅助调用不得自建第二套资源调度器；候选落盘前必须核对 session、usage mode、单聊/群聊、人物身份、最新对话消息和 ChatContext generation，旧候选不得覆盖新对话状态。
- continuation 只允许用于明确 `continuationEligible` 的 post-admission 失败并受统一次数上限约束；不得把 continuation 退化为原请求重放。
- durable 大工具结果必须复用统一 recoverable projection：完整结果可恢复、模型侧预览有界、EPHEMERAL 结果不得伪装成可恢复落盘结果。
- 每个模型 step 只能执行该 step 实际发送给模型的工具集合；可选工具经 capability_search 启用后只能从下一次工具面更新开始调用，模型幻觉或兼容供应商返回未暴露工具时必须 fail closed。
- 工具声明、审批与实际执行开始必须分开验证：审批前中断应恢复为 TOOL_NOT_STARTED；只有持久化 execution-started 后无结果才允许判定 TOOL_OUTCOME_UNKNOWN。修改类工具一旦进入执行器，异常/超时不得再标记为安全自动重试。
- 计划模式和只读子代理必须按本次调用参数判断副作用；web_fetch 前台读取与大型 json_query 结果不得落盘工具产物，run_in_background 不得在只读/规划作用域创建持久任务。
- 新生成的压缩检查点必须携带明确的 chat/work 类型；旧 Work v1 provenance 只允许作为恢复兼容读取，新 Chat 压缩不得写 Work 专属 provenance。
- 模型候选状态不得直接覆盖运行时事实：Chat 长期人物状态和 Work 目标状态都必须经过运行时 transition policy；Work 仍有 pending/in_progress Todo 时禁止把目标标记为 completed。
- 输出质量守卫通过统一 `LocalOutputQualityPipeline` 调度，共享 inspect/result 契约但保留领域规则；Chat 仅做高置信最小修复，Work 完成声明与 Todo/Goal 状态冲突时必须在最终交付前改为真实未完成状态，并记录脱敏诊断。
- Work 请求投影必须保持工具调用/结果批次合法，投影后 Token 必须实质下降；小上下文不得无意义重写。
- Work 压力增长必须用 source→source 比较，不能把上一请求的投影后压力与当前完整历史比较；DeepSeek/OpenAI 缓存敏感路由在绝对阈值前不得因该错位触发提前压缩。
- Work 工具结果超过 4 KiB 时必须先持久 spill；模型可见额度必须只有一处事实源（写时保留、请求投影、恢复分页共用同一数值），新鲜结果保持 4 KiB 原样、陈旧结果压缩到约 1 KiB，均可按 `call_id` 恢复；`tool_output_read` 默认分页 4 KiB，单页交付不得超过该可见额度，禁止默认一次重新灌回 24 KiB。
- DeepSeek 官方路由保持共享前缀与工具 schema 顺序稳定，并以供应商实报 hit/miss 验证；OpenAI 官方 GPT-5.6+ API Key Responses 的缓存键/30m TTL 只在能力快照允许时发送，自定义兼容地址不得继承。
- Work 稳定运行时/长期规则必须位于历史前部且跨 turn 保持字节稳定；按当前 query 召回的记忆、handoff 和其他动态上下文只能出现在当前 user 附近，禁止重新插入 system 后第二条破坏整段前缀。
- DeepSeek `APPEND_ONLY` system/tool 能力必须被运行时实际消费：system 更新追加，工具新增只追加；工具撤销、schema 变化或无法识别的工具结构必须立即退回完整替换，不能为缓存保留失效能力。
- 正常 Work turn 在首个模型 step 后不得主动语义压缩；完成、失败、步数耗尽等持久 turn 边界再执行压缩。真正的 provider context overflow 仍允许轮中紧急压缩恢复。
- overflow recovery 后若压缩后的请求成功，缓存连续性必须记录最终实际发送的 `activeMessages` 与对应 generation，禁止把压缩前 `requestMessages` 记成成功基线。
- 缓存成本诊断必须保留供应商实报的 cache write Token；cache write 属于 miss 子集，只作为成本细分，不能再次增加 input/total Token。
- 缓存敏感路由必须记录成功请求的前缀连续性：消息只允许尾部追加且工具面保持一致时 generation 不变；历史重写或工具面变化必须开启新 generation；失败请求不得更新成功基线。
- Work exposure 成功结算以供应商实报 input Token 为准；累计 exposure 仅用于诊断，不得作为单轮 Work 的硬输入上限；客户端估算用于单请求上下文准入、并发 pending 风险控制与未知受理校准。
- 并发 pending reservation 只按当前在途估算量做有界等待；已有累计 exposure 不得阻塞后续请求。单个请求仍必须遵守当前路由上下文窗口 / operational limit，pending 释放语义与请求次数护栏保持有效。
- 本地 preflight 拒绝必须带稳定 code / failure_kind / admission_state / origin，禁止伪装成供应商模型故障。
- Work 传输层路由健康必须按物理路由指纹跨 run 共享：连续 3 次可归因传输失败进入冷却；冷却结束只允许一个 half-open 探测；成功清零、探测失败指数延长且上限 15 分钟。用户取消、本地预算/上下文拒绝不得累计路由失败；进程级状态必须有容量和空闲淘汰边界。
- 远程会话异步请求必须绑定发起时的 host/session scope：快速切换会话或主机后，旧请求的成功结果、失败结果、loading 结算和错误横幅都不得写入当前界面；host-scoped plugin/preset/catalog 同样必须拒绝旧 host 结果。
- “上次打开会话”持久化必须 latest-wins：并发 A→B 快速切换时，即使 A 的持久化更慢，最终落盘也必须是 B；不得依赖协程调度顺序。

当前高风险领域：

- Session 恢复和未知副作用。
- 主 / 子代理 run 归属。
- Chat 连续性与人物记忆隔离。
- 发送排队 / 拒绝语义。
- Token 账本与去重统计。
- MCP / LSP 生命周期。
- VirtualDisplay 资源隔离。
- Runtime / APK 布局和实际执行。

### 多模型协议运行时

专项验证必须用同一组语义契约覆盖所有已注册协议，而不是只验证“请求能返回文本”：

- Chat Completions、Responses API Key、ChatGPT 套餐 Responses、Anthropic Messages 均验证文本、图片、工具调用、工具续轮、usage、截断、错误和取消。
- DeepSeek、MiniMax、月之暗面、GLM、Gemini OpenAI-compatible、Qwen 的既有协议必须保持 Chat Completions，不因新增 Adapter 改写供应商 payload。
- 官方 Claude 档案使用 Anthropic Messages；旧官方 Claude 档案迁移到原生协议，自定义兼容地址保留用户保存的协议。
- Anthropic 流式事件至少覆盖 `message_start/content_block_start/content_block_delta/content_block_stop/message_delta/message_stop`；`text_delta`、`thinking_delta`、`signature_delta`、`input_json_delta` 必须按块正确组装。
- Anthropic thinking/signature 和 tool_use 仅在相同 adapter + auth/profile + baseUrl + model 路由中无损 replay；切换账户、模型、地址或协议后不得把供应商私有状态发往新路由。
- Responses continuation 同样通过通用 replay envelope 保存；通用 `_dsh_model_replay` 只能存在本地历史，禁止作为未知 wire 字段发送给任何供应商。
- Gemini `thought_signature` 等 Chat-Completions 私有工具 metadata 在 Canonical round-trip 中保持，但只能由对应 Chat adapter 投影回 wire。
- 新会话的 Agent 事件使用 provider-neutral `model_tool_calls` 做恢复判定；旧会话继续兼容 legacy `tool_calls`，迁移不能中断历史恢复。
- 前台 Run、子代理与工具链冻结模型 profile；运行中切换 UI active profile 不得改变已经启动的模型请求、Vision 或工具子调用。
- Vision 生产请求必须携带冻结 profile 并重新进入统一 Gateway；不得退回自行拼接 `/chat/completions` 的可变活动模型旁路。
- Retry 只在同一路由执行；不得因网络、429 或 5xx 自动切换供应商/API Key，避免重复工具副作用和隐式计费。
- 模型能力由 route capability snapshot 描述并由统一 Gateway 实际执行：已知不支持的工具/图片在网络请求前拒绝，replay/streaming/temperature 服从本次冻结快照；图片上限、采样字段和协议能力必须保留 #345 已验证的供应商差异。\n- 成功模型回复与 Token 明细必须记录不含密钥的实际 route identity；同名模型、多账户和代理地址可区分。DeepSeek 官方价格只允许用于 DeepSeek 官方 host，未知或第三方路由只累计真实 API usage 并标记未定价。

### ChatGPT 套餐模型

专项验证至少覆盖：

- API Key 与 ChatGPT 套餐 profile ID 隔离。
- OAuth callback 编码、state/nonce/PKCE 与 ID Token 验证路径。
- access token 过期时 single-flight refresh，refresh token 轮换后原子替换。
- ChatGPT 身份登录与套餐推理授权必须独立建模：缺少任一套餐推理 scope 时保留合法登录身份，但不得发现、暴露或继续使用套餐模型 Profile。
- refresh 成功后若套餐 scope 降级，必须先原子保存轮换后的 access/refresh token 与新 scopes，再退休对应套餐 Profile；当前活动套餐路由立即变为未配置，禁止静默回退到 API Key 或其他可能计费来源。
- Responses 请求固定 `store=false`、`stream=true`；不发送套餐共享暂不支持的采样字段。
- ChatGPT 套餐 HTTP Responses 每次必须有非空 `input`；system 内容只进入 `instructions`，后台状态整理不得产生 system-only 请求。
- ChatGPT 套餐请求收到成功 HTTP 响应后若 SSE 在 `response.completed` 前断开，视为“执行结果未知”，禁止自动整轮重放；仅明确的服务端可重试错误按官方恢复语义退避。
- 运行诊断必须记录脱敏的 profile/account/client_id 绑定、OpenAI request ID、provider code 与 HTTP status，禁止记录 access/refresh token。
- ChatGPT 模型档案必须绑定保存的 issued client_id 对应账户；切换模型档案时同步账户选择，禁止用可变“当前账户”替代档案 credentialRef。
- ChatGPT 套餐共享的 function/custom tools 必须按 SIWC 预览契约放入 namespace；标准 API Key Responses 继续使用普通顶层 function tools，不相互污染协议。
- 协议选择以模型档案的认证类型与协议为边界：ChatGPT 套餐固定走受限 Responses 契约；API Key 的 Responses / Chat Completions 保持各自原有行为。
- OpenAI GPT-6 系列按模型能力发送采样字段：Astra 不发送 `temperature`；Sol/Luna 在 Chat Completions 需要采样或工具调用时显式使用 `reasoning_effort=none`；其他供应商不继承该限制。
- API Key Responses 必须使用该模型档案自己的 base URL 拼接 `/responses`；只有 ChatGPT 套餐共享强制使用 OpenAI 官方 Responses 地址。
- Vision 与“测试连接”必须复用同一协议路由，禁止 Responses-only 模型在旁路功能中退回 `/chat/completions`。
- Responses SSE 的顶层 `error`、`response.failed`、`response.incomplete`、refusal 与 completed 均需保留真实语义；标准 Responses 错误不得映射成 ChatGPT 套餐错误。
- DeepSeek 官方网页搜索只允许读取 DeepSeek 官方 API Key，不得把 OpenAI、Gemini、Qwen、ChatGPT OAuth 等当前模型凭据误发给 DeepSeek 搜索接口。
- Work 默认暴露 web_search、web_fetch，切换模型不得清除默认联网工具；网络工具不会扩大文件/进程权限。
- web_search 默认走无密钥 Bing RSS 公共搜索，失败后只可使用明确配置的 DeepSeek 官方 API Key 备用；任何模型账户凭据不得转发给公共搜索服务。
- Chat 与群聊无需模型支持工具调用；只有在明确联网或实时信息意图时才发起应用层检索，群聊一轮最多一次，并且结果仅进入当轮临时上下文，不进入长期会话历史。
- system message 不进入 Responses input，转换为 `instructions`。
- 文本、图片、function tool、function_call_output 和 continuation item 转换。
- 切换到 Responses 时，旧 Chat Completions 的每个 tool_call 都转换为同 call_id 的 function_call；原始 Responses output 优先重放，不重复添加调用。
- Responses function schema 显式保留 strict 设置；未声明时使用 non-strict，不能把可选工具参数隐式变成必填。
- 子代理按 profileId 绑定独立 model/baseUrl/protocol/authKind/credentialRef；不得修改父代理活动档案。同名 API/套餐或多账户模型必须消除来源歧义。
- list_subagent_models 只展示有凭据的已保存档案，不硬编码 DeepSeek 列表。
- 自定义 API 协议选择要同时用于保存和连接测试，并在重新加载、替换 Key 后保留。
- 套餐模型目录结构异常必须报错，不得静默解释为空目录而覆盖已有档案；标准 API data[].id 不能被当作套餐共享授权依据。
- Vision 对所有供应商记录实际 API usage；价格未知时不得套用其他供应商价格。
- HTTP/2 `stream was reset:CANCEL`（冒号无空格）和有空格的形式使用同一断流分类；用户主动取消仍遵守 coroutine cancellation。
- 浏览器授权未完成、网页失败或用户返回应用时，可显式取消当前 loopback listener 并重新开始授权；取消后不得残留“授权进行中”互斥状态。
- 重新授权已有记录必须复用已保存 issued client_id；“添加账户”仍允许创建新的注册记录，同邮箱/同 subject 的不同 issued client_id 不得静默合并。
- 终止性 refresh token 错误只清空失效 token，保留 issued client_id + identity 映射用于重新授权；临时网络/服务端错误不得清凭据。
- ChatGPT“测试连接”必须走该授权记录自己的套餐 profile，并以真实 Responses 完成作为成功条件；不得只用 /models 可达代替推理连通。
- 多条授权记录可由用户显式移除；移除一条记录不得误删其他 client_id 的模型档案或凭据。
- refresh token 被确认失效并清空后，设置页必须读取最新持久化状态并回到“需重新授权”，不得继续用旧快照显示已连接。
- App 回到前台时，ChatGPT 刷新必须经过 presentation 账户工作流，同时完成远端模型目录与本地套餐模型档案对账；不得只刷新 OAuth/UI 状态而留下旧模型选择器。
- 模型目录暂时不可达时，已登录且套餐权限仍有效的账户必须保留既有套餐模型档案；只有确认未登录或套餐权限失效时才退役对应档案。
- 后台模型目录刷新不得在本地任务仍运行时改写活动模型路由；目录变更应延后到空闲态的后续刷新，显式账户/模型切换仍受运行中准入门禁保护。确认套餐授权失效时仍应立即撤销后续可用资格，当前运行使用冻结路由处理其自身终态。
- ChatGPT 账户断开/删除必须先在空闲态撤掉对应本地套餐模型档案；该本地变更一旦提交，后续账户断开/删除与下一账户对账必须不可取消地收尾，禁止因页面退出或协程取消留下半状态。
- ChatGPT 套餐连通测试属于账户 / 模型边界职责，放在独立 tester 中；`LocalRuntimeKernel` 只维持生命周期与 bootstrap / recovery 边界，不承载账户、模型发现或连通测试业务。
- `response.completed`、`response.failed`、`response.incomplete` 与流中断分别处理；收到 `response.completed` 后立即按成功终态结算，不再继续等待连接 EOF，避免终态后的 TCP/HTTP2 收尾抖动触发重复请求。
- 统一 `withCancellableModelResponse` 只让读取/协议阶段错误决定请求成败；成功结果产生后的 `Response.close()` 清理异常必须降级为 best-effort，不能反转成功并触发重复发送。
- `response.completed` 前发生可恢复网络中断、超时或临时服务错误时继续使用有限次数退避重试；用户主动取消、参数/权限错误、套餐限制等不可恢复状态不得盲目重放。
- 本机 Token 账本只记录成功返回的 `LocalModelReply` 中 API 实际 `usage`；失败或中断且未形成成功终态的尝试不得伪造 Token 记录，也不得把提示词估算再次叠加到总量。
- 套餐共享错误按 OpenAI 官方语义区分：用量限制、用量暂不可查、用户/工作区不可用、能力不支持、路由不支持、授权上下文异常。
- `subscription_sharing_usage_limit_exceeded` 只表示当前共享请求受限，不推断整个 Plus/Pro 套餐已经耗尽或自行推断重置时间。
- 流开始前兼容 `{"detail":"..."}` 直接准入错误；保留 HTTP status、request id、provider code/param 与合法 `Retry-After` 供诊断和退避。
- `Software caused connection abort`、connection reset 等真实断流映射为可恢复网络错误，用户界面不直接暴露底层 Java Socket 文案。
- 套餐额度不足不得静默切换到可能产生 API 费用的 API Key。
- Responses namespace 必须完整包含非空 `type/name/description/tools`；内部 function 必须完整包含非空 `type/name/description/parameters`。动态/MCP 工具缺少描述或参数定义时必须在本机适配层补齐合法默认值，非法参数 schema 必须在发起网络请求前本地拒绝。
- Responses 工具未声明 `strict` 时显式发送 `strict=false`，保持既有 Chat Completions 宽松工具语义；只有明确声明 `strict=true` 时启用 Structured Outputs 严格约束。
- OpenAI 专属递归 strict schema 校验只对 ChatGPT 套餐和 OpenAI 官方 Responses 路由生效；第三方/自定义 Responses 仍执行公共 function/parameters 结构校验，但不继承 OpenAI 的 Structured Outputs strict 子集限制。
- `strict=true` 的 function parameters 必须递归满足 OpenAI 约束：每一级 object 均为 `additionalProperties=false`，所有 `properties` 字段均列入 `required`；可选参数使用包含 `null` 的联合类型表达。严格模式下拒绝 `allOf/not/dependentRequired/dependentSchemas/if/then/else` 等当前不支持关键字。OpenAI 官方同时限制 schema 最多 10 层、5000 个 object properties 等总体复杂度；由于 `$ref` 与递归 schema 受支持，这类全图限制由 OpenAI 服务端作为最终权威校验，客户端不做可能误判递归引用的简化深度计算。
- ChatGPT 套餐共享继续遵守 SIWC Responses 限制：`store=false`、`stream=true`、完整历史放入 `input`，system 转 `instructions`；不得发送 `background/conversation/max_output_tokens/max_tool_calls/metadata/moderation/multi_agent/prompt/prompt_cache_retention/safety_identifier/temperature/top_logprobs/top_p/truncation/user/previous_response_id`，本地 function/custom tools 继续使用 namespace，不启用该路由不支持的 hosted MCP、file search、Code Interpreter、native computer、tool_search 或 programmatic tool calling。
- ChatGPT 套餐不得继承 API Key Responses 的 `prompt_cache_key` 或 `prompt_cache_options.ttl`；现有 `comparison_response_id` 仅用于允许的缓存诊断路径。OpenAI 官方 API Key Responses 与套餐共享必须保持字段白名单隔离。
- Chat、Work、主 Agent、子代理、Automation、群聊、人物辅助和 Vision 使用相同模型凭据解析边界。
- 后台 Automation 和一次操作内可能发生二次生成/修复的人物辅助功能，开始请求时必须冻结一个精确 profile；后续重试、去重、格式修复和连续性修复不得重新读取可变 active profile。
- Chat 主回合完成后异步执行的 post-turn 状态归并同样属于原回合的一部分；首次归并、延迟重试和剩余 pending 续批必须一直使用该回合冻结的 profile，用户随后切换账户不得改变其认证来源。
- Vision 的 fallback route 必须携带精确 profile id，并只按该 profile 解析凭据；插件组合层不得使用全局 `apiKeys.get()`，也不得用 `activeProfile()` 覆盖 ToolContext/route 已冻结的身份。
- 同模型 + 同 base URL 的多账户场景必须以 profile id 为最终身份边界；后台任务运行期间切换前台账户不得改变正在执行请求的认证来源。
- 多账户切换、断开、模型被移除、进程重启和旧 `model_profiles_v2` 迁移。

## PR 放行

最终验证必须针对：

```text
current main + current PR head
```

以下情况旧 CI 作废：

- main 已更新。
- PR 新增提交。
- 解决冲突 / merge / rebase。
- 测试、CI、Runtime 或构建配置变化。
- 相关横向功能先进入主线。

只看到“PR 可合并”或“旧 CI 全绿”不能作为最终放行依据。

完整作业规范见 [../AGENTS.md](../AGENTS.md)。

### 多协议合并审计补充

- Canonical 工具转换不得把非法 strict、description、name 或 parameters 改成可发送的默认值绕过协议校验。
- Anthropic `message_stop` 必须同时具备 message 起始身份、明确 stop_reason 与已关闭的内容块；截断和畸形 tool_use 不得形成成功回复或执行工具。
- 终态后的 reader/response close 异常不得覆盖成功；非终态断流和用户取消仍分别走有限重试与取消路径。
- 同路由续轮保留签名 thinking/redacted_thinking，正文和工具调用以当前 canonical 历史为准，不恢复过滤前正文。
- 并行 tool_result 合入同一 user turn；未知媒体块本机拒绝，官方 Claude API 图片按 10 MB base64 编码限制验收（客户端原始字节预算 7,500,000）。
- 官方旧 Sonnet 型号迁移保持 profile id 和凭据归属，代理自定义型号不改写。

- 官方 Vision 文档：https://platform.claude.com/docs/en/build-with-claude/vision；直连 API 与 Bedrock/Google Cloud 的图片限额分别处理，不混用 5 MB 云平台限制。

### 本轮审计修复回归

- 完成门禁：Markdown 全局结论、局部否定、代码与成果链接同时存在时，只纠正冲突结论；可见回复与 canonical 回复一致。
- 异步请求：同一身份离开后返回（ABA）、同渠道后发先回、不同渠道并行及关闭失效。
- 压缩日志：重复页复用解压、删除/替换失效、冷读与缓存命中均执行解压上限。
- 工具结果：结构化成功正文可以讨论错误代码；错误事实经 transcript 编码/恢复不丢失。
- 人物与导入：生活事件不无条件续期、显式故事晚间优先、无 schema/旧 schema 混合新旧字段保留新版已编辑值、非法版本不回退。
- 体验：群聊部分失败/全部失败及单成员重试、任务 Worker 更新实时显示、运行中心全部清单、TalkBack 状态、130% 字号与系统大字、长时间线、明/夜/墨及高对比壁纸均须设备复验。
- 产品源码 PR 必须运行与影响范围匹配的编译、单测、架构及专项检查；main 中影响 APK 的变更、关键高风险改动、正式发布必须运行完整矩阵。静态守卫通过不能代替设备和用户实际操作验收。


### 架构 3.0 审计修复链路回归

- Inbox：含 Team 消息头的边界准入、内存与持久化正文一致、进程重启不丢尾部、超限不生成 QUEUED/DELIVERED；历史超限队列有明确拒绝事实和拆分重发提示。
- 远端分页：A→B→A、同会话新快照、切换主机，以及旧成功/错误/finally 均不得覆盖新会话的正文、加载状态或连接恢复反馈。
- 项目：损坏目录下，绑定项目的 Work/子代理/关联 Chat 拒绝执行；无项目绑定的对话保持可用。
- Automation：同 ID 覆盖、删除重建、旧版代次、WAL 恢复和压缩后仍不复用身份；旧 Worker 不能结算、删除或提交新任务副作用。
- Skill：合法 900 行规则保留最后一行约束；目录枚举只读取元数据；超预算或损坏单项不阻塞其他技能及预置恢复。
- Team 投影：首批扫描有界，后台追赶不占全局命令锁，检查点续读及缓存命中不重扫；跨会话、清空和删除后不复用或重建旧检查点；UI 区分恢复中与空团队。

### CI 模拟器预热与 APK 编译并行（2026-10-09）

选中的 `android-16-instrumented` 和 `android-17-instrumented` 在 `scope`、快速静态及架构验证通过后开始 SDK 初始化/模拟器启动，消除在 `device-artifacts-x86` 构建后才开始模拟器冷启动的串行等待。
设备实际安装前调用 `wait-android-device-artifacts.sh`：通过 GitHub Actions 只读授权按 **当前 `GITHUB_REPOSITORY` + `GITHUB_RUN_ID`** 定位构建产物，仅从此 Run 的 `android-x86_64-test-apks` 下载三个非空 APK。源 Job 失败、取消或等待超时时直接失败。无跨 Run APK 复用，完整 Android 16/17 仪器化、Debug/Optimized 启动冒烟及最终合并门禁均保留。

该优化主要缩短墙钟等待时间，但模拟器 Runner 可能在 APK 编译时占用更长时间；应同时衡量总 Runner 分钟成本。Gradle 已启用并行构建、Configuration Cache 和 Build Cache，不重复分拆同一构建任务。
