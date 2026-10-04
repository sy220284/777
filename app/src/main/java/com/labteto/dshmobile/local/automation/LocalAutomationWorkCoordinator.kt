package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaProfile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.labteto.dshmobile.local.model.LocalModelRunContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Owns durable Work-automation execution and transcript persistence.
 *
 * Automation stays detached from the visible UI session; the engine only supplies the shared
 * runner factory and durable session/event boundaries.
 */
internal class LocalAutomationWorkCoordinator(
    private val state: StateFlow<LocalHarnessState>,
    private val sessionCoordinator: LocalSessionCoordinator,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val agentRunCoordinator: LocalAgentRunCoordinator,
    private val runnerFactory: (
        sessionId: String,
        boundState: LocalHarnessState,
        onApprovalBlocked: (String) -> Unit,
    ) -> LocalSubagentRunner,
    private val onSessionReleased: (String) -> Unit = {},
) {
    suspend fun prepareWorkSession(
        text: String,
        preferredSessionId: String? = null,
    ): String {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "后台任务提示词不能为空" }
        awaitReady()
        return resolveSession(preferredSessionId, prompt).id
    }

    suspend fun runWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult {
        val prompt = text.trim()
        require(prompt.isNotEmpty()) { "后台任务提示词不能为空" }
        awaitReady()

        val sessionId = resolveSession(preferredSessionId, prompt).id
        val budget = LocalAutomationTimeoutBudget(timeoutMillis, 15 * 60_000L)
        val sessionLease = budget.acquireSession(sessionId, LocalSessionRuntimeKind.AUTOMATION_WORK)
        try {
        // Re-read after ownership so deletion cannot be undone by a stale preflight snapshot.
        val session = requireAutomationWorkSession(sessionCoordinator.read(sessionId))
        val boundState = boundState(session)
        val boundEventLog = eventLogFor(sessionId)
        val recovery = if (recoverInterrupted) {
            prepareAutomationWorkRecovery(
                prompt = prompt,
                sessionId = sessionId,
                eventLog = boundEventLog,
                profiles = state.value.modelProfiles,
                agentRunCoordinator = agentRunCoordinator,
            )
        } else {
            LocalAutomationWorkRecoveryPlan(prompt)
        }
        recovery.completedOutput?.let { output ->
            ensureRecoveredTranscript(
                session = session,
                output = output,
                eventLog = boundEventLog,
            )
            return LocalAutomationRunResult(sessionId = sessionId, output = output)
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
        val runner = runnerFactory(
            sessionId,
            boundState,
        ) { reason ->
            if (blockedReason == null) blockedReason = reason
        }

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
            val output = result.requireCompletedOutput().ifBlank { "后台任务已完成" }

            blockedReason?.let { reason ->
                persistTranscript(
                    session = session,
                    messages = listOf(userMessage),
                    finalRole = "system",
                    finalContent = reason,
                    eventLog = boundEventLog,
                )
                throw LocalHarnessBlockedException(reason, sessionId)
            }

            persistTranscript(
                session = session,
                messages = listOf(userMessage),
                finalRole = "assistant",
                finalContent = output,
                eventLog = boundEventLog,
            )
            LocalAutomationRunResult(sessionId = sessionId, output = output)
        } catch (blocked: LocalHarnessBlockedException) {
            throw blocked
        } catch (timeout: TimeoutCancellationException) {
            val detail = "后台任务执行超时，已停止本轮任务"
            persistTranscript(
                session = session,
                messages = listOf(userMessage),
                finalRole = "system",
                finalContent = detail,
                eventLog = boundEventLog,
            )
            throw LocalAutomationWorkException(detail, sessionId, timeout)
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
            throw LocalAutomationWorkException(detail, sessionId, error)
        }
        } finally {
            sessionLease.close()
            onSessionReleased(sessionId)
        }
    }

    private suspend fun awaitReady() {
        withTimeout(15_000L) {
            while (state.value.loading) delay(50)
        }
        require(state.value.configured) { "本机 Harness 尚未配置模型" }
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
        sessionCoordinator.enqueue(session)
        return session
    }

    private fun boundState(session: LocalHarnessSession): LocalHarnessState {
        val runtime = state.value
        val recentTranscript = LocalSessionTranscriptPager(eventLogFor(session.id))
            .page(limit = LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
            .messages
            .ifEmpty {
                session.transcriptWindow
                    .ifEmpty { session.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES) }
            }
        return runtime.copy(
            sessionId = session.id,
            usageMode = LocalUsageMode.WORK,
            personaId = PersonaProfile.DEFAULT_PERSONA_ID,
            galleryId = null,
            galleryStoryId = null,
            chatPersona = PersonaProfile(),
            chatState = ChatCharacterState(),
            replySuggestions = emptyList(),
            conversationMode = session.conversationMode,
            parentSessionId = session.parentSessionId,
            lineageId = session.lineageId.ifBlank { session.id },
            projectId = session.projectId ?: LOCAL_PROJECT_ID,
            handoffSummary = session.handoffSummary,
            messages = recentTranscript,
            transcriptIndex = localTranscriptIndexForSession(session),
            plan = session.plan,
            todos = session.todos,
            goal = session.goal,
            planMode = false,
            jobs = emptyList(),
            queuedInputCount = 0,
            running = false,
            pendingApproval = null,
            pendingQuestion = null,
            error = null,
        )
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
        val latest = sessionCoordinator.read(session.id) ?: session
        val appendedTranscript = messages + finalMessage
        val latestWindow = latest.transcriptWindow.ifEmpty {
            latest.messages.takeLast(LOCAL_TRANSCRIPT_RUNTIME_WINDOW_MESSAGES)
        }
        sessionCoordinator.enqueue(
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
}
