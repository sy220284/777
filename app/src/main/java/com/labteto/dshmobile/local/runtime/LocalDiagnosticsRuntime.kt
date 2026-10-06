package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.session.LocalSessionArchiveMaintenance
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.observability.DiagnosticReport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared diagnostics owner consumed by Settings and tool surfaces. */
@Singleton
internal class LocalDiagnosticsRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val usageTracker: DeepSeekUsageTracker,
    private val workDiagnostics: LocalWorkDiagnosticsProvider,
    private val bundledRuntimeManager: LocalBundledRuntimeManager,
    private val tools: LocalToolCompositionRoot,
) : LocalDiagnosticsPort {
    private val sessionsRoot = File(context.filesDir, "local-harness/sessions").apply { mkdirs() }
    private val environment by lazy {
        LocalEnvironmentInfoCoordinator(
            workspacePath = { tools.workspace.path },
            resourceSnapshot = runtimeStateStore::resourceSnapshot,
            foregroundHistoryBudgetChars = {
                val snapshot = runtimeStateStore.state.value
                runtimeStateStore.historyBudgetFor(snapshot, runtimeStateStore.resourceSnapshot()).maxHistoryChars
            },
            requestPressureStore = runtimeStateStore.requestPressureStore,
            usageTracker = usageTracker,
            toolExecutionCoordinator = tools.execution,
            commandAvailable = tools.process::isCommandAvailable,
            runtimeStatuses = bundledRuntimeManager::statuses,
            diagnostics = AppLog::snapshot,
            storageStatus = { LocalSessionArchiveMaintenance.storageStatus(sessionsRoot) },
            processExitStatus = { LocalProcessExitStatus.read(context) },
            foregroundSessionId = runtimeStateStore::currentSessionId,
            foregroundHistory = { runtimeStateStore.foregroundRunHandle.modelHistory },
            foregroundPendingInputs = { runtimeStateStore.foregroundRunHandle.pendingInputs.size() },
            pendingInputLimit = MAX_PENDING_INPUTS,
            workContextAssessment = workDiagnostics::contextAssessment,
            foregroundWorkBudget = workDiagnostics::budget,
        )
    }

    internal fun environmentInfo(run: LocalEnvironmentRunSnapshot? = null): String = environment.build(run)

    override suspend fun environmentInfo(): String = withContext(Dispatchers.IO) {
        environment.build(null)
    }

    override suspend fun diagnosticReport(): String = withContext(Dispatchers.IO) {
        val sessionId = runtimeStateStore.currentSessionId
        val appLogs = AppLog.exportSnapshot()
        val baseReport = DiagnosticReport.build(appLogs, environment.build(null))
        appendLocalDiagnosticDetails(
            baseReport = baseReport,
            sessionId = sessionId,
            eventLog = sessionStorage.eventLogs.get(sessionId),
            usageTracker = usageTracker,
            appLogs = appLogs,
        )
    }
}
