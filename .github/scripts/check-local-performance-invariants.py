#!/usr/bin/env python3
"""Guard Architecture 3.0 execution and performance invariants.

This guard checks hot-path anti-patterns and durable execution properties. It intentionally avoids
UI styling, prompt wording, file-size budgets, constructor/method counts, and exact implementation
shape. Product behavior belongs to tests; architecture ownership belongs to the structure guard.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
LOCAL_ROOT = ROOT / "app/src/main/java/com/labteto/dshmobile/local"

EVENT_LOG = ROOT / "harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/SessionEventLog.kt"
REPOSITORY = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRepository.kt"
DEEPSEEK = ROOT / "app/src/main/java/com/labteto/dshmobile/local/DeepSeekClient.kt"
CONTEXT_BUDGET = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalContextBudget.kt"
SESSION_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionCoordinator.kt"
SESSION_STORAGE = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt"
SESSION_SNAPSHOT_PROVIDER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalCurrentSessionSnapshotComposition.kt"
SESSION_SNAPSHOT_BOUNDARY = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalCurrentSessionSnapshotProvider.kt"
TRANSCRIPT_RUNTIME = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptRuntime.kt"
WORK_RUN_BINDING = ROOT / "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRunBinding.kt"
MODEL_HISTORY_BUFFER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelHistoryBuffer.kt"
RUNTIME_DEFAULTS = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessDefaults.kt"
TRANSCRIPT_HISTORY_LOADER = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalTranscriptHistoryLoader.kt"
MODEL_REQUEST_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt"
RUNTIME_STATE_STORE = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt"
TOOL_EXECUTION_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt"
AGENT_RUN_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt"
SESSION_RUNTIME_REGISTRY = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionRuntimeRegistry.kt"
AUTOMATION_RUNTIME = ROOT / "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt"
CHAT_AUTOMATION_PORT = ROOT / "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatAutomationExecutionPort.kt"
WORK_AUTOMATION_PORT = ROOT / "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkAutomationExecutionPort.kt"
AUTOMATION_WORKER = ROOT / "app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomationWorker.kt"
EXECUTION_STATUS = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalExecutionStatus.kt"

violations: list[str] = []


def read(path: Path) -> str:
    if not path.exists():
        violations.append(f"required execution-invariant source is missing: {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//.*$", "", source, flags=re.MULTILINE)


def kotlin_sources_under(relative_dir: str) -> dict[str, str]:
    base = ROOT / relative_dir
    if not base.exists():
        return {}
    return {
        path.relative_to(ROOT).as_posix(): strip_comments(path.read_text(encoding="utf-8"))
        for path in base.rglob("*.kt")
    }


def merge_sources(*groups: dict[str, str]) -> dict[str, str]:
    merged: dict[str, str] = {}
    for group in groups:
        merged.update(group)
    return merged


def contains_any(sources: dict[str, str], token: str) -> bool:
    return any(token in source for source in sources.values())


def paths_containing(sources: dict[str, str], token: str) -> list[str]:
    return sorted(path for path, source in sources.items() if token in source)


def ordered_in_source(source: str, *tokens: str) -> bool:
    positions = [source.find(token) for token in tokens]
    return min(positions) >= 0 and positions == sorted(positions)


event_log = strip_comments(read(EVENT_LOG))
repository = strip_comments(read(REPOSITORY))
deepseek = strip_comments(read(DEEPSEEK))
context_budget = strip_comments(read(CONTEXT_BUDGET))
session_coordinator = strip_comments(read(SESSION_COORDINATOR))
session_storage = strip_comments(read(SESSION_STORAGE))
session_snapshot_provider = strip_comments(read(SESSION_SNAPSHOT_PROVIDER))
session_snapshot_boundary = strip_comments(read(SESSION_SNAPSHOT_BOUNDARY))
transcript_runtime = strip_comments(read(TRANSCRIPT_RUNTIME))
work_run_binding = strip_comments(read(WORK_RUN_BINDING))
model_history_buffer = strip_comments(read(MODEL_HISTORY_BUFFER))
runtime_defaults = strip_comments(read(RUNTIME_DEFAULTS))
transcript_history_loader = strip_comments(read(TRANSCRIPT_HISTORY_LOADER))
model_request_coordinator = strip_comments(read(MODEL_REQUEST_COORDINATOR))
runtime_state_store = strip_comments(read(RUNTIME_STATE_STORE))
tool_execution_coordinator = strip_comments(read(TOOL_EXECUTION_COORDINATOR))
agent_run_coordinator = strip_comments(read(AGENT_RUN_COORDINATOR))
session_runtime_registry = strip_comments(read(SESSION_RUNTIME_REGISTRY))
automation_runtime = strip_comments(read(AUTOMATION_RUNTIME))
chat_automation_port = strip_comments(read(CHAT_AUTOMATION_PORT))
work_automation_port = strip_comments(read(WORK_AUTOMATION_PORT))
automation_worker = strip_comments(read(AUTOMATION_WORKER))
execution_status = strip_comments(read(EXECUTION_STATUS))

chat_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/chat")
work_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/work")
session_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/session")
automation_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/automation")
runtime_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/runtime")
all_local_sources = kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local")

root_execution_sources = {}
for relative in (
    "app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt",
    "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt",
):
    path = ROOT / relative
    if path.exists():
        root_execution_sources[relative] = strip_comments(path.read_text(encoding="utf-8"))

foreground_sources = merge_sources(chat_sources, work_sources, root_execution_sources)
recovery_sources = merge_sources(work_sources, session_sources, runtime_sources, root_execution_sources)

# Historical Chat data may still be decoded at compatibility/persistence boundaries. Active
# execution must use the current Chat context projection during a live turn.
active_chat_context_sources = {
    path: source
    for path, source in merge_sources(chat_sources, root_execution_sources).items()
    if path.endswith((
        "LocalChatDirectTurnExecutor.kt",
        "LocalChatContextRefreshCoordinator.kt",
        "LocalGroupChatTurnExecutor.kt",
        "LocalChatTurnCoordinator.kt",
        "LocalChatReplyCoordinator.kt",
    ))
}
active_chat_context_sources.update({
    path: source
    for path, source in automation_sources.items()
    if path.endswith("LocalAutomationChatCoordinator.kt")
})

hot_sources = merge_sources(
    foreground_sources,
    session_sources,
    runtime_sources,
)


# ---- Bounded streaming and hot-state work ---------------------------------

def numeric_constant(name: str) -> int | None:
    match = re.search(rf"\bconst\s+val\s+{re.escape(name)}\s*=\s*([0-9_]+)(?:L)?", runtime_defaults)
    return int(match.group(1).replace("_", "")) if match else None


preview_chars = numeric_constant("MAX_STREAM_PREVIEW_CHARS")
if preview_chars is None or preview_chars > 8_192:
    violations.append(
        f"MAX_STREAM_PREVIEW_CHARS must remain bounded at <= 8192, got {preview_chars}"
    )

preview_interval = numeric_constant("STREAM_PREVIEW_INTERVAL_MS")
if preview_interval is None or not 16 <= preview_interval <= 250:
    violations.append(
        f"STREAM_PREVIEW_INTERVAL_MS must remain screen-friendly (16..250 ms), got {preview_interval}"
    )

for forbidden_hot_pattern in (
    "modelHistory.sumOf",
    "state.streamingAssistant + delta.content",
    "_state.update { it.copy(streamingAssistant",
    "state.messages.mapTo(hashSetOf()",
    "state.messages.maxOfOrNull(LocalHarnessMessage::createdAt)",
    'title = state.messages.firstOrNull { it.role == "user" }',
    "postTurnSnapshot.messages.lastOrNull",
):
    hits = paths_containing(hot_sources, forbidden_hot_pattern)
    if hits:
        violations.append(
            f"hot execution surface contains forbidden unbounded pattern {forbidden_hot_pattern!r}: " + ", ".join(hits)
        )

# Model-history accounting has one owner. No extracted execution surface may mutate the raw list.
for forbidden_mutation in (
    "modelHistory +=",
    "modelHistory.add(",
    "modelHistory.clear()",
):
    hits = paths_containing(hot_sources, forbidden_mutation)
    if hits:
        violations.append(
            f"execution surface bypassed LocalModelHistoryBuffer via {forbidden_mutation!r}: "
            + ", ".join(hits)
        )
if re.search(r"\bmodelHistory\s*\[[^\]]+\]\s*=", "\n".join(hot_sources.values())):
    violations.append("execution surface mutates raw modelHistory entries outside LocalModelHistoryBuffer")

for cached_metric in ("encodedChars", "estimatedTokens"):
    if re.search(rf"\bvar\s+{cached_metric}\s*:\s*Int", model_history_buffer) is None:
        violations.append("LocalModelHistoryBuffer lost cached metric: " + cached_metric)


# ---- Bounded durable history ----------------------------------------------

for required_event_api in ("pageBeforeChronological", "pageBeforeNewestFirst", "pageAfter", "latestMatching", "forEachAfter"):
    if re.search(rf"\bfun\s+{required_event_api}\s*\(", event_log) is None:
        violations.append("SessionEventLog lost bounded/recent history API: " + required_event_api)

if "val events = snapshot()" in event_log:
    violations.append("SessionEventLog.read must not materialize the complete event archive")
if "orderedFilesUnsafe().asReversed()" not in event_log:
    violations.append("SessionEventLog newest-first paths lost reverse segment traversal")
if "RandomAccessFile(source, \"r\")" not in event_log:
    violations.append("SessionEventLog restart/tail recovery lost bounded reverse file access")

for token in (
    "LOCAL_TRANSCRIPT_HISTORY_MAX_PAGES_PER_LOAD",
    "LOCAL_TRANSCRIPT_HISTORY_MAX_RAW_MESSAGES_PER_LOAD",
):
    if token not in transcript_history_loader:
        violations.append("foreground transcript loading lost total-call budget: " + token)

# Full-history events() is forbidden in foreground/recovery hot paths.
for path, source in merge_sources(chat_sources, work_sources).items():
    if ".events()" in source:
        violations.append(f"{path} scans the full Session event archive on a Feature hot path")

def check_full_history_reference(source_map: dict[str, str], relative: str, token: str) -> str | None:
    """A missing audit target must fail closed instead of silently scanning an empty string."""
    source = source_map.get(relative)
    if source is None:
        return f"missing required full-history audit target: {relative}"
    if token in source:
        return f"{relative} uses full-history events() for recent attribution"
    return None


for relative, token, owner_sources in (
    ("app/src/main/java/com/labteto/dshmobile/local/work/LocalSubagentRunner.kt", "eventLog().events()", work_sources),
    ("app/src/main/java/com/labteto/dshmobile/local/TokenUsageAnalytics.kt", "eventLog.events()", all_local_sources),
):
    violation = check_full_history_reference(owner_sources, relative, token)
    if violation:
        violations.append(violation)

if (
    "fun appendMessages(messages: List<LocalHarnessMessage>, runtimeWindowMessages: Int)" not in transcript_runtime
    or "state.appendMessages(messages, runtimeWindowMessages)" not in transcript_runtime
    or "(current.messages + messages).takeLast(runtimeWindowMessages)" not in work_run_binding
):
    violations.append(
        "Transcript projection must preserve a bounded visible window through its narrow state port"
    )

if (
    "summaryCache" not in repository
    or "LocalSessionSummaryIndex" not in repository
):
    violations.append("LocalSessionRepository lost lightweight summary caching/indexing")


# ---- Session persistence and recovery ordering ----------------------------

if (
    "messages = emptyList()" not in session_snapshot_provider
    or "transcriptWindow = state.messages.takeLast(runtimeWindowMessages)" not in session_snapshot_provider
):
    violations.append("Session snapshots must keep full transcript out of the snapshot payload")

if "controlProjectedThroughSequence = eventLog.latestSequence()" not in session_snapshot_boundary:
    violations.append("Session snapshot boundary lost durable control cursor capture")
if not ordered_in_source(
    session_storage,
    "val boundary = localSessionSnapshotBoundary(",
    "return currentSnapshotProvider.snapshot(",
):
    violations.append(
        "Session persistence must capture durable projection cursors before materializing mutable Feature state"
    )

agent_inbox_persistence_path = (
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalAgentInboxPersistence.kt"
)
agent_inbox_persistence = all_local_sources.get(agent_inbox_persistence_path, "")
if 'LOCAL_AGENT_INBOX_EVENT_TYPE = "agent/inbox/spliced"' not in agent_inbox_persistence:
    violations.append("LocalAgentInboxPersistence must own the canonical durable inbox event type")

for path, source in all_local_sources.items():
    if path != agent_inbox_persistence_path and '"agent/inbox/spliced"' in source:
        violations.append(
            f"{path} hard-codes the durable Agent inbox event type; use LOCAL_AGENT_INBOX_EVENT_TYPE"
        )
    if "eventLog.append(LOCAL_AGENT_INBOX_EVENT_TYPE" in source and "encodeLocalAgentInboxEvent(" not in source:
        violations.append(
            f"{path} writes Agent inbox state without encodeLocalAgentInboxEvent"
        )

# Recovery must reject stale ownership before late foreground Work commits.
if not contains_any(work_sources, "agentRunCoordinator.ensureCurrentOwner("):
    violations.append("Work execution lost the durable late-commit ownership fence")


# ---- Runtime ownership, route freeze, and tool execution ------------------

resource_scheduler_owners = paths_containing(all_local_sources, "HarnessResourceScheduler(")
expected_scheduler_owner = [
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalRuntimeStateStore.kt"
]
if resource_scheduler_owners != expected_scheduler_owner:
    violations.append(
        "HarnessResourceScheduler must have exactly one Runtime owner; found: "
        + ", ".join(resource_scheduler_owners)
    )
for required_resource_contract in (
    "internal val resourceScheduler = HarnessResourceScheduler(",
    "internal suspend fun <T> withModelRequestResource(",
    "HarnessResourceKind.MODEL_REQUEST",
    "resourceScheduler.withResource(",
):
    if required_resource_contract not in runtime_state_store:
        violations.append(
            "LocalRuntimeStateStore lost shared resource ownership: " + required_resource_contract
        )
if "runtimeStateStore.withModelRequestResource(block)" not in automation_runtime:
    violations.append(
        "Automation model requests must acquire MODEL_REQUEST through shared Runtime ownership"
    )

for terminal_status in ("DELIVERED", "SKIPPED", "BLOCKED", "CANCELLED", "FAILED"):
    if terminal_status not in execution_status:
        violations.append("shared execution status lost terminal state: " + terminal_status)

for port_name, source in (
    ("Chat Automation execution Port", chat_automation_port),
    ("Work Automation execution Port", work_automation_port),
):
    if "val status:" not in source:
        violations.append(f"{port_name} must return a structured status instead of exception-only outcome")
    if "LocalExecutionStatus" not in source:
        violations.append(f"{port_name} must use the shared execution terminal status contract")

for terminal_status in ("DELIVERED", "SKIPPED", "BLOCKED", "CANCELLED", "FAILED"):
    if f"LocalAutomationRunStatus.{terminal_status}" not in automation_worker:
        violations.append(
            "Automation Worker lost explicit settlement for terminal status: " + terminal_status
        )

tracked_tool_execution_owners = paths_containing(all_local_sources, ".executeTracked(")
expected_tool_execution_owner = [
    "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt"
]
if tracked_tool_execution_owners != expected_tool_execution_owner:
    violations.append(
        "tracked ToolRegistry execution must have one shared policy owner; found: "
        + ", ".join(tracked_tool_execution_owners)
    )
for required_tool_boundary in (
    "LocalToolPolicy.canonical(",
    "planModeEnabled",
    "allowMutation",
    "ToolContext(",
    "registry.executeTracked(",
):
    if required_tool_boundary not in tool_execution_coordinator:
        violations.append(
            "LocalToolExecutionCoordinator lost tool policy/side-effect boundary: "
            + required_tool_boundary
        )

if model_request_coordinator.count("modelGateway.profileForRoute(") != 1:
    violations.append(
        "one model request must resolve the fallback route exactly once before retries/recovery"
    )
if not ordered_in_source(
    model_request_coordinator,
    "val frozenProfile = profile ?: modelGateway.profileForRoute(",
    "val runSurface = frozenProfile.toRunModelSurface()",
    "modelStepRuntime.recover(",
):
    violations.append(
        "model route/profile must freeze before request recovery and retry orchestration"
    )
if re.search(
    r"requestRuntime\.complete\s*\(\s*surface\s*=\s*runSurface\b",
    model_request_coordinator,
    re.DOTALL,
) is None:
    violations.append(
        "provider invocation must use the frozen runSurface instead of re-reading mutable model settings"
    )

for required_agent_owner_fact in (
    "private val foregroundOwners = ConcurrentHashMap<String, String>()",
    "fun isCurrentOwner(context: LocalAgentRunContext)",
    "fun ensureCurrentOwner(context: LocalAgentRunContext)",
):
    if required_agent_owner_fact not in agent_run_coordinator:
        violations.append(
            "LocalAgentRunCoordinator lost run-identity ownership fact: " + required_agent_owner_fact
        )

for required_session_owner_api in (
    "class LocalSessionRuntimeLease",
    "suspend fun <T> withOwner(",
    "fun tryAcquire(",
    "suspend fun acquire(",
    "suspend fun acquireAll(",
):
    if required_session_owner_api not in session_runtime_registry:
        violations.append(
            "LocalSessionRuntimeRegistry lost process-wide session ownership API: "
            + required_session_owner_api
        )
if ".distinct().sorted().forEach { sessionId ->" not in session_runtime_registry:
    violations.append(
        "multi-session ownership must acquire leases in stable order to avoid cross-session deadlock"
    )


# ---- Protocol/runtime anti-bypass rules -----------------------------------

if "parse(synthetic.toString())" in deepseek:
    violations.append("DeepSeek streaming rebuilt a synthetic full response instead of incremental parsing")

if "usageMode: LocalUsageMode" in context_budget or "DEFAULT_CHAT_TOOL_RESULT_TOKENS" in context_budget:
    violations.append("context/tool-result budget forked by product mode instead of using one shared capability")

for forbidden_fallback in (
    '"to", "vision-tool"',
    '"multimodal/fallback"',
):
    hits = paths_containing(foreground_sources, forbidden_fallback)
    if hits:
        violations.append(
            "foreground execution must use the unified frozen-profile Vision route; "
            "parallel fallback found in: " + ", ".join(hits)
        )

# Compatibility Chat-context decoding stays outside active/proactive execution paths.
for path, source in active_chat_context_sources.items():
    if "withLegacyFallback" in source:
        violations.append(
            f"{path} uses compatibility Chat context fallback inside active execution"
        )

# Mutation-style regression checks: both historical bypasses must fail closed.
if "--self-test" in sys.argv:
    target = "app/src/main/java/com/labteto/dshmobile/local/work/LocalSubagentRunner.kt"
    assert check_full_history_reference({}, target, "eventLog().events()") is not None
    assert check_full_history_reference({target: "eventLog().events()"}, target, "eventLog().events()") is not None
    assert check_full_history_reference({target: "eventLog().latestMatching()"}, target, "eventLog().events()") is None
    print("[architecture-3] execution guard self-test passed")
    sys.exit(0)

if violations:
    print("Architecture 3.0 execution invariant guard failed:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    sys.exit(1)

print("[architecture-3] OK: execution/performance invariants hold")
