package com.labteto.dshmobile.local.work

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
 * domains. Aggregate LocalHarnessState is only reconstructed at the composition boundary when a
 * legacy execution API still needs a read-only snapshot during Architecture 3.0 migration.
 */
internal data class LocalWorkRunState(
    val modelState: LocalModelState = LocalModelState(),
    val mainMaxSteps: Int = 16,
    val subagentMaxSteps: Int = 20,
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

/** Read-only migration snapshot for shared/legacy execution APIs; never retained as Work state. */
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
