package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalForegroundTurnWakeCoordinator
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LocalSubagentResult
import com.labteto.dshmobile.local.model.LocalModelRunContext
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.runtime.LOCAL_PROJECT_ID
import com.labteto.dshmobile.local.runtime.LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.MAX_EVENT_CHARS
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.session.LocalSessionTranscriptPager
import com.labteto.dshmobile.local.session.appendLocalTranscriptRuntimeIndex
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.session.localTranscriptIndexForSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns durable Work-automation execution and transcript persistence.
 *
 * Automation stays detached from the visible UI session. Work owns runner construction,
 * recovery semantics, non-interactive approval and durable transcript delivery.
 */
@javax.inject.Singleton
internal class LocalWorkAutomationExecutionCoordinator @javax.inject.Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val work: LocalWorkComposition,
    private val wake: LocalForegroundTurnWakeCoordinator,
) : LocalWorkAutomationExecutionPort {
    private val sessionCoordinator get() = sessionStorage.coordinator
    override suspend fun prepareSession(
        text: String,
        preferredSessionId: String?,
    ): String {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "后台任务提示词不能为空" }
        awaitReady()
        return resolveSession(preferredSessionId, prompt).id
    }

    override suspend fun run(
        text: String,
        preferredSessionId: String?,
        timeoutMillis: Long,
        recoverInterrupted: Boolean,
    ): LocalWorkAutomationResult {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "后台任务提示词不能为空" }
        awaitReady()

        val sessionId = resolveSession(preferredSessionId, prompt).id
        val budget = LocalWorkAutomationTimeoutBudget(timeoutMillis, 15 * 60_000L)
        val sessionLease = budget.acquireSession(sessionId, LocalSessionRuntimeKind.AUTOMATION_WORK)
        try {
        // Re-read after ownership so deletion cannot be undone by a stale preflight snapshot.
        val session = requireWorkAutomationSession(sessionStorage.coordinator.read(sessionId))
        val boundState = boundState(session)
        val boundEventLog = sessionStorage.eventLogs.get(sessionId)
        val recovery = try {
            if (recoverInterrupted) {
                prepareWorkAutomationRecovery(
                    prompt = prompt,
                    sessionId = sessionId,
                    eventLog = boundEventLog,
                    profiles = runtimeStateStore.state.value.modelProfiles,
                    agentRunCoordinator = sessionStorage.agentRunCoordinator,
                    contextPolicy = LocalWorkRecoveryContextPolicy,
                )
            } else {
                LocalWorkAutomationRecoveryPlan(prompt)
            }
        } catch (blocked: LocalHarnessBlockedException) {
            val detail = blocked.message ?: "后台任务需要人工处理"
            return LocalWorkAutomationResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkAutomationStatus.BLOCKED,
                detail = detail,
            )
        }
        recovery.completedOutput?.let { output ->
            ensureRecoveredTranscript(
                session = session,
                output = output,
                eventLog = boundEventLog,
            )
            return LocalWorkAutomationResult(sessionId = sessionId, output = output)
        }
        val executionTask = recovery.executionTask
        val recoveredProfile = recovery.profile

        val userMessage = LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = prompt,
            createdAt = System.currentTimeMillis(),
        )
        if (!recoverInterrupted || !hasMatchingUser(boundEventLog, prompt)) {
            boundEventLog.append("user/message", buildJsonObject {
                put("content", prompt)
                put("automation", true)
                put("transcript", encodeTranscriptMessages(listOf(userMessage)))
            })
        }

        var blockedReason: String? = null
        val runner = work.automationRunner(
            sessionId = sessionId,
            boundState = boundState,
            onApprovalBlocked = { reason ->
                if (blockedReason == null) blockedReason = reason
            },
        )

        return try {
            val result = withTimeout(budget.remainingMillis()) {
                val execute: suspend () -> LocalSubagentResult = {
                    runner.runResult(
                        task = executionTask,
                        inheritHistory = false,
                        allowMutation = true,
                        maxSteps = boundState.subagentMaxSteps,
                    )
                }
                recoveredProfile?.let { profile ->
                    withContext(LocalModelRunContext(profile)) { execute() }
                } ?: execute()
            }

            blockedReason?.let { reason ->
                persistTranscript(
                    session = session,
                    messages = listOf(userMessage),
                    finalRole = "system",
                    finalContent = reason,
                    eventLog = boundEventLog,
                )
                return LocalWorkAutomationResult(
                    sessionId = sessionId,
                    output = reason,
                    status = LocalWorkAutomationStatus.BLOCKED,
                    detail = reason,
                )
            }

            val status = when (result.status) {
                com.labteto.dshmobile.local.agent.LocalSubagentStatus.COMPLETED ->
                    LocalWorkAutomationStatus.DELIVERED
                com.labteto.dshmobile.local.agent.LocalSubagentStatus.CANCELLED ->
                    LocalWorkAutomationStatus.CANCELLED
                com.labteto.dshmobile.local.agent.LocalSubagentStatus.STEP_LIMIT,
                com.labteto.dshmobile.local.agent.LocalSubagentStatus.FAILED ->
                    LocalWorkAutomationStatus.FAILED
            }
            val output = when (status) {
                LocalWorkAutomationStatus.DELIVERED -> result.output.ifBlank { "后台任务已完成" }
                LocalWorkAutomationStatus.CANCELLED -> result.output.ifBlank { "后台任务已取消" }
                else -> result.output.ifBlank { "后台任务失败" }
            }
            persistTranscript(
                session = session,
                messages = listOf(userMessage),
                finalRole = if (status == LocalWorkAutomationStatus.DELIVERED) "assistant" else "system",
                finalContent = output,
                eventLog = boundEventLog,
            )
            LocalWorkAutomationResult(
                sessionId = sessionId,
                output = output,
                status = status,
                detail = output.takeIf { status != LocalWorkAutomationStatus.DELIVERED },
            )
        } catch (timeout: TimeoutCancellationException) {
            val detail = "后台任务执行超时，已停止本轮任务"
            persistTranscript(
                session = session,
                messages = listOf(userMessage),
                finalRole = "system",
                finalContent = detail,
                eventLog = boundEventLog,
            )
            LocalWorkAutomationResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkAutomationStatus.FAILED,
                detail = detail,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val detail = "后台任务失败：" + (error.message ?: error::class.java.simpleName)
            persistTranscript(
                session = session,
                messages = listOf(userMessage),
                finalRole = "system",
                finalContent = detail,
                eventLog = boundEventLog,
            )
            LocalWorkAutomationResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkAutomationStatus.FAILED,
                detail = detail,
            )
        }
        } finally {
            sessionLease.close()
            if (runtimeStateStore.currentSessionId == sessionId &&
                runtimeStateStore.state.value.sessionId == sessionId
            ) {
                wake.startNextIfIdle()?.start()
            }
        }
    }

    private suspend fun awaitReady() {
        withTimeout(15_000L) {
            while (runtimeStateStore.state.value.loading) delay(50)
        }
        require(runtimeStateStore.state.value.modelState.configured) { "本机 Harness 尚未配置模型" }
    }

    private fun resolveSession(
        preferredSessionId: String?,
        prompt: String,
    ): LocalHarnessSession {
        preferredSessionId
            ?.takeIf(String::isNotBlank)
            ?.let(sessionCoordinator::read)
            ?.takeIf { it.usageMode == LocalUsageMode.WORK }
            ?.let { return it }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val session = LocalHarnessSession(
            id = id,
            title = prompt.lineSequence().firstOrNull()?.trim()?.take(36)
                ?.takeIf(String::isNotBlank)
                ?: "自动化任务",
            updatedAt = now,
            usageMode = LocalUsageMode.WORK,
            conversationMode = LocalConversationMode.PROJECT,
            lineageId = id,
            projectId = LOCAL_PROJECT_ID,
        )
        sessionStorage.coordinator.enqueue(session)
        return session
    }

    private fun boundState(session: LocalHarnessSession): LocalHarnessState {
        val runtime = runtimeStateStore.state.value
        val recentTranscript = LocalSessionTranscriptPager(sessionStorage.eventLogs.get(session.id))
            .page(limit = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
            .messages
            .ifEmpty {
                session.transcriptWindow
                    .ifEmpty { session.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES) }
            }
        return LocalWorkRunState(
            modelState = runtime.modelState,
            mainMaxSteps = runtime.mainMaxSteps,
            subagentMaxSteps = runtime.subagentMaxSteps,
            workspacePath = runtime.workspacePath,
            sessionId = session.id,
            conversationMode = session.conversationMode,
            parentSessionId = session.parentSessionId,
            lineageId = session.lineageId.ifBlank { session.id },
            projectId = session.projectId ?: LOCAL_PROJECT_ID,
            handoffSummary = session.handoffSummary,
            userRules = runtime.userRules,
            autoRecall = runtime.autoRecall,
            autoMemory = runtime.autoMemory,
            messages = recentTranscript,
            transcriptIndex = localTranscriptIndexForSession(session),
            work = LocalWorkState(
                plan = session.plan,
                todos = session.todos,
                goal = session.goal,
                planMode = false,
                jobs = emptyList(),
                pendingApproval = null,
                pendingQuestion = null,
            ),
            kernel = runtime.kernel.copy(running = false, queuedInputCount = 0),
            safeAutoApprovalEnabled = runtime.safeAutoApprovalEnabled,
            error = null,
        ).toAggregateSnapshot()
    }

    private fun hasMatchingUser(
        eventLog: LocalSessionEventLog,
        prompt: String,
    ): Boolean {
        val latest = eventLog.latest("user/message") ?: return false
        return latest.data["automation"]?.jsonPrimitive?.booleanOrNull == true &&
            latest.data["content"]?.jsonPrimitive?.contentOrNull == prompt
    }

    private fun ensureRecoveredTranscript(
        session: LocalHarnessSession,
        output: String,
        eventLog: LocalSessionEventLog,
    ) {
        val latestAssistant = eventLog.latest("assistant/message")
        val alreadyPersisted = latestAssistant?.data?.get("automation")
            ?.jsonPrimitive?.booleanOrNull == true &&
            latestAssistant.data["content"]?.jsonPrimitive?.contentOrNull == output
        if (alreadyPersisted) return
        persistTranscript(
            session = session,
            messages = emptyList(),
            finalRole = "assistant",
            finalContent = output,
            eventLog = eventLog,
        )
    }

    private fun persistTranscript(
        session: LocalHarnessSession,
        messages: List<LocalHarnessMessage>,
        finalRole: String,
        finalContent: String,
        eventLog: LocalSessionEventLog,
    ) {
        val finalMessage = LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = finalRole,
            content = truncateWithoutSplittingSurrogatePair(finalContent, MAX_EVENT_CHARS),
            createdAt = System.currentTimeMillis(),
        )
        val event = eventLog.append(
            if (finalRole == "assistant") "assistant/message" else "system/message",
            buildJsonObject {
                put("automation", true)
                put("content", finalMessage.content)
                put("transcript", encodeTranscriptMessages(listOf(finalMessage)))
            },
        )
        val latest = sessionStorage.coordinator.read(session.id) ?: session
        val appendedTranscript = messages + finalMessage
        val latestWindow = latest.transcriptWindow.ifEmpty {
            latest.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
        }
        sessionStorage.coordinator.enqueue(
            latest.copy(
                title = latest.title.takeIf { it.isNotBlank() && it != "新会话" } ?: session.title,
                updatedAt = System.currentTimeMillis(),
                messages = emptyList(),
                transcriptWindow = (latestWindow + appendedTranscript)
                    .takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES),
                transcriptIndex = appendLocalTranscriptRuntimeIndex(
                    localTranscriptIndexForSession(latest),
                    appendedTranscript,
                ),
                transcriptProjectedThroughSequence = event.sequence,
            ),
        )
    }

    private class LocalWorkAutomationTimeoutBudget(
        timeoutMillis: Long,
        maxMillis: Long,
    ) {
        private val deadlineNanos =
            System.nanoTime() + timeoutMillis.coerceIn(5_000L, maxMillis) * 1_000_000L

        fun remainingMillis(): Long =
            ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)

        suspend fun acquireSession(
            sessionId: String,
            kind: LocalSessionRuntimeKind,
        ): LocalSessionRuntimeLease = withTimeout(remainingMillis()) {
            LocalSessionRuntimeRegistry.acquire(sessionId, kind)
        }
    }

    private fun requireWorkAutomationSession(session: LocalHarnessSession?): LocalHarnessSession {
        val current = session ?: error("后台任务会话已不存在")
        require(current.usageMode == LocalUsageMode.WORK) { "后台任务会话已不在工作模式" }
        return current
    }

}
