package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.model.withWorkRuntimeContext

internal data class LocalWorkTurnPromptContext(
    val stable: String = "",
    val dynamic: String = "",
)

internal fun ContextComposer.composeWorkTurnContext(
    input: String,
    snapshot: LocalHarnessState,
    workspacePath: String,
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
