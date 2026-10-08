package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.context.ContextRequest
import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.model.withWorkRuntimeContext

internal data class LocalWorkTurnPromptContext(
    val stable: String = "",
    val dynamic: String = "",
)

internal fun ContextComposer.composeWorkTurnContext(
    input: String,
    snapshot: LocalHarnessState,
    workspacePath: String,
    projectInstructions: String = "",
): LocalWorkTurnPromptContext {
    val composed = composeParts(
        ContextRequest(
            query = input,
            mode = snapshot.conversationMode,
            projectId = snapshot.projectId,
            lineageId = snapshot.lineageId,
            handoffSummary = snapshot.handoffSummary,
            projectInstructions = projectInstructions,
        ),
    )
    return LocalWorkTurnPromptContext(
        stable = withWorkRuntimeContext(
            composed.stable,
            workspacePath,
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
            snapshot.modelState.modelSelection.activeProfile,
        ),
        dynamic = composed.dynamic,
    )
}
