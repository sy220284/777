#!/usr/bin/env python3
"""Guard local Harness performance invariants that are easy to regress in code review."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
LOCAL_CONVERSATION_SURFACE = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationSurface.kt"
LOCAL_CONVERSATION_COMPOSER = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationComposer.kt"
LOCAL_CONVERSATION_COMPOSER_ACTIONS = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalConversationComposerActions.kt"
REMOTE_COMPOSER = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/main/Composer.kt"
SHARED_COMPOSER = ROOT / "app/src/main/java/com/labteto/dshmobile/ui/components/DsConversationComposer.kt"
LIFECYCLE_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt"
EVENT_LOG = ROOT / "harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/SessionEventLog.kt"
REPOSITORY = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSessionRepository.kt"
DEEPSEEK = ROOT / "app/src/main/java/com/labteto/dshmobile/local/DeepSeekClient.kt"
WEB_PROVIDER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalWebProvider.kt"
WEB_DIAGNOSTICS = ROOT / "app/src/main/java/com/labteto/dshmobile/local/web/LocalWebDiagnostics.kt"
CONTEXT_BUDGET = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalContextBudget.kt"
COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSessionCoordinator.kt"
RUN_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalAgentRunCoordinator.kt"
MODEL_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt"
MODEL_HISTORY_BUFFER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelHistoryBuffer.kt"
ENGINE_DEFAULTS = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessDefaults.kt"
TRANSCRIPT_RUNTIME = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptRuntime.kt"
SESSION_PERSISTENCE_PROJECTION = ROOT / "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionPersistenceProjection.kt"
SESSION_STORAGE_RUNTIME = ROOT / "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt"
AUTOMATION_CHAT = ROOT / "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt"
PROMPT_CONTEXT = ROOT / "app/src/main/java/com/labteto/dshmobile/local/model/LocalPromptContext.kt"
TOOL_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt"
CHAT_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatTurnCoordinator.kt"
CHAT_REPLY_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatReplyCoordinator.kt"
CHAT_HISTORY_WINDOW = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatHistoryWindow.kt"
CHAT_EDIT_SUPPORT = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatEditSupport.kt"
CHAT_CONTEXT_REFRESH = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatContextRefreshCoordinator.kt"
GROUP_CHAT_EXECUTOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupChatTurnExecutor.kt"
SUBAGENT_RUNNER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt"
TOKEN_USAGE_ANALYTICS = ROOT / "app/src/main/java/com/labteto/dshmobile/local/TokenUsageAnalytics.kt"
CLEANUP_WORKFLOW = ROOT / ".github/workflows/cleanup-old-releases.yml"

violations: list[str] = []

engine = ENGINE.read_text(encoding="utf-8")

local_conversation_surface = LOCAL_CONVERSATION_SURFACE.read_text(encoding="utf-8")
local_conversation_composer = LOCAL_CONVERSATION_COMPOSER.read_text(encoding="utf-8")
local_conversation_composer_actions = LOCAL_CONVERSATION_COMPOSER_ACTIONS.read_text(encoding="utf-8")
remote_composer = REMOTE_COMPOSER.read_text(encoding="utf-8")
shared_composer = SHARED_COMPOSER.read_text(encoding="utf-8")

if "DsConversationComposer(" not in local_conversation_composer:
    violations.append("LocalConversationComposer.kt must use the shared DsConversationComposer shell")
if "var focused" not in local_conversation_composer or "val expanded =" not in local_conversation_composer:
    violations.append("LocalConversationComposer.kt must preserve focus-driven two-row composer expansion")
if "var focused by remember(state.sessionId)" in local_conversation_composer:
    violations.append("LocalConversationComposer focus must not reset on session changes while IME remains visible")
if "DsAnimations.composerReveal" not in local_conversation_composer or "animateSize = false" not in local_conversation_composer:
    violations.append("LocalConversationComposer must use targeted row reveal without nested shell size animation")
if "shape = DsShapes.composer" in local_conversation_composer:
    violations.append("LocalConversationComposer.kt must not rebuild composer geometry outside DsConversationComposer")

if "DsConversationComposer(" not in remote_composer:
    violations.append("Composer.kt must use the shared DsConversationComposer shell")
if "composerFocused" not in remote_composer or "composerExpanded" not in remote_composer:
    violations.append("Composer.kt must preserve focus-driven two-row composer expansion")
if "shape = DsShapes.composer" in remote_composer:
    violations.append("Composer.kt must not rebuild composer geometry outside DsConversationComposer")

if "object DsComposerMetrics" not in shared_composer or "fun DsComposerAction(" not in shared_composer:
    violations.append("Shared composer must own compact action geometry and sizing tokens")
if "LocalConversationComposerExpandedRow(" not in local_conversation_composer:
    violations.append("LocalConversationComposer must delegate expanded actions to its focused component")
if "icon = Icons.Outlined.ListAlt" not in local_conversation_composer_actions or "icon = Icons.Outlined.VerifiedUser" not in local_conversation_composer_actions:
    violations.append("Work composer must keep clear planning and auto-approve actions in the expanded row")
mode_pill = (ROOT / "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalUsageModePill.kt").read_text(encoding="utf-8")
if "graphicsLayer { translationX = indicatorOffsetPx }" not in mode_pill:
    violations.append("Usage-mode indicator animation must stay on the render layer instead of relayout on every frame")
if "val result = onSend(input, attachments.toList())" not in local_conversation_composer or "if (!result.accepted) return" not in local_conversation_composer:
    violations.append("Local Chat/Work composer must preserve the draft until runtime accepts the send")

event_log = EVENT_LOG.read_text(encoding="utf-8")
repository = REPOSITORY.read_text(encoding="utf-8")
deepseek = DEEPSEEK.read_text(encoding="utf-8")
if "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION" not in deepseek or "sawTerminalFrame" not in deepseek:
    violations.append("Model streaming must reject truncated SSE responses after admission without blind whole-request replay")
web_provider = WEB_PROVIDER.read_text(encoding="utf-8")
web_diagnostics = WEB_DIAGNOSTICS.read_text(encoding="utf-8")
context_budget = CONTEXT_BUDGET.read_text(encoding="utf-8")
coordinator = COORDINATOR.read_text(encoding="utf-8")
run_coordinator = RUN_COORDINATOR.read_text(encoding="utf-8")
model_coordinator = MODEL_COORDINATOR.read_text(encoding="utf-8")
model_history_buffer = MODEL_HISTORY_BUFFER.read_text(encoding="utf-8")
engine_defaults = ENGINE_DEFAULTS.read_text(encoding="utf-8")
session_persistence_projection = SESSION_PERSISTENCE_PROJECTION.read_text(encoding="utf-8")
session_storage_runtime = SESSION_STORAGE_RUNTIME.read_text(encoding="utf-8")
transcript_runtime = TRANSCRIPT_RUNTIME.read_text(encoding="utf-8")
automation_chat = AUTOMATION_CHAT.read_text(encoding="utf-8")
prompt_context = PROMPT_CONTEXT.read_text(encoding="utf-8")
tool_coordinator = TOOL_COORDINATOR.read_text(encoding="utf-8")
chat_coordinator = CHAT_COORDINATOR.read_text(encoding="utf-8")
chat_reply_coordinator = CHAT_REPLY_COORDINATOR.read_text(encoding="utf-8")
chat_history_window = CHAT_HISTORY_WINDOW.read_text(encoding="utf-8")
chat_edit_support = CHAT_EDIT_SUPPORT.read_text(encoding="utf-8")
chat_context_refresh = CHAT_CONTEXT_REFRESH.read_text(encoding="utf-8")
group_chat_executor = GROUP_CHAT_EXECUTOR.read_text(encoding="utf-8")
subagent_runner = SUBAGENT_RUNNER.read_text(encoding="utf-8")
token_usage_analytics = TOKEN_USAGE_ANALYTICS.read_text(encoding="utf-8")
lifecycle_coordinator = LIFECYCLE_COORDINATOR.read_text(encoding="utf-8")
cleanup_workflow = CLEANUP_WORKFLOW.read_text(encoding="utf-8")


def kotlin_sources_under(relative_dir: str) -> dict[str, str]:
    base = ROOT / relative_dir
    return {
        path.relative_to(ROOT).as_posix(): path.read_text(encoding="utf-8")
        for path in base.rglob("*.kt")
    }


def merge_sources(*groups: dict[str, str]) -> dict[str, str]:
    merged: dict[str, str] = {}
    for group in groups:
        merged.update(group)
    return merged


def contains_any(sources: dict[str, str], token: str) -> bool:
    return any(token in source for source in sources.values())


def ordered_in_any(sources: dict[str, str], *tokens: str) -> bool:
    for source in sources.values():
        positions = [source.find(token) for token in tokens]
        if min(positions) >= 0 and positions == sorted(positions):
            return True
    return False


def count_in_sources(sources: dict[str, str], token: str) -> int:
    return sum(source.count(token) for source in sources.values())


engine_source = {
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt": engine,
}
chat_execution_sources = merge_sources(
    engine_source,
    {
        "app/src/main/java/com/labteto/dshmobile/local/LocalChatTurnCoordinator.kt": chat_coordinator,
        "app/src/main/java/com/labteto/dshmobile/local/LocalChatReplyCoordinator.kt": chat_reply_coordinator,
        "app/src/main/java/com/labteto/dshmobile/local/LocalChatHistoryWindow.kt": chat_history_window,
        "app/src/main/java/com/labteto/dshmobile/local/LocalChatEditSupport.kt": chat_edit_support,
        "app/src/main/java/com/labteto/dshmobile/local/LocalChatContextRefreshCoordinator.kt": chat_context_refresh,
    },
    kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/chat"),
)
work_execution_sources = merge_sources(
    engine_source,
    {
        "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt": subagent_runner,
        "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt": tool_coordinator,
    },
    kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/work"),
)
session_execution_sources = merge_sources(
    engine_source,
    {
        "app/src/main/java/com/labteto/dshmobile/local/LocalSessionLifecycleCoordinator.kt": lifecycle_coordinator,
        "app/src/main/java/com/labteto/dshmobile/local/LocalSessionCoordinator.kt": coordinator,
        "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalSessionStorageRuntime.kt": session_storage_runtime,
    },
    kotlin_sources_under("app/src/main/java/com/labteto/dshmobile/local/session"),
)
foreground_execution_sources = merge_sources(chat_execution_sources, work_execution_sources)
run_recovery_sources = merge_sources(work_execution_sources, session_execution_sources)
session_snapshot_call_sources = {
    path: source
    for path, source in session_execution_sources.items()
    if not path.endswith("LocalSessionPersistenceProjection.kt")
}

for name, source in (
    ("LocalChatTurnCoordinator.kt", chat_coordinator),
    ("LocalChatReplyCoordinator.kt", chat_reply_coordinator),
    ("LocalChatHistoryWindow.kt", chat_history_window),
    ("LocalChatContextRefreshCoordinator.kt", chat_context_refresh),
    ("LocalGroupChatTurnExecutor.kt", group_chat_executor),
):
    if "withLegacyFallback" in source:
        violations.append(
            f"{name} must use ChatContextState as the runtime scene/continuity source; "
            "legacy fallback is allowed only at persistence migration boundaries"
        )

for forbidden_runtime_fallback in (
    ".withLegacyFallback(snapshot.chat.chatState)",
    ".withLegacyFallback(beforeAssistant.chat.chatState)",
    ".withLegacyFallback(nextChatState)",
):
    if forbidden_runtime_fallback in engine:
        violations.append(
            f"LocalHarnessEngine reintroduced runtime legacy chat-context fallback: {forbidden_runtime_fallback}"
        )

if "withLegacyFallback" in engine:
    violations.append("LocalHarnessEngine must migrate legacy chat context at the session read boundary")
if "withLegacyFallback" in automation_chat:
    violations.append("Automation Chat must consume canonical ChatContextState from LocalSessionCoordinator")

if "release:" not in cleanup_workflow or "types: [published]" not in cleanup_workflow:
    violations.append("Release cleanup must stay event-driven from published releases")
if "cron: '23 3 * * *'" not in cleanup_workflow or "*/30 * * * *" in cleanup_workflow:
    violations.append("Release cleanup fallback must stay daily rather than every 30 minutes")
if "keep = formal_releases[:3]" not in cleanup_workflow or "remove = formal_releases[3:]" not in cleanup_workflow:
    violations.append("Release retention must keep exactly the three highest formal published versions")
if "timedelta(" in cleanup_workflow or "cutoff =" in cleanup_workflow:
    violations.append("Release retention must not add an age-based retention window")

def constant(name: str) -> int | None:
    source = engine + "\n" + engine_defaults
    match = re.search(rf"const val {re.escape(name)}\s*=\s*([0-9_]+)(?:L)?", source)
    return int(match.group(1).replace("_", "")) if match else None

preview_chars = constant("MAX_STREAM_PREVIEW_CHARS")
if preview_chars is None or preview_chars > 8_192:
    violations.append(
        f"MAX_STREAM_PREVIEW_CHARS must stay bounded at <= 8192, got {preview_chars}"
    )

preview_interval = constant("STREAM_PREVIEW_INTERVAL_MS")
if preview_interval is None or not 16 <= preview_interval <= 250:
    violations.append(
        f"STREAM_PREVIEW_INTERVAL_MS must stay screen-friendly (16..250 ms), got {preview_interval}"
    )

for forbidden in (
    "modelHistory.sumOf",
    "state.streamingAssistant + delta.content",
    "_state.update { it.copy(streamingAssistant",
    "state.messages.mapTo(hashSetOf()",
    "state.messages.maxOfOrNull(LocalHarnessMessage::createdAt)",
    'title = state.messages.firstOrNull { it.role == "user" }',
    "val latestDialogueId = current.messages.lastOrNull",
    'checkpointModelHistory("assistant/message")',
    'checkpointModelHistory("tool/result")',
    'checkpointModelHistory("user/message")',
    'checkpointModelHistory("user/queue-consumed")',
    "val directChat =",
    "CHAT_MODE_TOOLS",
    "postTurnSnapshot.messages.lastOrNull",
):
    if forbidden in engine:
        violations.append(f"LocalHarnessEngine.kt reintroduced hot-path pattern: {forbidden}")

# Engine must never mutate the underlying model-history collection directly. The dedicated
# buffer owns every write together with its cached character/token accounting.
for forbidden_mutation in (
    "modelHistory +=",
    "modelHistory.add(",
    "modelHistory[0] =",
    "modelHistory.clear()",
):
    if forbidden_mutation in engine:
        violations.append(
            f"LocalHarnessEngine bypassed LocalModelHistoryBuffer: {forbidden_mutation}"
        )

expected_buffer_counts = {
    "messages +=": 2,      # append + reset
    "messages.add(": 1,    # prepend
    "messages[0] =": 1,    # replaceSystem
    "messages.clear()": 1, # reset
}
historical_chat_full_scan_patterns = (
    "sourceEventSequenceForMessage(eventLog.events()",
    "restoreChatStateBefore(eventLog.events()",
    "restoreGroupStateBefore(eventLog.events()",
)
for pattern in historical_chat_full_scan_patterns:
    if pattern in engine:
        violations.append(
            "Historical Chat timeline rewrites must page event history instead of materializing the full archive"
        )

for name, source, forbidden in (
    ("LocalSubagentRunner.kt", subagent_runner, "eventLog().events()"),
    ("TokenUsageAnalytics.kt", token_usage_analytics, "eventLog.events()"),
):
    if forbidden in source:
        violations.append(
            f"{name} must resolve recent run attribution with newest-first SessionEventLog lookup, not full-history events()"
        )

if "fun latestMatching(" not in event_log:
    violations.append("SessionEventLog must keep newest-first predicate lookup for hot run attribution")

for token, expected in expected_buffer_counts.items():
    actual = model_history_buffer.count(token)
    if actual != expected:
        violations.append(
            f"LocalModelHistoryBuffer accounting path changed unexpectedly: {token!r} count={actual}, expected={expected}"
        )

if "val events = snapshot()" in event_log:
    violations.append("SessionEventLog.read must not materialize the whole event archive")
if "orderedFilesUnsafe().asReversed()" not in event_log:
    violations.append("SessionEventLog tail/latest paths must keep newest-first segment traversal")
if "RandomAccessFile(source, \"r\")" not in event_log or "REVERSE_READ_BUFFER_BYTES" not in event_log:
    violations.append("SessionEventLog restart sequence recovery must keep buffered reverse reading")
if "fun pageBefore(" not in event_log or "forEachEventReverseUnsafe" not in event_log:
    violations.append("SessionEventLog must keep bounded reverse paging for infinite-session history")

if (
    "summaryCache" not in repository
    or "LocalSessionSummaryIndex" not in repository
    or "cacheSummaryLocked(" not in repository
    or "summaryIndex.read(" not in repository
):
    violations.append("LocalSessionRepository must keep lightweight session-summary caching")

if not contains_any(session_snapshot_call_sources, "localSessionPersistenceSnapshot("):
    violations.append("Session snapshot callers must route persistence through the Session capability")

if "currentState: () -> LocalHarnessState" not in session_persistence_projection:
    violations.append("Session snapshot projection must defer mutable state reads until after cursor capture")

control_pos = session_persistence_projection.find(
    "val controlProjectedThroughSequence = eventLog.latestSequence()"
)
transcript_pos = session_persistence_projection.find("val transcriptProjectedThroughSequence")
state_pos = session_persistence_projection.find(
    "val state = currentState()"
)
if min(control_pos, transcript_pos, state_pos) < 0 or not (
    control_pos < state_pos and transcript_pos < state_pos
):
    violations.append(
        "Session snapshot persistence must capture durable projection cursors before reading mutable state"
    )

if "parse(synthetic.toString())" in deepseek:
    violations.append("DeepSeek streaming replies must not rebuild and reparse a synthetic full response")

if "usageMode: LocalUsageMode" in context_budget or "DEFAULT_CHAT_TOOL_RESULT_TOKENS" in context_budget:
    violations.append("Context/tool-result budgets must be shared across Chat and Work product surfaces")

if contains_any(foreground_execution_sources, 'eventLog.append("user/queue"'):
    violations.append("Queued user input must use the durable agent/inbox/spliced fact, not legacy user/queue writers")
if (
    not contains_any(run_recovery_sources, "decodeLocalAgentInboxPending")
    or not contains_any(run_recovery_sources, "pendingInputs.restore(")
):
    violations.append("Session/Agent recovery must restore the durable Agent inbox")

if not ordered_in_any(
    run_recovery_sources,
    "liveWorkRun(sessionId)?.let { liveBinding ->",
    "syncVisibleWorkRun(sessionId, liveBinding)",
    "sessionCoordinator.readWithLegacyApproval(sessionId)",
    "eventLog.repairInterruptedTail()",
):
    violations.append(
        "Live session-bound Work runtime must rebind before any durable recovery path"
    )
if not contains_any(
    work_execution_sources,
    "executeSafely(call.toLocalToolCall(), allowMutation = true, binding = binding)",
):
    violations.append(
        "Bound Work single-tool execution must keep the originating session binding"
    )
if (
    not contains_any(work_execution_sources, "binding = binding,")
    or not contains_any(work_execution_sources, "executeToolBatch(")
):
    violations.append(
        "Bound Work tool batches must keep the originating session binding"
    )
if not contains_any(work_execution_sources, "LocalRuntimeOwnershipPolicy.allowVisibleQueuedTurn("):
    violations.append(
        "Shared visible queue must stay idle while the current session has a live Work owner"
    )
if not contains_any(work_execution_sources, "agentRunCoordinator.ensureCurrentOwner(runContext)"):
    violations.append(
        "Foreground Work execution must reject late work after runtime ownership transfers"
    )
if "val latestRunId = log.latest(eventType(kind))" not in run_coordinator:
    violations.append(
        "Recovery checkpoints must not overwrite a newer run for the same session"
    )
wake_path_count = count_in_sources(
    run_recovery_sources,
    "startNextQueuedTurnIfIdle()?.start()",
)
if wake_path_count < 5:
    violations.append("Recovered durable Agent inbox must keep startup/session-switch wake paths")

if (
    not contains_any(chat_execution_sources, "transcriptForBranchMaterialization(")
    or not contains_any(chat_execution_sources, "restoreMaterializedChatBranchState(")
):
    violations.append("Chat branching must materialize full history only on demand and preserve durable branch graphs")
if "(current.messages + messages).takeLast(runtimeWindowMessages)" not in transcript_runtime:
    violations.append("Runtime transcript must stay bounded inside LocalTranscriptRuntime")
if (
    "LocalSessionCoordinator(" not in session_storage_runtime
    or "sessionCoordinator.snapshot(" not in session_persistence_projection
    or "coordinator.snapshot(" not in session_storage_runtime
    or not contains_any(session_snapshot_call_sources, "localSessionPersistenceSnapshot(")
):
    violations.append("Session snapshot writes must stay routed through LocalSessionCoordinator")
if (
    "messages = emptyList()" not in coordinator or
    "transcriptWindow = state.messages.takeLast(runtimeWindowMessages)" not in coordinator
):
    violations.append("Session snapshots must persist only a bounded transcriptWindow, never full state.messages")
if not contains_any(chat_execution_sources, "LocalSessionTranscriptPager(eventLog).all()"):
    violations.append("Full Chat transcript reads must go through the Session Event pager")
for name, source in (
    ("LocalChatEditSupport.kt", chat_edit_support),
    ("LocalChatContextRefreshCoordinator.kt", chat_context_refresh),
):
    if ".events()" in source:
        violations.append(f"{name} must page historical events instead of scanning the full archive")

if "if (!policy.toolsEnabled) return JsonArray(emptyList())" not in tool_coordinator:
    violations.append("Chat capability policy must project an empty model tool catalog through LocalToolExecutionCoordinator")
if not contains_any(work_execution_sources, "toolCalls = if (runPolicy.allowToolExecution)"):
    violations.append("Work AgentLoop must strip disallowed tool calls before execution")
if (
    contains_any(foreground_execution_sources, '"to", "vision-tool"')
    or contains_any(foreground_execution_sources, '"multimodal/fallback"')
):
    violations.append("Native-image failures must not fall back to a separate vision model")
if not contains_any(foreground_execution_sources, "当前模型不支持图片理解"):
    violations.append("Unsupported current-model image input must surface an explicit user-facing error")
if "suspend fun run(" not in group_chat_executor:
    violations.append("Group Chat must keep its dedicated multi-character execution owner")
if not contains_any(work_execution_sources, "val loop = AgentLoop("):
    violations.append("Work foreground execution must keep the primary AgentLoop under the Work-owned execution surface")
if "底层能力与工作界面共用同一套 Agent、工具、权限和上下文治理" in engine:
    violations.append("Chat prompt must not advertise Work tools or execution capabilities")
if "以用户当前输入、明确纠正和当前状态为准" not in prompt_context or "当前模式只进行聊天，不执行工作任务或工具操作" not in prompt_context:
    violations.append("Chat prompt must retain current-state priority and chat-only execution boundaries")
if "持续到任务完成或遇到真实阻塞" not in prompt_context or "最终结论必须有实际结果支撑" not in prompt_context:
    violations.append("Work prompt must retain abstract execution-discipline and evidence-based completion rules")
if "const val PROBE_ATTEMPTS = 3" not in web_diagnostics or "const val SAFE_HTTP_RETRY_ATTEMPTS = 3" not in web_provider:
    violations.append("Network diagnosis and safe HTTP reads must keep bounded three-attempt retry resilience")
if '"X-RateLimit-Remaining"' not in web_provider or '"Retry-After"' not in web_provider:
    violations.append("HTTP tooling must expose safe rate-limit response headers for error classification")

if (
    not contains_any(run_recovery_sources, "agentRunCoordinator.start(")
    or not contains_any(run_recovery_sources, "agentRunCoordinator.recoveryDecision(")
):
    violations.append("Foreground Agent runs must use durable LocalAgentRunCoordinator checkpoints and restart recovery")
if "eventType(context.kind)" not in run_coordinator or "TOOL_OUTCOME_UNKNOWN" not in run_coordinator:
    violations.append("Run checkpoints must stay isolated by execution kind and block unsafe side-effect recovery")
if "runCoordinator?.recordEvent" not in subagent_runner or "runKind: LocalAgentRunKind" not in subagent_runner:
    violations.append("Subagent and automation runs must share the unified run-context checkpoint boundary")
if not contains_any(foreground_execution_sources, "modelRequestCoordinator.complete("):
    violations.append("Foreground model transport must stay routed through LocalModelRequestCoordinator")
if not contains_any(work_execution_sources, "toolExecutionCoordinator.execute("):
    violations.append("Foreground registered tools must stay routed through LocalToolExecutionCoordinator")
if not contains_any(chat_execution_sources, "chatTurnCoordinator.prepare("):
    violations.append("Chat semantic preparation must stay routed through LocalChatTurnCoordinator")
if "messages = emptyList()" not in coordinator:
    violations.append("LocalSessionCoordinator must keep legacy full transcript out of new snapshots")

for forbidden_direct in (
    "toolRegistry.execute(",
    "modelClient.complete(",
    "modelClient.completeStreaming(",
    "AgentRequestExecutor(",
    "chatTurnRunner.",
    "chatInteractionPlanner.",
    "sessionRepository.read(",
    "sessionRepository.enqueue(",
    "sessionRepository.delete(",
    "sessionRepository.summaries()",
):
    if forbidden_direct in engine:
        violations.append(
            f"LocalHarnessEngine bypassed an extracted coordinator: {forbidden_direct}"
        )

chat_execution_text = "\n".join(chat_execution_sources.values())
chat_turn = re.search(
    r"(?:private|internal)?\s*suspend fun runChatTurn\(.*?(?=\n\s*(?:private|internal|public)?\s*(?:suspend\s+)?fun |\Z)",
    chat_execution_text,
    re.S,
)
if chat_turn is None:
    violations.append("Chat foreground turn implementation is missing from the Chat execution surface")
else:
    chat_turn_body = chat_turn.group(0)
    if "cancelChatPostTurn()" not in chat_turn_body:
        violations.append("Chat turns must cancel stale post-turn refresh before capturing new context")
    if "withChatTurnContext(" not in chat_turn_body:
        violations.append("Chat turns must preserve stable/dynamic context placement")

if not contains_any(work_execution_sources, "val loop = AgentLoop("):
    violations.append("Work foreground AgentLoop is missing from the Work execution surface")
if not contains_any(chat_execution_sources, "chatReplyCoordinator.finalizeDirect("):
    violations.append("Direct Chat replies must pass the pre-commit scene continuity guard")
if "chatReplyCoordinator.finalizeGroup(" not in group_chat_executor:
    violations.append("Group Chat replies must pass the shared-scene continuity guard")
if "chatReplyCoordinator.guardProactive(" not in automation_chat:
    violations.append("Proactive Chat replies must pass the pre-commit scene continuity guard")

if (
    not contains_any(chat_execution_sources, "before.chat.chatBranches.nodes.isNotEmpty()")
    or not contains_any(chat_execution_sources, "appendMaterializedChatBranchMessage(")
):
    violations.append("Chat branch continuation must only materialize after a real branch already exists")

if violations:
    print("Local performance invariant guard failed:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    sys.exit(1)

print("Local performance invariant guard passed")
