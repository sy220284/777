package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.observability.AppLogEntry

/** Owns environment diagnostic snapshot selection and report assembly. */
internal class LocalEnvironmentInfoCoordinator(
    private val workspacePath: () -> String,
    private val resourceSnapshot: () -> HarnessResourceSnapshot,
    private val historyBudget: (LocalWorkRunBinding?) -> LocalHistoryBudget,
    private val requestPressureStore: LocalRequestPressureStore,
    private val usageTracker: DeepSeekUsageTracker,
    private val toolExecutionCoordinator: LocalToolExecutionCoordinator,
    private val commandAvailable: (String) -> Boolean,
    private val runtimeStatuses: () -> List<String>,
    private val diagnostics: () -> List<AppLogEntry>,
    private val storageStatus: () -> String,
    private val processExitStatus: () -> String,
    private val foregroundSessionId: () -> String,
    private val foregroundHistory: () -> LocalModelHistoryBuffer,
    private val foregroundPendingInputs: () -> Int,
    private val foregroundWorkBudget: (String) -> LocalWorkExecutionBudget.Snapshot?,
) {
    fun build(binding: LocalWorkRunBinding?): String {
        val sessionId = binding?.sessionId ?: foregroundSessionId()
        val history = binding?.modelHistory ?: foregroundHistory()
        val pendingInputCount = binding?.pendingInputs?.size() ?: foregroundPendingInputs()
        val latestRequest = usageTracker.analyticsSnapshot().recentRecords.firstOrNull { record ->
            record.reported && record.inputTokens > 0L && record.context.sessionId == sessionId
        }
        val enabledOptional = binding?.enabledOptionalTools?.let { tools ->
            synchronized(tools) { tools.toSet() }
        } ?: toolExecutionCoordinator.enabledOptionalSnapshot()
        val commands = COMMANDS.filter(commandAvailable)
        return LocalEnvironmentReport.build(
            workspacePath = workspacePath(),
            resources = resourceSnapshot(),
            contextChars = history.encodedChars,
            contextBudgetChars = historyBudget(binding).maxHistoryChars,
            requestPressure = requestPressureStore.latest(sessionId),
            contextWindow = requestPressureStore.window(sessionId),
            workBudget = binding?.executionControl?.budget?.snapshot() ?: foregroundWorkBudget(sessionId),
            latestRequest = latestRequest,
            capabilitySummary = toolExecutionCoordinator.capabilitySummary(enabledOptional),
            pendingInputs = pendingInputCount,
            pendingInputLimit = LocalHarnessEngine.MAX_PENDING_INPUTS,
            commands = commands,
            runtimeStatuses = runtimeStatuses(),
            recentDiagnostics = diagnostics(),
        ) + "\n" + storageStatus() + "\n" + processExitStatus()
    }

    private companion object {
        val COMMANDS = listOf(
            "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "sed", "grep", "find",
            "git", "curl", "wget", "python3", "python", "node",
        )
    }
}
