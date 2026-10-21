package com.labteto.dshmobile.local.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve the original snapshot on an impossible event cursor rather than replaying side effects. */
internal class SessionProjectionCursorAheadException(message: String) : IllegalStateException(message)

/**
 * Validate both snapshot projections before repair and legacy migration mutate event history.
 * A cursor < -1 is as unsafe as one ahead of the durable tail: it can replay known side effects.
 */
internal fun requireSessionSnapshotCursorsWithinLog(
    stored: LocalHarnessSession,
    latestSequence: Long,
) {
    for ((name, cursor) in listOf(
        "控制状态" to stored.controlProjectedThroughSequence,
        "对话历史" to stored.transcriptProjectedThroughSequence,
    )) {
        if (cursor != null && (cursor < -1L || cursor > latestSequence)) {
            throw SessionProjectionCursorAheadException(
                "$name 投影游标 $cursor 超出事件日志有效范围 -1..$latestSequence；已停止自动恢复以保护历史",
            )
        }
    }
}

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
        // Validate before legacy migration: migration itself appends authoritative events.
        // A stale snapshot must never mutate the archive before the cursor mismatch is reported.
        requireSessionSnapshotCursorsWithinLog(stored, eventLog.latestSequence())
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
        val durableLatest = eventLog.latestSequence()
        if (cursor < -1L || cursor > durableLatest) {
            throw SessionProjectionCursorAheadException(
                "会话投影游标 $cursor 超出事件日志末尾 $durableLatest；已停止自动恢复以保护历史",
            )
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
