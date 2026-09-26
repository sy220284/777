#!/usr/bin/env python3
"""Guard local Harness performance invariants that are easy to regress in code review."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
EVENT_LOG = ROOT / "harness-core/src/main/kotlin/com/labteto/dshmobile/harness/session/SessionEventLog.kt"
REPOSITORY = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSessionRepository.kt"
DEEPSEEK = ROOT / "app/src/main/java/com/labteto/dshmobile/local/DeepSeekClient.kt"
CONTEXT_BUDGET = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalContextBudget.kt"
COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSessionCoordinator.kt"
RUN_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalAgentRunCoordinator.kt"
MODEL_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalModelRequestCoordinator.kt"
TOOL_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalToolExecutionCoordinator.kt"
CHAT_COORDINATOR = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalChatTurnCoordinator.kt"
SUBAGENT_RUNNER = ROOT / "app/src/main/java/com/labteto/dshmobile/local/LocalSubagentRunner.kt"

violations: list[str] = []

engine = ENGINE.read_text(encoding="utf-8")
event_log = EVENT_LOG.read_text(encoding="utf-8")
repository = REPOSITORY.read_text(encoding="utf-8")
deepseek = DEEPSEEK.read_text(encoding="utf-8")
context_budget = CONTEXT_BUDGET.read_text(encoding="utf-8")
coordinator = COORDINATOR.read_text(encoding="utf-8")
run_coordinator = RUN_COORDINATOR.read_text(encoding="utf-8")
model_coordinator = MODEL_COORDINATOR.read_text(encoding="utf-8")
tool_coordinator = TOOL_COORDINATOR.read_text(encoding="utf-8")
chat_coordinator = CHAT_COORDINATOR.read_text(encoding="utf-8")
subagent_runner = SUBAGENT_RUNNER.read_text(encoding="utf-8")

def constant(name: str) -> int | None:
    match = re.search(rf"const val {re.escape(name)}\s*=\s*([0-9_]+)(?:L)?", engine)
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

# All mutable model-history writes must go through the size-accounting helpers.
expected_counts = {
    "modelHistory +=": 2,      # appendModelHistory + resetModelHistory
    "modelHistory.add(": 1,    # prependModelHistory
    "modelHistory[0] =": 1,    # replaceSystemModelHistory
    "modelHistory.clear()": 1, # resetModelHistory
}
for token, expected in expected_counts.items():
    actual = engine.count(token)
    if actual != expected:
        violations.append(
            f"model history mutation bypass risk: {token!r} count={actual}, expected={expected}"
        )

if "val events = snapshot()" in event_log:
    violations.append("SessionEventLog.read must not materialize the whole event archive")
if "orderedFilesUnsafe().asReversed()" not in event_log:
    violations.append("SessionEventLog tail/latest paths must keep newest-first segment traversal")
if "RandomAccessFile(source, \"r\")" not in event_log or "REVERSE_READ_BUFFER_BYTES" not in event_log:
    violations.append("SessionEventLog restart sequence recovery must keep buffered reverse reading")
if "fun pageBefore(" not in event_log or "forEachEventReverseUnsafe" not in event_log:
    violations.append("SessionEventLog must keep bounded reverse paging for infinite-session history")

if "summaryCache" not in repository or "snapshot.toSummary()" not in repository:
    violations.append("LocalSessionRepository must keep lightweight session-summary caching")

if "parse(synthetic.toString())" in deepseek:
    violations.append("DeepSeek streaming replies must not rebuild and reparse a synthetic full response")

if "usageMode: LocalUsageMode" in context_budget or "DEFAULT_CHAT_TOOL_RESULT_TOKENS" in context_budget:
    violations.append("Context/tool-result budgets must be shared across Chat and Work product surfaces")

if 'eventLog.append("user/queue"' in engine:
    violations.append("Queued user input must use the durable agent/inbox/spliced fact, not legacy user/queue writers")
if "decodeLocalAgentInboxPending" not in engine or "pendingInputs.restore(" not in engine:
    violations.append("LocalHarnessEngine must restore the durable Agent inbox on Session load")
if engine.count("startNextQueuedTurnIfIdle()?.start()") < 5:
    violations.append("Recovered durable Agent inbox must keep startup/session-switch wake paths")

if "transcriptForBranchMaterialization(" not in engine or "restoreMaterializedChatBranchState(" not in engine:
    violations.append("Chat branching must materialize full history only on demand and preserve durable branch graphs")
if "(state.messages + messages).takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)" not in engine:
    violations.append("Runtime transcript must stay bounded to LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES")
if "LocalSessionCoordinator(" not in engine or "sessionCoordinator.snapshot(" not in engine:
    violations.append("Session snapshot writes must stay routed through LocalSessionCoordinator")
if (
    "messages = emptyList()" not in coordinator or
    "transcriptWindow = state.messages.takeLast(runtimeWindowMessages)" not in coordinator
):
    violations.append("Session snapshots must persist only a bounded transcriptWindow, never full state.messages")
if "LocalSessionTranscriptPager(eventLog).all()" not in engine:
    violations.append("Full transcript reads must go through the Session Event pager")

if "if (!policy.toolsEnabled) return JsonArray(emptyList())" not in tool_coordinator:
    violations.append("Chat capability policy must project an empty model tool catalog through LocalToolExecutionCoordinator")
if "toolCalls = if (runPolicy.allowToolExecution)" not in engine:
    violations.append("Chat model replies must strip unexpected tool calls before AgentLoop execution")
if "runPolicy.imageFallbackToVisionTool" not in engine:
    violations.append("Chat native-image failures must not fall back to Work vision tools")
if "runGroupChatTurn(input)" not in engine or "runAgentTurn(input, memoryInput)" not in engine:
    violations.append("Single chat must use the primary AgentLoop while group chat keeps multi-character orchestration")
if "maxSteps = if (runPolicy.allowToolExecution) mainMaxSteps else 1" not in engine:
    violations.append("Single chat must remain a one-step primary-agent reply")
if "底层能力与工作界面共用同一套 Agent、工具、权限和上下文治理" in engine:
    violations.append("Chat prompt must not advertise Work tools or execution capabilities")

if "agentRunCoordinator.start(" not in engine or "agentRunCoordinator.recoveryDecision(" not in engine:
    violations.append("Foreground Agent runs must use durable LocalAgentRunCoordinator checkpoints and restart recovery")
if "eventType(context.kind)" not in run_coordinator or "TOOL_OUTCOME_UNKNOWN" not in run_coordinator:
    violations.append("Run checkpoints must stay isolated by execution kind and block unsafe side-effect recovery")
if "runCoordinator?.recordEvent" not in subagent_runner or "runKind: LocalAgentRunKind" not in subagent_runner:
    violations.append("Subagent and automation runs must share the unified run-context checkpoint boundary")
if "modelRequestCoordinator.complete(" not in engine:
    violations.append("Foreground model transport must stay routed through LocalModelRequestCoordinator")
if "toolExecutionCoordinator.execute(" not in engine:
    violations.append("Foreground registered tools must stay routed through LocalToolExecutionCoordinator")
if "chatTurnCoordinator.prepare(" not in engine or "chatTurnCoordinator.finalize(" not in engine:
    violations.append("Chat semantic preparation/finalization must stay routed through LocalChatTurnCoordinator")
if "messages = emptyList()" not in coordinator:
    violations.append("LocalSessionCoordinator must keep legacy full transcript out of new snapshots")

run_agent = re.search(
    r"private suspend fun runAgentTurn\(.*?\n    private fun AgentToolCall",
    engine,
    re.S,
)
if run_agent is None:
    violations.append("Unified foreground Agent loop is missing")
else:
    run_agent_body = run_agent.group(0)
    if "cancelChatPostTurn()" not in run_agent_body:
        violations.append("Chat turns must cancel stale post-turn refresh before capturing new context")
    if "withChatTurnContext(" not in run_agent_body:
        violations.append("Unified Chat turns must preserve stable/dynamic context placement")
if "before.chatBranches.nodes.isNotEmpty()" not in engine or "appendMaterializedChatBranchMessage(" not in engine:
    violations.append("Chat branch continuation must only materialize after a real branch already exists")

if violations:
    print("Local performance invariant guard failed:", file=sys.stderr)
    for violation in violations:
        print(f"  - {violation}", file=sys.stderr)
    sys.exit(1)

print("Local performance invariant guard passed")
