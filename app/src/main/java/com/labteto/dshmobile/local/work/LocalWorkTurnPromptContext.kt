package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.context.ContextRequest
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.model.workRuntimeContextParts

internal data class LocalWorkTurnPromptContext(
    val stable: String = "",
    val dynamic: String = "",
    val skill: String = "",
)

internal fun ContextComposer.composeWorkTurnContext(
    input: String,
    snapshot: LocalHarnessState,
    workspacePath: String,
    skillGuidance: String = "",
): LocalWorkTurnPromptContext {
    val composed = composeParts(
        ContextRequest(
            query = input,
            mode = snapshot.conversationMode,
            projectId = snapshot.projectId,
            lineageId = snapshot.lineageId,
            handoffSummary = snapshot.handoffSummary,
        ),
    )
    val runtime = workRuntimeContextParts(
        context = composed.stable,
        workspacePath = workspacePath,
        model = snapshot.modelState.model,
        baseUrl = snapshot.modelState.baseUrl,
        profile = snapshot.modelState.modelSelection.activeProfile,
        configuredProfiles = snapshot.modelState.modelSelection.profiles,
        task = input,
    )
    return LocalWorkTurnPromptContext(
        stable = runtime.stable,
        dynamic = listOf(runtime.dynamic, composed.dynamic)
            .filter(String::isNotBlank).joinToString("\n\n"),
        skill = skillGuidance,
    )
}
