package com.labteto.dshmobile.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val LOCAL_TRANSCRIPT_MIGRATION_BASELINE_EVENT =
    "session/transcript-migration-baseline"
internal const val LOCAL_TRANSCRIPT_MIGRATION_CHUNK_EVENT =
    "session/transcript-migration-chunk"
internal const val LOCAL_TRANSCRIPT_MIGRATION_COMPLETE_EVENT =
    "session/transcript-migration-complete"

internal data class LocalTranscriptSnapshotMigration(
    val window: List<LocalHarnessMessage>,
    val index: LocalTranscriptRuntimeIndex,
    val projectedThroughSequence: Long,
)

/**
 * Move a legacy complete transcript snapshot into Session Event exactly once.
 *
 * A baseline event is written before the chunks. Reverse paging treats that baseline as a hard
 * materialization boundary, so older overlapping semantic events cannot duplicate the migrated
 * transcript. If the process dies after the completion event but before the new slim snapshot is
 * flushed, the next launch reconstructs from the completed event archive instead of writing a
 * second migration.
 */
internal fun migrateLegacyTranscriptSnapshot(
    session: LocalHarnessSession,
    eventLog: LocalSessionEventLog,
    windowSize: Int,
    chunkSize: Int = 200,
): LocalTranscriptSnapshotMigration? {
    if (session.messages.isEmpty() || session.transcriptWindow.isNotEmpty()) return null
    val boundedWindow = windowSize.coerceAtLeast(1)
    val boundedChunk = chunkSize.coerceIn(1, 1_000)

    val existingCompletion = eventLog.latest(LOCAL_TRANSCRIPT_MIGRATION_COMPLETE_EVENT)
    if (existingCompletion != null) {
        val rebuilt = LocalSessionTranscriptPager(eventLog).all()
        return LocalTranscriptSnapshotMigration(
            window = rebuilt.takeLast(boundedWindow),
            index = buildLocalTranscriptRuntimeIndex(rebuilt),
            projectedThroughSequence = eventLog.latestSequence(),
        )
    }

    val source = session.transcriptProjectedThroughSequence?.let { cursor ->
        projectSessionTranscriptTail(
            snapshotMessages = session.messages,
            events = eventLog.snapshotAfter(cursor),
            sequenceExclusive = cursor,
        ).messages
    } ?: session.messages

    val baseline = eventLog.append(
        LOCAL_TRANSCRIPT_MIGRATION_BASELINE_EVENT,
        buildJsonObject {
            put("source", "legacy-session-snapshot")
            put("message_count", source.size)
        },
    )
    source.chunked(boundedChunk).forEachIndexed { index, chunk ->
        eventLog.append(
            LOCAL_TRANSCRIPT_MIGRATION_CHUNK_EVENT,
            buildJsonObject {
                put("baseline_sequence", baseline.sequence)
                put("chunk_index", index)
                put("transcript", encodeTranscriptMessages(chunk))
            },
        )
    }
    val complete = eventLog.append(
        LOCAL_TRANSCRIPT_MIGRATION_COMPLETE_EVENT,
        buildJsonObject {
            put("baseline_sequence", baseline.sequence)
            put("message_count", source.size)
        },
    )

    return LocalTranscriptSnapshotMigration(
        window = source.takeLast(boundedWindow),
        index = buildLocalTranscriptRuntimeIndex(source),
        projectedThroughSequence = complete.sequence,
    )
}
