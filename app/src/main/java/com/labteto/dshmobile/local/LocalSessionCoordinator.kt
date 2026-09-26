package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class LocalSessionTranscriptRestore(
    val messages: List<LocalHarnessMessage>,
    val index: LocalTranscriptRuntimeIndex,
    val projectedThroughSequence: Long,
    val needsPersist: Boolean,
)

/**
 * Owns local Session snapshot/projection boundaries.
 *
 * Complete transcript facts live in Session Event. The coordinator exposes only the bounded runtime
 * window and disposable snapshot acceleration state to LocalHarnessEngine.
 */
internal class LocalSessionCoordinator(
    private val repository: LocalSessionRepository,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val runtimeWindowMessages: Int,
) {
    fun read(id: String): LocalHarnessSession? = repository.read(id)

    fun readWithLegacyApproval(id: String): LocalSessionRead? =
        repository.readWithLegacyApproval(id)

    fun enqueue(snapshot: LocalHarnessSession) = repository.enqueue(snapshot)

    fun delete(id: String): Boolean = repository.delete(id)

    fun summaries(): List<LocalSessionSummary> = repository.summaries()

    fun restoreTranscript(
        stored: LocalHarnessSession,
        persistedSnapshotExists: Boolean,
    ): LocalSessionTranscriptRestore {
        val eventLog = eventLogFor(stored.id)
        val migration = if (persistedSnapshotExists) {
            migrateLegacyTranscriptSnapshot(
                session = stored,
                eventLog = eventLog,
                windowSize = runtimeWindowMessages,
            )
        } else {
            null
        }
        val legacyBaseline = if (
            migration == null &&
            stored.transcriptProjectedThroughSequence == null &&
            persistedSnapshotExists
        ) {
            eventLog.latest(LEGACY_TRANSCRIPT_PROJECTION_BASELINE_EVENT)?.sequence
                ?: eventLog.append(
                    LEGACY_TRANSCRIPT_PROJECTION_BASELINE_EVENT,
                    buildJsonObject { put("source", "legacy-session-snapshot") },
                ).sequence
        } else {
            null
        }
        val cursor = migration?.projectedThroughSequence
            ?: transcriptProjectionReplayCursor(
                snapshot = stored,
                persistedSnapshotExists = persistedSnapshotExists,
                legacyBaselineSequence = legacyBaseline,
            )
        val baseWindow = when {
            migration != null -> migration.window
            stored.transcriptWindow.isNotEmpty() -> stored.transcriptWindow
                .takeLast(runtimeWindowMessages)
            else -> stored.messages.takeLast(runtimeWindowMessages)
        }
        val tailEvents = eventLog.snapshotAfter(cursor)
        val projected = projectSessionTranscriptTail(
            snapshotMessages = baseWindow,
            events = tailEvents,
            sequenceExclusive = cursor,
            maxMessages = runtimeWindowMessages,
        )
        val indexBase = when {
            migration != null -> migration.index
            stored.transcriptIndex.totalMessageCount > 0L -> stored.transcriptIndex
            stored.messages.isNotEmpty() -> buildLocalTranscriptRuntimeIndex(stored.messages)
            stored.transcriptWindow.isNotEmpty() -> buildLocalTranscriptRuntimeIndex(stored.transcriptWindow)
            else -> LocalTranscriptRuntimeIndex()
        }
        val projectedIndex = projectLocalTranscriptRuntimeIndexTail(
            snapshot = indexBase,
            events = tailEvents,
            sequenceExclusive = cursor,
        )
        return LocalSessionTranscriptRestore(
            messages = projected.messages,
            index = projectedIndex,
            projectedThroughSequence = projected.projectedThroughSequence,
            needsPersist = migration != null ||
                legacyBaseline != null ||
                stored.messages.isNotEmpty() ||
                stored.transcriptWindow != projected.messages ||
                stored.transcriptIndex != projectedIndex ||
                projected.projectedThroughSequence != stored.transcriptProjectedThroughSequence,
        )
    }

    fun snapshot(
        sessionId: String,
        state: LocalHarnessState,
        controlProjectedThroughSequence: Long,
        transcriptProjectedThroughSequence: Long?,
    ): LocalHarnessSession = LocalHarnessSession(
        id = sessionId,
        title = state.transcriptIndex.firstUserTitle ?: "新会话",
        updatedAt = System.currentTimeMillis(),
        usageMode = state.usageMode,
        personaId = state.personaId,
        chatState = state.chatState,
        replySuggestions = state.replySuggestions,
        chatBranches = state.chatBranches,
        groupChat = state.groupChat,
        galleryId = state.galleryId,
        galleryStoryId = state.galleryStoryId,
        gallerySaveSuppressedThrough = state.gallerySaveSuppressedThrough,
        conversationMode = state.conversationMode,
        parentSessionId = state.parentSessionId,
        lineageId = state.lineageId,
        projectId = state.projectId,
        handoffSummary = state.handoffSummary,
        messages = emptyList(),
        transcriptWindow = state.messages.takeLast(runtimeWindowMessages),
        transcriptIndex = state.transcriptIndex,
        plan = state.plan,
        todos = state.todos,
        goal = state.goal,
        planMode = state.planMode,
        controlProjectedThroughSequence = controlProjectedThroughSequence,
        transcriptProjectedThroughSequence = transcriptProjectedThroughSequence,
    )

    private companion object {
        const val LEGACY_TRANSCRIPT_PROJECTION_BASELINE_EVENT =
            "session/transcript-projection-baseline"
    }
}
