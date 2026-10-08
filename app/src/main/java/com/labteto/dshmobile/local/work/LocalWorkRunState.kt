package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.LocalAgentRuntimeSettings
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelState
import com.labteto.dshmobile.local.runtime.LocalKernelState
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalTranscriptRuntimeIndex

/**
 * Mutable state owned by one detached/foreground Work run.
 *
 * This deliberately excludes Chat, Settings, Session lists, usage accounting and other product
 * domains. Aggregate LocalHarnessState is reconstructed only as a read-only compatibility snapshot
 * for Shared request APIs; Work never receives an aggregate write boundary.
 */
internal data class LocalWorkRunState(
    val modelState: LocalModelState = LocalModelState(),
    val mainMaxSteps: Int = LocalAgentRuntimeSettings.DEFAULT_MAIN_MAX_STEPS,
    val subagentMaxSteps: Int = LocalAgentRuntimeSettings.DEFAULT_SUBAGENT_MAX_STEPS,
    val workspacePath: String = "",
    val sessionId: String,
    val conversationMode: LocalConversationMode = LocalConversationMode.INDEPENDENT,
    val parentSessionId: String? = null,
    val lineageId: String = "",
    val projectId: String? = null,
    val handoffSummary: String? = null,
    val userRules: String = "",
    val autoRecall: Boolean = true,
    val autoMemory: Boolean = true,
    val messages: List<LocalHarnessMessage> = emptyList(),
    val transcriptIndex: LocalTranscriptRuntimeIndex = LocalTranscriptRuntimeIndex(),
    val work: LocalWorkState = LocalWorkState(),
    val kernel: LocalKernelState = LocalKernelState(),
    val safeAutoApprovalEnabled: Boolean = false,
    val error: String? = null,
)

internal fun LocalHarnessState.toLocalWorkRunState(): LocalWorkRunState {
    require(usageMode == LocalUsageMode.WORK) { "Work run 只能从 Work 会话创建" }
    return LocalWorkRunState(
        modelState = modelState,
        mainMaxSteps = mainMaxSteps,
        subagentMaxSteps = subagentMaxSteps,
        workspacePath = workspacePath,
        sessionId = sessionId,
        conversationMode = conversationMode,
        parentSessionId = parentSessionId,
        lineageId = lineageId,
        projectId = projectId,
        handoffSummary = handoffSummary,
        userRules = userRules,
        autoRecall = autoRecall,
        autoMemory = autoMemory,
        messages = messages,
        transcriptIndex = transcriptIndex,
        work = work,
        kernel = kernel,
        safeAutoApprovalEnabled = safeAutoApprovalEnabled,
        error = error,
    )
}

/** Read-only compatibility snapshot for Shared request APIs; never retained or used as a write owner. */
internal fun LocalWorkRunState.toAggregateSnapshot(): LocalHarnessState = LocalHarnessState(
    loading = false,
    modelState = modelState,
    mainMaxSteps = mainMaxSteps,
    subagentMaxSteps = subagentMaxSteps,
    workspacePath = workspacePath,
    sessionId = sessionId,
    usageMode = LocalUsageMode.WORK,
    conversationMode = conversationMode,
    parentSessionId = parentSessionId,
    lineageId = lineageId,
    projectId = projectId,
    handoffSummary = handoffSummary,
    userRules = userRules,
    autoRecall = autoRecall,
    autoMemory = autoMemory,
    messages = messages,
    transcriptIndex = transcriptIndex,
    work = work,
    kernel = kernel,
    safeAutoApprovalEnabled = safeAutoApprovalEnabled,
    error = error,
)
