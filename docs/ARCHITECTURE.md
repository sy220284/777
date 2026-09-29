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

High-frequency streaming preview is kept separate from aggregate state, avoiding full state rewrites for token-by-token output.

### Capability runtimes

`LocalUiRuntime` is a dependency-only aggregator. Ownership remains in narrow runtimes such as `LocalChatRuntime`, `LocalWorkRuntime`, `LocalSessionRuntime`, `LocalModelRuntime`, `LocalToolsRuntime` and `LocalAutomationRuntime`.

New UI and worker code should enter through the relevant capability runtime instead of calling `LocalHarnessEngine` directly.

### Orchestration boundaries

`LocalHarnessEngine` owns consistency across a local turn/session. Focused behavior lives in extracted coordinators for model transport, tool execution, Chat preparation/finalization, Session persistence, Agent run recovery, automation, group Chat and sending.

The Engine has CI-enforced line, dependency and public-surface ratchets. New responsibilities must move outward rather than expanding the central orchestration surface.

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

Built-in plugins are described by `PluginDescriptor` and registered in a `PluginCatalog`. `PluginManager` resolves dependency order and minimum versions before lifecycle mutation, prevents disabling providers with active dependents, and keeps descriptor/catalog state synchronized with the live registry. Runtime replacement uses `PluginRegistry.replace`: registry mutations are isolated, failed replacements clean up the candidate and reinstall the previous plugin before restoring the original registry surface.

Startup plugin installation remains atomic. Dynamic MCP disconnect stops new admission, drains in-flight calls, unregisters tools, then closes transport. Downstream Agent code depends on capability contracts rather than Android UI classes. External DEX/JAR loading is intentionally outside this trust boundary until the plugin API is stable; the current hot-swap contract applies to trusted in-process plugin definitions.

### Chat continuity and memory

Chat keeps persona definition, relationship memory, scene continuity, character evolution and user behavior tuning as separate concerns.

Relationship memory uses a stable subject key; Gallery identity wins over copied persona identity. `CharacterBehaviorTuning` changes expression and pacing but cannot rewrite trust, shared events or other historical facts.

### Token usage and observability

`TokenUsageAnalyticsStore` keeps a request-level ledger. API-reported input/output usage is the total; prompt sections are diagnostic attribution only and are not added again.

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
