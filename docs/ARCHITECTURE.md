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

### Tool and plugin composition

Platform-specific providers are built by `LocalPluginCompositionFactory` / `LocalPluginComposition`, not by the Engine.

Built-in plugins are described by `PluginDescriptor` and registered in a `PluginCatalog`. `PluginManager` resolves dependency order and minimum versions before lifecycle mutation, prevents disabling providers with active dependents, and keeps descriptor/catalog state synchronized with the live registry. Runtime replacement uses `PluginRegistry.replace`: registry mutations are staged and published together after admitted tool calls drain (up to 30 seconds). Failed replacements clean up the candidate and reinstall the previous plugin, publishing the newly created resources instead of old closed references. If resource restoration or cleanup fails, tools fail closed until the failed plugin is successfully disabled. UI management resolves the active instance under the lifecycle mutation lock. The Android composition rejects hot replacement or disabling of runtime/device providers that are also retained by long-lived owners; changing these providers requires restarting the runtime.

Startup plugin installation remains atomic. Dynamic MCP disconnect stops new admission, drains in-flight calls, unregisters tools, then closes transport. Downstream Agent code depends on capability contracts rather than Android UI classes. External DEX/JAR loading is intentionally outside this trust boundary until the plugin API is stable; the current hot-swap contract applies to trusted in-process plugin definitions.

### Chat continuity and memory

Chat keeps persona definition, relationship memory, scene continuity, character evolution and user behavior tuning as separate concerns.

Relationship memory uses a stable subject key; Gallery identity wins over copied persona identity. `CharacterBehaviorTuning` changes expression and pacing but cannot rewrite trust, shared events or other historical facts.

### Character diary and cross-chat memory

角色日记是 Chat 的长期叙事记忆投影，不是第二份事实源。原始事实继续以 `SessionEventLog` 为准，当前场景与待续状态继续由 `ChatContextState` 维护，精确关系事实继续进入 `MemoryStore`，长期人格变化继续由 `CharacterEvolution` 维护。

日记复用现有 post-turn 状态整理请求生成稀疏的 `diaryDelta`，不为普通回合增加额外模型请求。只有具备跨会话价值的经历才允许落盘；条目区分客观事件锚点、角色感受、未说出口的心理活动、关系意义和仍会影响后续的余波。日记禁止逐句复述和流水账式时间串联，也禁止把角色对用户动机的推测升格为客观事实。

单聊与群聊使用同一稳定角色 `subjectKey` 形成连续的人物经历。群聊状态整理为实际发言角色更新隐藏状态并生成主观日记，同时在同一次模型请求中为在场未发言角色生成只含日记的观察投影；同一公开事件可以形成不同角色视角，且不增加额外模型调用。单聊经历可在后续群聊召回，群聊经历也可在后续单聊召回。披露边界与“角色是否记得”分离：`PRIVATE` 日记仅能进入该角色的私密上下文，`SHAREABLE` 可在该角色参与的群聊中使用，群聊公开经历记为 `PUBLIC`。

召回统一受模型上下文窗口预算约束。长期事实与日记共享有上限的 Chat 长期记忆预算，日记不会全量常驻 Prompt；普通输入只召回语义相关条目，显式“以前/上次/那天”等回忆请求才放宽候选。所有最终注入文本再次按模型 Token 估算硬裁剪。

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
