package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalAutomationWorkException
import com.labteto.dshmobile.local.runtime.LocalHarnessBlockedException
import com.labteto.dshmobile.local.chat.LocalChatAutomationExecutionPort
import com.labteto.dshmobile.local.chat.LocalChatAutomationPolicy
import com.labteto.dshmobile.local.work.LocalWorkAutomationExecutionPort
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Automation execution plus the bounded chat snapshot used by the event planner. */
@Singleton
class LocalAutomationRuntime @Inject internal constructor(
    private val chatExecution: LocalChatAutomationExecutionPort,
    private val workExecution: LocalWorkAutomationExecutionPort,
    private val runtimeStateStore: LocalRuntimeStateStore,
) {
    private val runtimeState = runtimeStateStore.state
    internal val planningRevisions = runtimeState.map { it.toAutomationPlanningRevision() }.distinctUntilChanged()


    internal fun planningContext(): AutomationPlanningContext =
        runtimeState.value.toAutomationPlanningContext()

    internal fun planningRevision(): AutomationPlanningRevision =
        runtimeState.value.toAutomationPlanningRevision()

    internal suspend fun runPrompt(text: String, timeoutMillis: Long = 5 * 60_000L): String {
        val result = runWork(text = text, timeoutMillis = timeoutMillis)
        return when (result.status) {
            LocalAutomationRunStatus.DELIVERED, LocalAutomationRunStatus.SKIPPED -> result.output
            LocalAutomationRunStatus.BLOCKED -> throw LocalHarnessBlockedException(
                result.detail ?: result.output,
                result.sessionId,
            )
            LocalAutomationRunStatus.CANCELLED -> throw CancellationException(
                result.detail ?: result.output,
            )
            LocalAutomationRunStatus.FAILED -> throw LocalAutomationWorkException(
                result.detail ?: result.output,
                result.sessionId,
            )
        }
    }

    internal suspend fun <T> withModelRequestResource(block: suspend () -> T): T =
        runtimeStateStore.withModelRequestResource(block)


    internal suspend fun prepareWorkSession(text: String, preferredSessionId: String? = null): String =
        workExecution.prepareSession(text, preferredSessionId)
    internal suspend fun runWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult = workExecution.run(
        text = text,
        preferredSessionId = preferredSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
    ).let { result ->
        LocalAutomationRunResult(
            sessionId = result.sessionId,
            output = result.output,
            status = result.status,
            detail = result.detail,
        )
    }
    internal suspend fun runChat(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long = 3 * 60_000L,
        recoverInterrupted: Boolean = false,
        recoveryStartedAt: Long? = null,
        policy: LocalChatAutomationPolicy = LocalChatAutomationPolicy(),
    ): LocalAutomationRunResult = chatExecution.run(
        instruction = instruction,
        targetSessionId = targetSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
        recoveryStartedAt = recoveryStartedAt,
        policy = policy,
    ).let { result ->
        LocalAutomationRunResult(
            sessionId = result.sessionId,
            output = result.output,
            status = result.status,
            detail = result.detail,
            nextRunAtHint = result.nextRunAtHint,
            waitingForUserReply = result.waitingForUserReply,
        )
    }
}
