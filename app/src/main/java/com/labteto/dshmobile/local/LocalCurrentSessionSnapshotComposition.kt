package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.withChatSessionDomain
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalCurrentSessionSnapshotProvider
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionSnapshotBoundary
import com.labteto.dshmobile.local.work.withWorkSessionDomain
import javax.inject.Inject
import javax.inject.Singleton

/** App composition adapter that materializes Feature-owned state into the neutral Session envelope. */
@Singleton
internal class LocalCurrentSessionSnapshotComposition @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
) : LocalCurrentSessionSnapshotProvider {
    override fun snapshot(
        expectedSessionId: String,
        boundary: LocalSessionSnapshotBoundary,
        runtimeWindowMessages: Int,
    ): LocalHarnessSession? {
        val state = runtimeStateStore.state.value
        if (state.sessionId != expectedSessionId) return null
        return LocalHarnessSession(
            id = expectedSessionId,
            title = state.transcriptIndex.firstUserTitle ?: "新会话",
            updatedAt = state.transcriptIndex.latestCreatedAt.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            usageMode = state.usageMode,
            personaId = state.chat.personaId,
            galleryId = state.chat.galleryId,
            galleryStoryId = state.chat.galleryStoryId,
            gallerySaveSuppressedThrough = state.chat.gallerySaveSuppressedThrough,
            conversationMode = state.conversationMode,
            parentSessionId = state.parentSessionId,
            lineageId = state.lineageId,
            projectId = state.projectId,
            handoffSummary = state.handoffSummary,
            messages = emptyList(),
            transcriptWindow = state.messages.takeLast(runtimeWindowMessages),
            transcriptIndex = state.transcriptIndex,
            plan = state.work.plan,
            planMode = state.work.planMode,
            controlProjectedThroughSequence = boundary.controlProjectedThroughSequence,
            transcriptProjectedThroughSequence = boundary.transcriptProjectedThroughSequence,
        ).withChatSessionDomain(state.chat).withWorkSessionDomain(state.work)
    }
}
