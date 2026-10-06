package com.labteto.dshmobile.local.session

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
 * window and disposable snapshot acceleration state to the process lifecycle owner.
 */
internal class LocalSessionCoordinator(
    private val repository: LocalSessionRepository,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val runtimeWindowMessages: Int,
    private val domainCodecs: List<LocalSessionDomainCodec>,
) {
    fun read(id: String): LocalHarnessSession? =
        repository.read(id)?.let(::normalizeLoaded)

    fun readWithLegacyApproval(id: String): LocalSessionRead? =
        repository.readWithLegacyApproval(id)?.let { loaded ->
            loaded.copy(session = normalizeLoaded(loaded.session))
        }

    fun enqueue(snapshot: LocalHarnessSession) = repository.enqueue(snapshot)

    suspend fun writeNow(snapshot: LocalHarnessSession) = repository.writeNow(snapshot)

    fun delete(id: String): Boolean = repository.delete(id)

    fun releaseDeletionBarrier(ids: Set<String>) = repository.releaseDeletionBarrier(ids)

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
        ).copy(
            // Old builds could persist false forever. Current edit/variant actions validate the
            // actual active transcript at use time, so old storage must not disable them.
            branchingEligible = true,
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

    private fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession =
        normalizeLocalSessionDomains(session, domainCodecs)

    private companion object {
        const val LEGACY_TRANSCRIPT_PROJECTION_BASELINE_EVENT =
            "session/transcript-projection-baseline"
    }
}
