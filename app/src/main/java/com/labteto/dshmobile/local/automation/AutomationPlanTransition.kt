package com.labteto.dshmobile.local.automation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.harness.state.RuntimeStateTransition
import com.labteto.dshmobile.harness.state.RuntimeStateTransitionPolicy
import com.labteto.dshmobile.harness.state.acceptRuntimeStateTransition
import com.labteto.dshmobile.harness.state.rejectRuntimeStateTransition

internal data class AutomationPlanningRevision(
    val sessionId: String,
    val profileId: String? = null,
    val usageMode: LocalUsageMode,
    val groupChatEnabled: Boolean,
    val personaId: String,
    val galleryId: String?,
    val galleryStoryId: String?,
    val latestDialogueMessageId: String?,
    val chatContextGeneration: Long,
)

internal fun LocalHarnessState.toAutomationPlanningRevision() = AutomationPlanningRevision(
    sessionId = sessionId,
    profileId = modelState.modelSelection.activeProfileId,
    usageMode = usageMode,
    groupChatEnabled = groupChat.enabled,
    personaId = personaId,
    galleryId = galleryId,
    galleryStoryId = galleryStoryId,
    latestDialogueMessageId = transcriptIndex.latestDialogueMessageId,
    chatContextGeneration = chatContext.generation,
)

private val AUTOMATION_PLANNING_REVISION_POLICY =
    RuntimeStateTransitionPolicy<AutomationPlanningRevision> { current, candidate ->
        if (current == candidate) {
            acceptRuntimeStateTransition(candidate)
        } else {
            rejectRuntimeStateTransition(
                current,
                "事件规划期间聊天状态已经变化，请基于最新对话重新生成",
            )
        }
    }

internal fun resolveAutomationPlanningRevision(
    current: AutomationPlanningRevision,
    candidate: AutomationPlanningRevision,
): RuntimeStateTransition<AutomationPlanningRevision> =
    AUTOMATION_PLANNING_REVISION_POLICY.resolve(current, candidate)

internal fun LocalHarnessState.toAutomationPlanningContext() = AutomationPlanningContext(
    sessionId = sessionId,
    revision = toAutomationPlanningRevision(),
    configured = modelState.configured,
    usageMode = usageMode,
    groupChatEnabled = groupChat.enabled,
    model = modelState.model,
    baseUrl = modelState.baseUrl,
    profileId = modelState.modelSelection.activeProfileId,
    personaName = chatPersona.name,
    recentMessages = messages
        .asSequence()
        .filter { it.role == "user" || it.role == "assistant" }
        .filter { it.content.isNotBlank() }
        .toList()
        .takeLast(12)
        .map { AutomationPlanningMessage(it.role, it.content) },
)
