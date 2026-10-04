package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.context.ContextComposer
import com.labteto.dshmobile.local.context.ContextRequest

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
            snapshot.model,
            snapshot.baseUrl,
            snapshot.modelSelection.activeProfile,
        ),
        dynamic = composed.dynamic,
    )
}
