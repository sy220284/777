package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.session.RegisteredSessionProjection
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionSnapshot
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal enum class LocalToolActivityKind {
    SEARCH,
    WEB,
    BROWSER,
    FILE,
    CODE,
    TERMINAL,
    IMAGE,
    MEMORY,
    MCP,
    AGENT,
    TASK,
    DEVICE,
    GENERIC,
}

internal enum class LocalToolActivityPhase {
    DECLARED,
    RUNNING,
    COMPLETED,
    FAILED,
    OUTCOME_UNKNOWN,
    CANCELLED,
}

internal data class LocalToolActivity(
    val callId: String,
    val name: String,
    val kind: LocalToolActivityKind,
    val phase: LocalToolActivityPhase,
    val executionId: String? = null,
    val errorCode: String? = null,
    val sideEffect: String? = null,
    val declaredSequence: Long? = null,
    val startedSequence: Long? = null,
    val finishedSequence: Long? = null,
    val argumentsPreview: String? = null,
    val resultPreview: String? = null,
)

internal data class LocalToolActivityState(
    val activities: List<LocalToolActivity> = emptyList(),
)

/**
 * Shared Tool-owned projection over the existing Session EventLog.
 *
 * No activity event is persisted here. tool/call, tool/execution-started and tool/result remain the
 * only durable facts; this projection only makes those facts convenient for presentation layers.
 */
@Singleton
internal class LocalToolActivityProjectionRuntime @Inject constructor(
    sessionStorage: LocalSessionStorageRuntime,
) {
    private val projection: RegisteredSessionProjection<LocalToolActivityState> =
        sessionStorage.projectionRegistry.register(
            name = "tool.activity",
            stateVersion = 2,
            initial = { LocalToolActivityState() },
            reducer = SessionReducer(::reduceLocalToolActivity),
        )

    internal fun snapshot(
        eventLog: LocalSessionEventLog,
    ): SessionProjectionSnapshot<LocalToolActivityState> =
        projection.fold(localToolActivityProjectionEvents(eventLog))
}

internal fun localToolActivityProjectionEvents(
    eventLog: LocalSessionEventLog,
): List<SessionEvent> =
    eventLog.pageBeforeChronological(limit = LOCAL_TOOL_ACTIVITY_EVENT_SCAN_LIMIT)
        .filter { it.type in LOCAL_TOOL_ACTIVITY_EVENT_TYPES }
        .map { event ->
            SessionEvent(
                sequence = event.sequence,
                type = event.type,
                createdAt = event.createdAt,
                data = event.data,
            )
        }

internal fun reduceLocalToolActivity(
    state: LocalToolActivityState,
    event: SessionEvent,
): LocalToolActivityState {
    val callId = event.data["id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: return state
    val previous = state.activities.lastOrNull { it.callId == callId }
    val name = event.data["name"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?: previous?.name
        ?: "tool"
    val next = when (event.type) {
        "tool/call" -> LocalToolActivity(
            callId = callId,
            name = name,
            kind = localToolActivityKind(name),
            phase = if (
                event.data["execution_started"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                LocalToolActivityPhase.RUNNING
            } else {
                LocalToolActivityPhase.DECLARED
            },
            executionId = previous?.executionId,
            argumentsPreview = event.data["arguments"]?.toString()?.let { truncateWithoutSplittingSurrogatePair(it, 2_000) },
            declaredSequence = event.sequence,
            startedSequence = previous?.startedSequence,
        )
        "tool/execution-started" -> (previous ?: LocalToolActivity(
            callId = callId,
            name = name,
            kind = localToolActivityKind(name),
            phase = LocalToolActivityPhase.DECLARED,
        )).copy(
            name = name,
            kind = localToolActivityKind(name),
            phase = LocalToolActivityPhase.RUNNING,
            executionId = event.data["execution_id"]?.jsonPrimitive?.contentOrNull,
            startedSequence = event.sequence,
        )
        "tool/result" -> {
            val errorCode = event.data["error_code"]?.jsonPrimitive?.contentOrNull
            val isError = event.data["is_error"]?.jsonPrimitive?.booleanOrNull == true
            val phase = when {
                errorCode == "TOOL_OUTCOME_UNKNOWN" -> LocalToolActivityPhase.OUTCOME_UNKNOWN
                errorCode?.contains("CANCEL", ignoreCase = true) == true ->
                    LocalToolActivityPhase.CANCELLED
                isError || !errorCode.isNullOrBlank() -> LocalToolActivityPhase.FAILED
                else -> LocalToolActivityPhase.COMPLETED
            }
            (previous ?: LocalToolActivity(
                callId = callId,
                name = name,
                kind = localToolActivityKind(name),
                phase = LocalToolActivityPhase.DECLARED,
            )).copy(
                name = name,
                kind = localToolActivityKind(name),
                phase = phase,
                errorCode = errorCode,
                sideEffect = event.data["side_effect"]?.jsonPrimitive?.contentOrNull,
                finishedSequence = event.sequence,
                resultPreview = event.data["content"]?.jsonPrimitive?.contentOrNull
                    ?.let { truncateWithoutSplittingSurrogatePair(it, 4_000) },
            )
        }
        else -> return state
    }
    return state.copy(
        activities = (state.activities.filterNot { it.callId == callId } + next)
            .takeLast(LOCAL_TOOL_ACTIVITY_LIMIT),
    )
}

internal fun localToolActivityKind(name: String): LocalToolActivityKind {
    val canonical = name.lowercase()
    return when {
        "browser" in canonical -> LocalToolActivityKind.BROWSER
        "search" in canonical -> LocalToolActivityKind.SEARCH
        canonical.startsWith("web_") || "http" in canonical || "download" in canonical ->
            LocalToolActivityKind.WEB
        canonical in setOf("read", "write", "edit", "apply_patch", "file_inspect", "list_files", "glob", "grep", "json_query") ->
            LocalToolActivityKind.FILE
        "lsp" in canonical || "code" in canonical -> LocalToolActivityKind.CODE
        canonical in setOf("bash", "job_list", "job_output", "job_kill") ||
            "terminal" in canonical || "shell" in canonical -> LocalToolActivityKind.TERMINAL
        "image" in canonical || "vision" in canonical -> LocalToolActivityKind.IMAGE
        "memory" in canonical -> LocalToolActivityKind.MEMORY
        "mcp" in canonical -> LocalToolActivityKind.MCP
        "subagent" in canonical || canonical.startsWith("team_") || canonical == "workflow" ||
            canonical in setOf("send_message", "interrupt_agent", "list_agents") ->
            LocalToolActivityKind.AGENT
        "task" in canonical || "todo" in canonical || "goal" in canonical || "automation" in canonical ->
            LocalToolActivityKind.TASK
        canonical.startsWith("android_") || "device" in canonical ->
            LocalToolActivityKind.DEVICE
        else -> LocalToolActivityKind.GENERIC
    }
}

private const val LOCAL_TOOL_ACTIVITY_LIMIT = 64
private const val LOCAL_TOOL_ACTIVITY_EVENT_SCAN_LIMIT = 256
private val LOCAL_TOOL_ACTIVITY_EVENT_TYPES = setOf(
    "tool/call",
    "tool/execution-started",
    "tool/result",
)
