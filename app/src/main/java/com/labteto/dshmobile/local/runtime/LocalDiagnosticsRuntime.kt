package com.labteto.dshmobile.local.runtime

import android.content.Context
import com.labteto.dshmobile.local.LocalToolCompositionRoot
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
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
            storageStatus = sessionStorage::storageDiagnosticSummary,
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
        val captureIssues = mutableListOf<String>()
        val environmentText = try {
            environment.build(null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.failure("Diagnostics", "export_environment", error, sessionId = sessionId)
            captureIssues += "环境信息采集失败：${error.javaClass.simpleName}"
            "环境信息读取失败，其余诊断继续导出"
        }

        // Capture session evidence before taking the final process-log snapshot: failures
        // in this stage must appear in the same report, not only in the next export.
        var sessionComplete = true
        val sessionDetails = try {
            appendLocalDiagnosticDetails(
                baseReport = "",
                sessionId = sessionId,
                eventLog = sessionStorage.eventLogs.get(sessionId),
                usageTracker = usageTracker,
                appLogs = AppLog.exportForDiagnostics().entries,
                onSessionIntegrity = { sessionComplete = it },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.failure("Diagnostics", "export_session_trace", error, sessionId = sessionId)
            captureIssues += "会话结构化追踪采集失败：${error.javaClass.simpleName}"
            "会话结构化追踪缺失：operation=export_session_trace；原因=${error.javaClass.simpleName}。基础进程日志仍可用。"
        }
        if (!sessionComplete) captureIssues += "会话事件存在损坏或读取失败"

        val processLogs = AppLog.exportForDiagnostics()
        val integrity = listOf(processLogs.integrity) + captureIssues
        val status = if (integrity.size == 1 && integrity[0] == "正常") "正常" else
            integrity.filter { it != "正常" }.joinToString("；")
        DiagnosticReport.build(
            processLogs.entries,
            environmentText,
            logIntegrity = status,
        ) + "\n\n" + sessionDetails.trimStart()
    }
}
