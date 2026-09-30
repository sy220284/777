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

本机模型层把“账户凭据”和“模型协议”分开处理：

```text
Chat / Work / Agent
        │
        ▼
LocalModelGateway
   ├─ LocalModelCredentialResolver
   │    ├─ API Key
   │    └─ ChatGPT OAuth plan
   └─ transport
        ├─ Chat Completions
        └─ OpenAI Responses
```

API Key 档案继续兼容既有 `model + baseUrl` 标识；ChatGPT 套餐档案额外绑定认证类型和账户身份，避免同一 OpenAI 模型在 API Key 与套餐登录之间覆盖凭据。ChatGPT 登录后的模型目录以 OpenAI 返回的可见模型为准，不把套餐模型永久写死在客户端预设中。

ChatGPT 套餐请求通过 Responses API，客户端保留完整 continuation items，因为套餐共享请求使用 `store=false`。系统提示转换为 Responses `instructions`，工具调用转换回本机 `LocalToolCall`，因此上层 Agent loop 不依赖具体传输协议。

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

### Token usage and observability

`TokenUsageAnalyticsStore` migrates the legacy JSONL ledger transactionally into SQLite. Request insertion, deduplication and lifetime totals commit together; a failed write can be retried with the same request id. Request details retain at most 90 days and 10,000 records (shrinking to 9,000 after overflow), with a 4 KiB per-record bound. Lifetime totals are retained independently. The bounded projection updates incrementally; reopening, retention cleanup and time-zone changes rebuild it from the retained window. Averages, action splits and groups describe that window, while headline aggregates remain lifetime totals. Deduplication covers retained request identities; callers must use a new id for a new request and avoid replaying expired requests. API-reported input/output usage is the total; prompt sections are diagnostic attribution only and are not added again.

`LocalTokenUsageContextBridge` maps internal model-consuming actions such as Web and Vision back to their parent run.

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
