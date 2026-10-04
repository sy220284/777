package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.LocalAutomationRunResult
import com.labteto.dshmobile.local.LocalHarnessEngine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Automation execution plus the bounded chat snapshot used by the event planner. */
@Singleton
class LocalAutomationRuntime @Inject constructor(
    private val engine: LocalHarnessEngine,
) {
    internal val planningRevisions = engine.state.map { it.toAutomationPlanningRevision() }.distinctUntilChanged()
    internal fun planningContext(): AutomationPlanningContext {
        val snapshot = engine.state.value
        return AutomationPlanningContext(
            sessionId = snapshot.sessionId,
            revision = snapshot.toAutomationPlanningRevision(),
            configured = snapshot.configured,
            usageMode = snapshot.usageMode,
            groupChatEnabled = snapshot.groupChat.enabled,
            model = snapshot.model,
            baseUrl = snapshot.baseUrl,
            profileId = snapshot.modelSelection.activeProfileId,
            personaName = snapshot.chatPersona.name,
            recentMessages = snapshot.messages
                .asSequence()
                .filter { it.role == "user" || it.role == "assistant" }
                .filter { it.content.isNotBlank() }
                .toList()
                .takeLast(12)
                .map { AutomationPlanningMessage(it.role, it.content) },
        )
    }
    internal fun planningRevision(): AutomationPlanningRevision =
        engine.state.value.toAutomationPlanningRevision()
    internal suspend fun runPrompt(text: String, timeoutMillis: Long = 5 * 60_000L): String =
        engine.runAutomationPrompt(text, timeoutMillis)
    internal suspend fun prepareWorkSession(text: String, preferredSessionId: String? = null): String =
        engine.prepareAutomationWorkSession(text, preferredSessionId)
    internal suspend fun runWork(
        text: String,
        preferredSessionId: String? = null,
        timeoutMillis: Long = 5 * 60_000L,
        recoverInterrupted: Boolean = false,
    ): LocalAutomationRunResult = engine.runAutomationWork(
        text = text,
        preferredSessionId = preferredSessionId,
        timeoutMillis = timeoutMillis,
        recoverInterrupted = recoverInterrupted,
    )
    internal suspend fun runChat(
        instruction: String,
        targetSessionId: String,
        timeoutMillis: Long = 3 * 60_000L,
        recoverInterrupted: Boolean = false,
        recoveryStartedAt: Long? = null,
        quietHoursEnabled: Boolean = false,
        quietStartHour: Int = 23,
        quietStartMinute: Int = 0,
        quietEndHour: Int = 7,
        quietEndMinute: Int = 0,
        proactiveMinGapMinutes: Long = 6L * 60L,
        proactiveMaxUnanswered: Int = 2,
        minimumSilenceMinutes: Long? = null,
        silenceReferenceAt: Long? = null,
        bypassProactivePolicy: Boolean = false,
    ): LocalAutomationRunResult = engine.runAutomationChat(
        instruction, targetSessionId, timeoutMillis, recoverInterrupted, recoveryStartedAt,
        quietHoursEnabled, quietStartHour, quietStartMinute, quietEndHour, quietEndMinute,
        proactiveMinGapMinutes, proactiveMaxUnanswered, minimumSilenceMinutes,
        silenceReferenceAt, bypassProactivePolicy,
    )
}

