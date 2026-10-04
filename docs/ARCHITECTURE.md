# Architecture

777 is an eight-module Android project built with Kotlin 2.2.10, Jetpack Compose, Hilt and Java 17.

The repository uses two levels of boundary:

- **Gradle modules** for binary, platform and protocol boundaries.
- **Capability packages inside `app`** for product evolution without turning every feature into a new module.

## Gradle modules

```text
app/                      Android composition root, local Chat / Work UI and orchestration
core/                     pure JVM remote Harness web-client protocol
harness-core/             platform-agnostic Agent loop, tools, jobs, capabilities and sessions
harness-runtime-android/  Android process runtime and persistent pipe terminal
harness-interop/          MCP HTTP / stdio and LSP
harness-device-android/   accessibility, notifications and virtual display
mock-harness/             Ktor Harness /api test server
reference-validation/     official Harness semantic / conformance validation
```

Main dependency direction:

```text
app
├─ harness-device-android
├─ harness-interop
├─ harness-runtime-android
└─ harness-core

core  ← remote Harness protocol path; independent from the local Agent kernel
```

A new Gradle module is added only when a capability can own a meaningfully smaller dependency set. Otherwise, package boundaries and CI ratchets are preferred.

## Local Android architecture

```text
Compose UI / ViewModels
        │
        ▼
local.presentation
  ├─ LocalUiRuntime
  ├─ Chat / Work surface projections
  ├─ Settings projection
  └─ Task projection
        │
        ▼
capability runtimes
  ├─ local.chat
  ├─ local.work
  ├─ local.session
  ├─ local.model
  ├─ local.tools
  ├─ local.automation
  └─ local.usage
        │
        ▼
LocalHarnessEngine
  cross-capability turn/session orchestration
        │
        ▼
Coordinators / Stores / Repositories
        │
        ▼
harness-core / Android runtime / MCP / device providers
```

### UI projections

Chat and Work are projections of one runtime, not two independent engines.

`LocalConversationSurfaceState` exposes only the fields relevant to the product surface. Fields owned by the other mode stay at stable defaults, and projections use `distinctUntilChanged`, so Chat-only churn does not wake Work UI and vice versa.

High-frequency streaming preview is kept separate from aggregate state, avoiding full state rewrites for token-by-token output. The preview is transient but not anonymous: every visible stream is owned by an explicit `sessionId + requestId + usageMode`, and UI surfaces render only a matching owner. Late deltas or completion from an older request therefore cannot overwrite or clear a newer preview.

### Capability runtimes

`LocalUiRuntime` is a dependency-only aggregator. Ownership remains in narrow runtimes such as `LocalChatRuntime`, `LocalWorkRuntime`, `LocalSessionRuntime`, `LocalModelRuntime`, `LocalToolsRuntime` and `LocalAutomationRuntime`.

New UI and worker code should enter through the relevant capability runtime instead of calling `LocalHarnessEngine` directly.

### Orchestration boundaries

`LocalHarnessEngine` owns consistency across a local turn/session. Focused behavior lives in extracted coordinators for model transport, tool execution, Chat preparation/finalization, Session persistence, Agent run recovery, automation, group Chat and sending.

Work 的计划、待办和目标变更由 `local.work.LocalWorkProgressCoordinator` 统一处理，包括输入规整、数量/长度上限、状态更新、事件 payload 和持久化调用顺序。Engine 只选择本次调用绑定的状态、事件日志和持久化回调；后台 Work 始终使用原 run 的会话，不跟随当前可见会话。

角色设置的确认保存由 `local.chat.LocalCharacterBehaviorTuningCoordinator` 编排：复用 Engine 的会话转换锁，检查会话和人物归属，等待人物、人物库与会话快照落盘后才返回成功。`CharacterBehaviorTuningPersistence` 只负责调节版本合并与持久副本收敛；单聊/群聊恢复不会借用默认人物的身份回写。界面保存锁按弹窗生命周期保持，不随初始值回显重置。

The Engine has CI-enforced line, dependency and public-surface ratchets. New responsibilities must move outward rather than expanding the central orchestration surface.


### Model accounts and transport

本机模型运行时把账户、路由、通用语义和供应商线协议拆成独立边界：

```text
Chat / Work / Agent / Automation / Vision
                  │
                  ▼
          LocalModelGateway
                  │
        resolve once per run/request
                  ▼
        LocalResolvedModelRoute
      ├─ profile / auth identity
      ├─ provider / model / baseUrl
      ├─ protocol / capabilities
      └─ replay route fingerprint
                  │
                  ▼
       Canonical model vocabulary
      ├─ message/content/reasoning
      ├─ tool definition/call/result
      └─ provider-neutral replay envelope
                  │
                  ▼
       LocalModelAdapterRegistry
      ├─ OpenAI-compatible Chat Completions
      ├─ OpenAI Responses
      └─ Anthropic Messages
```

API Key 档案继续兼容既有 `model + baseUrl` 标识；ChatGPT 套餐档案额外绑定认证类型和账户身份，避免同一 OpenAI 模型在 API Key 与套餐登录之间覆盖凭据。ChatGPT 登录后的模型目录以 OpenAI 返回的可见模型为准，不把套餐模型永久写死在客户端预设中。

模型调用开始后，前台 Agent、子代理及其工具通过 `LocalModelRunContext` 继承冻结的 profile；Vision 优先读取同一运行上下文，禁止在一个已启动 Run 内重新查询可变 active profile。普通设置页连通测试等非 Run 操作才按显式 profile 或当前活动档案解析。

上层 Agent、历史压缩和恢复逻辑依赖 Canonical 消息/工具语义，不把供应商私有字段作为控制协议。供应商继续执行请求所需的私有状态存放在 `LocalModelReplayEnvelope`；它绑定 adapter、认证类型、profile、base URL 和 model 的路由指纹。同一路由可无损重放 Responses continuation、Anthropic thinking/signature 等状态；模型、协议、地址或账户变化时自动丢弃私有 replay，只保留通用文本和工具语义。

ChatGPT 套餐始终使用 OpenAI Responses；普通 Responses API Key 保持自己的 base URL。DeepSeek、MiniMax、Kimi、GLM、Gemini OpenAI-compatible 与 Qwen 继续走既有 Chat Completions 客户端，不因新增协议改变 payload、reasoning、工具调用或流式语义。官方 Claude 档案使用 Anthropic Messages，旧官方 Claude 档案在加载时迁移；自定义兼容代理不会被强制改协议。

Responses、Chat Completions 与 Anthropic 的工具 schema、流事件、错误、取消和 provider-private replay 都封装在各自 Adapter/Client 内。ChatGPT 套餐的 SIWC 限制仍只作用于 OpenAI Responses Adapter，不传播到其他供应商。

### Send path

`LocalSendCoordinator` is the single local admission policy:

```text
draft
→ validate configuration / transition / queue capacity
→ STARTED | QUEUED | REJECTED
→ explicit UI feedback
```

The composer keeps the draft when runtime rejects a send. Queue-full, session-transition, loading and unconfigured states remain distinct facts.

### Agent run context and recovery

Foreground, subagent and automation execution share `LocalAgentRunCoordinator` checkpoints.

```text
sessionId
→ turnId / runId
→ parentRunId
→ agentId
→ tool call
```

Recovery does not blindly replay side effects. A started tool whose result is unknown becomes `TOOL_OUTCOME_UNKNOWN`; one that never started becomes `TOOL_NOT_STARTED`.

Work 请求由 `LocalWorkRequestContextProjection` 生成：完整历史持久保留，模型侧采用可信任务检查点与最近因果链，超过 2 KiB 的 Work 工具结果先写入 Session 私有输出存储，模型只保留约 1 KiB 的可恢复预览，并通过 `call_id` 分页恢复。Work Prompt 分成稳定前缀与动态尾部：固定运行时事实和长期规则紧跟基础 system，当前查询召回的记忆/交接信息只放到当前 user 前，避免每个新 turn 改写历史前端。未知/自定义路由继续使用 28k 稳态目标与 36k 绝对触发；DeepSeek 官方与 OpenAI 官方按冻结路由的 `LocalPromptCachePolicy` 使用更晚的模型窗口比例阈值。DeepSeek 的 `APPEND_ONLY` 能力在运行时生效：system 规则更新追加到历史，已暴露工具保持原顺序与原 schema，新能力只能追加；工具撤销或 schema 变化视为权威边界，立即切换新工具面。Work 同时保存原始 source pressure 与实际 request pressure，增长判断只比较同一 source 坐标。正常 Work turn 只在入口和持久 turn 边界主动语义压缩，轮中依赖 provider overflow recovery 作为硬安全例外；overflow 成功后的最终 `activeMessages` 才能成为下一请求的缓存连续性基线。DeepSeek 依赖服务端自动前缀缓存；OpenAI 官方 GPT-5.6+ API Key Responses 可使用稳定 `prompt_cache_key` 与 `prompt_cache_options.ttl=30m`。ChatGPT 套餐继续遵守 SIWC 限制，不发送这些 API Key 专属字段。

### Chat / Work 共享智能体底座

Chat 与 Work 继续保留各自领域策略，但高阶运行能力通过共享契约回流：`projectLocalRequestContext` 是统一请求上下文治理入口，当前只由 Work 启用语义稳态投影，Chat 明确保留自身阈值与连续性策略；请求压力按 usage mode 保存 request/source 两套坐标，避免模式专属诊断继续渗入共享请求协调器。

历史压缩使用带 `work/chat` 类型的统一可信 checkpoint envelope；旧 `_dsh_work_checkpoint_source=history_compactor_v1` 继续只读兼容，新 Chat 压缩不再伪装成 Work checkpoint。模型提出的状态变化通过 `RuntimeStateTransitionPolicy` 交给运行时裁决：Chat 的长期人物状态仍由运行时拥有，Work 目标在存在未完成 Todo 时不能直接落为 completed。输出质量守卫也采用共享协议：Chat 可做高置信最小修复，Work 对“完成声明与运行时状态冲突”只记录结构化诊断，不擅自改写模型正文。

持久事件遍历统一使用 `LocalSessionEventLog.withEvents` 的作用域快照；读取期间固定字节边界，遍历提前返回、消费者异常和正常完成均关闭全部文件与解压器，流不能逃逸作用域。

### Tool and plugin composition

Platform-specific providers are built by `LocalPluginCompositionFactory` / `LocalPluginComposition`, not by the Engine.

`LocalToolPolicy` 统一声明内置工具的暴露策略；常用执行工具常驻，低频工具仍完整注册，通过任务意图预激活或 `capability_search` 按需暴露。`LocalToolExecutionCoordinator` 持有每次 Work 的激活编排，GitHub 只读取当前及有限最近真实用户意图；计划模式在投影前执行原权限过滤。

Built-in plugins are described by `PluginDescriptor` and registered in a `PluginCatalog`. `PluginManager` resolves dependency order and minimum versions before lifecycle mutation, prevents disabling providers with active dependents, and keeps descriptor/catalog state synchronized with the live registry. Runtime replacement uses `PluginRegistry.replace`: registry mutations are staged and published together after admitted tool calls drain (up to 30 seconds). Failed replacements clean up the candidate and reinstall the previous plugin, publishing the newly created resources instead of old closed references. If resource restoration or cleanup fails, tools fail closed until the failed plugin is successfully disabled. UI management resolves the active instance under the lifecycle mutation lock. The Android composition rejects hot replacement or disabling of runtime/device providers that are also retained by long-lived owners; changing these providers requires restarting the runtime.

Startup plugin installation remains atomic. Dynamic MCP disconnect stops new admission, drains in-flight calls, unregisters tools, then closes transport. Downstream Agent code depends on capability contracts rather than Android UI classes. External DEX/JAR loading is intentionally outside this trust boundary until the plugin API is stable; the current hot-swap contract applies to trusted in-process plugin definitions.

### Web capability boundaries

`LocalWebProvider` 保留网页获取、通用 HTTP 请求与下载入口。`local.web.LocalWebSearchClient` 负责 DeepSeek 辅助搜索协议、结果格式化和真实 API usage 归属；`LocalWebDiagnostics` 负责 DNS/代理/VPN 事实及有上限的 HTTP/TLS 探测。获取与诊断共用同一个 `LocalWebTargetResolver`，安全地址判断与路由构造仍只有一个实现；`LocalWebHttpPolicy` 提供搜索与 HTTP 共用的有界响应读取、User-Agent 和传输错误分类。搜索、诊断与通用 HTTP 保留各自的重试边界。

### Chat continuity and memory

Chat keeps persona definition, relationship memory, scene continuity, character evolution and user behavior tuning as separate concerns.

Relationship memory uses a stable subject key; Gallery identity wins over copied persona identity. `CharacterBehaviorTuning` changes expression and pacing but cannot rewrite trust, shared events or other historical facts.

### Character diary and cross-chat memory

角色日记是 Chat 的长期叙事记忆投影，不是第二份事实源。原始事实继续以 `SessionEventLog` 为准，当前场景与待续状态继续由 `ChatContextState` 维护，精确关系事实继续进入 `MemoryStore`，长期人格变化继续由 `CharacterEvolution` 维护。

日记复用现有 post-turn 状态整理请求生成稀疏的 `diaryDelta`，不为普通回合增加额外模型请求。只有具备跨会话价值的经历才允许落盘；条目区分客观事件锚点、角色感受、未说出口的心理活动、关系意义和仍会影响后续的余波。每条落盘日记必须至少包含感受或未说出口的心理活动之一，重大事件也不能退化成只有 event 的事件清单；缺少主观层时由事实/连续性层继续承载客观信息。日记优先使用角色第一人称内在表述，禁止逐句复述和流水账式时间串联，也禁止把角色对用户动机的推测升格为客观事实。

单聊与群聊使用同一稳定角色 `subjectKey` 形成连续的人物经历。群聊状态整理为实际发言角色更新隐藏状态并生成主观日记，同时在同一次模型请求中为在场未发言角色生成只含日记的观察投影；同一公开事件可以形成不同角色视角，且不增加额外模型调用。只有已取得明确公开授权并落为 `PUBLIC` 的单聊经历可在后续群聊召回；群聊公开经历仍可在后续单聊召回。披露边界与“角色是否记得”分离：`PRIVATE` 只允许留在该角色自己的单聊记忆；`SHAREABLE` 仅代表普通单聊经历，也不得进入群聊 Prompt；只有 `PUBLIC` 可以进入群聊。单聊条目只有出现明确公开授权证据时才能升级为 `PUBLIC`，保密证据始终优先并强制保持 `PRIVATE`。禁止以后通过“隐私余波”“态度提示”或其他旁路把 PRIVATE/SHAREABLE 重新注入群聊。

精确事实与人物日记使用不同边界：用户明确陈述并经 MemoryPolicy 落盘的精确事实（关系状态、关系对象、稳定偏好/稳定信息等）允许在单聊和群聊双向召回，仍按当前人物 subjectKey、lineage 与语义门控筛选；群聊不得因为 `groupAudience` 关闭事实召回。人物日记继续单独受披露级别约束，群聊只接受 `PUBLIC`。

召回统一受模型上下文窗口预算约束。长期事实与日记共享有上限的 Chat 长期记忆预算，日记不会全量常驻 Prompt；普通输入只召回语义相关条目，显式“以前/上次/那天”等回忆请求才放宽候选。所有最终注入文本再次按模型 Token 估算硬裁剪。 Chat→Chat 继续会话不再复制旧对话生成叙事 handoff；当前场景、待续和未归并事实由迁移后的 `ChatContextState` 承接，长期经历按需从日记召回。Work 的任务 handoff 保持不变。

日记保存来源会话、用户/角色消息 ID 和 generation。聊天分支编辑、历史重写或回滚时，与被丢弃消息关联的日记同步失效，避免“幽灵记忆”残留。相近经历在短时间内优先精炼已有条目，保留更完整的感受、心理和关系意义，而不是每轮追加重复记录。

### Token usage and observability

`TokenUsageAnalyticsStore` migrates the legacy JSONL ledger transactionally into SQLite. Request insertion, deduplication and lifetime totals commit together; a failed write can be retried with the same request id. Request details retain at most 90 days and 10,000 records (shrinking to 9,000 after overflow), with a 4 KiB per-record bound. Lifetime totals are retained independently. The bounded projection updates incrementally; reopening, retention cleanup and time-zone changes rebuild it from the retained window. Averages, action splits and groups describe that window, while headline aggregates remain lifetime totals. Deduplication covers retained request identities; callers must use a new id for a new request and avoid replaying expired requests. API-reported input/output usage is the total; prompt sections are diagnostic attribution only and are not added again.

`LocalTokenUsageContextBridge` maps internal model-consuming actions such as Web and Vision back to their parent run. 每个成功模型回复同时携带不含密钥的实际 route identity（profile/provider/model/baseUrl/auth/protocol/fingerprint）；Token 账本用它区分同名模型、多账户和代理地址。官方价格只在供应商与官方地址同时匹配时估算，未知或第三方路由保留实际 API usage 并记为未定价。

```text
requestId
→ session / turn
→ run / parentRun
→ agent
→ action
```

This supports daily, session, task, main-agent/subagent and action-level views without double counting.

## Persistence and performance

The local Session event log is the durable fact stream. Snapshots are bounded materializations, not a second full transcript authority.

Important invariants:

- historical reads use paging;
- runtime transcript windows stay bounded;
- model-history writes go through the dedicated buffer;
- tool output is bounded in model context, with recoverable spill storage where required;
- streaming updates do not rebuild aggregate state;
- caches and logs have explicit limits.

CI performance guards reject known hot-path regressions and full-history scans.

## Remote Harness architecture

远程 wire DTO 按协议功能组织：`LlmContent.kt` 承载内容块、流式 chunk 及其原样透传序列化器，`LlmMessages.kt` 承载消息、来源、终态和 usage，`LlmRequests.kt` 承载模型请求配置与工具 schema。会话 payload 分为回合、控制/审批、工作流/子代理、调度和压缩；`Events.kt` 只保留事件 envelope 与统一类型分派。既有 package、类型名、wire 字段及未知类型原样保留契约保持一致。

The remote path stays separate from the local native Agent kernel.

```text
HTTPS relay
→ /api/remote.mux
→ ConnectionManager
→ SessionStore
→ ConversationSnapshot / projections
→ Compose UI
```

`SessionStore` remains the single remote stream/fold owner. The live assistant attempt is presentation data; durable settlement retires it, while reconnect restores partial output from the follow baseline.

The current **remote protocol baseline** is `0.1.6-alpha.1` at
`0d1f50007f9bca3f52b06e1c3074fa14d5fb0720` (`DshCore.PROTOCOL_BASELINE`).

The separate **local semantic reference** is pinned by `upstream/deepseek-harness.lock.json` at
`0.1.7-rc.2 / 477b4f420553e8a52c2fbccc464d7561b239c443`.

Do not confuse the remote wire baseline with the local differential-validation baseline.

## Architecture ratchets

CI treats architectural boundaries as executable constraints, including:

- line-count ratchets for known hotspots;
- zero public methods on `LocalHarnessEngine`;
- bounded Engine constructor dependencies;
- bounded aggregate state;
- allowlists for direct Engine consumers;
- capability-package boundaries;
- streaming, transcript paging and model-history performance invariants.

When a ratchet fails, the fix is to move responsibility to the correct boundary—not to raise the budget.

See [AGENTS.md](../AGENTS.md) for repository-wide engineering and merge rules.

### Pending chat continuity

The request-time pending window retains at most 64 turns with 4,000 characters per message. Full pending facts are persisted as `chat/pending-turn` events before eviction; consolidation reads paged events after the processed cursor and selects the oldest unfinished batch with bounded memory. Direct, proactive and group chat use the same store with separate scopes. Legacy active queues are archived before bounding. Continuations copy unfinished facts in bounded batches into the new session log and assign its sequences; old processed cursors are reset. Imported prefix facts remain available across branches in the new conversation. Branch restoration filters archived facts by active message ids when alternatives exist. A consolidation commit preserves newer pending turns and deterministic scene updates and rejects competing cursor changes.


### 人物生命运行时 V3

Chat 人物运行时采用“稳定人物底色 → 独立生活/记忆/关系状态 → 本轮注意力 → 动态模式 → 最终对话”的单一路径。`PersonaProfile` 只保存少量长期人物资料，不保存固定思维/行为/表达模板；`ChatCharacterState` 保存会话内状态和可持续演变；请求时由 `CharacterRuntimeProjector` 统一投影给单聊、群聊和主动互动，禁止各入口维护平行人物 Prompt。

独立生活由 `CharacterLifeRuntime` 基于 `lifeContext`、当前日程、挂念与未完事项推进。时间推进采用请求时 catch-up：即使用户一段时间没有打开聊天，下一次人物被调用时也会按真实时间推进生活节拍；只允许从既有人物生活资料或已发生事件延展低风险日常状态，禁止凭空生成重大人生事件、关系事实或不可逆变化。生活事件有来源、类型、开始/更新时间和过期边界，并可为主动互动提供自然理由。

人物对用户的主观认识使用持久 `currentUserImpression`。它只在出现新证据时修正，不参与短期 TTL；旧 `recentImpression` 仅作为历史数据兼容镜像。人物注意力由 `CharacterAttentionResolver` 每轮从输入中选择最多两个优先关注点，并结合人物盲点形成软倾向；明确问题、边界和重要事实始终优先。`CharacterBehaviorResolver` 现作为动态模式解析器，只根据真实运行时状态与用户显式调节调整联想、推演、情绪驱动、感官、言外敏感、自由度、主动、自我分享、回应覆盖、压缩、玩心与改口等连续倾向；自然人物描述直接由模型理解，不通过“嘴硬/理性/害羞”等关键词表硬映射到固定话术或动作。模式是概率场，不是候选菜单，同一人物可随话题和状态自然切换脑回路。


长期成长继续保留主动、开放、安全感三个粗粒度基线，同时为 `mutableTraits` 维护独立证据计数、动量、反证和权重。单轮不能改写人格；只有多次真实经历才能缓慢改变可变倾向，稳定特质与硬约束不参与关系热度漂移。关系数值仅作为派生诊断，阶段、共同经历、共同物、真实行为证据优先。

Token 预算在投影层硬限制：稳定人物前缀最多 520 Token，本轮“此刻”最多 230 Token，本轮动态模式最多 330 Token；硬事实、明确边界与用户纠正优先于模式细节保留。长期事实单独封顶 500 Token，人物日记封顶 800 Token。普通闲聊默认不召回日记，轻相关最多 1 条，明确回忆请求最多 3 条。人物日记、生活流与模式投影均复用现有模型回合，不增加独立模型调用。

角色回复最终仍经过已有字面风格过滤与重复守卫，并增加 `CharacterReplyAnomalyGuard`。异常守卫只对绑定人物启用，只做高置信度、最小结构修复（解释式标题、过度罗列、连续重复等）；正常文本不重写，检测到但无法安全自动修复的结构只记录诊断。
