package com.labteto.dshmobile.local.session

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    val chunkCount = chunkCount(source.size, boundedChunk)
    val resumable = findResumableLegacyMigration(
        source = source,
        chunkSize = boundedChunk,
        eventLog = eventLog,
    )
    val baseline = resumable?.baseline ?: eventLog.append(
        LOCAL_TRANSCRIPT_MIGRATION_BASELINE_EVENT,
        buildJsonObject {
            put("source", "legacy-session-snapshot")
            put("message_count", source.size)
            put("chunk_size", boundedChunk)
            put("first_message_id", source.firstOrNull()?.id.orEmpty())
            put("last_message_id", source.lastOrNull()?.id.orEmpty())
        },
    )
    val nextChunkIndex = resumable?.nextChunkIndex ?: 0
    for (index in nextChunkIndex until chunkCount) {
        val from = index * boundedChunk
        val to = minOf(from + boundedChunk, source.size)
        eventLog.append(
            LOCAL_TRANSCRIPT_MIGRATION_CHUNK_EVENT,
            buildJsonObject {
                put("baseline_sequence", baseline.sequence)
                put("chunk_index", index)
                put("transcript", encodeTranscriptMessages(source.subList(from, to)))
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

private data class ResumableLegacyMigration(
    val baseline: LocalSessionEventLog.Event,
    val nextChunkIndex: Int,
)

private fun findResumableLegacyMigration(
    source: List<LocalHarnessMessage>,
    chunkSize: Int,
    eventLog: LocalSessionEventLog,
): ResumableLegacyMigration? {
    val baseline = eventLog.latest(LOCAL_TRANSCRIPT_MIGRATION_BASELINE_EVENT) ?: return null
    val data = baseline.data
    if (data.intValue("message_count") != source.size) return null
    if (data.intValue("chunk_size") != chunkSize) return null
    if (data.stringValue("first_message_id") != source.firstOrNull()?.id.orEmpty()) return null
    if (data.stringValue("last_message_id") != source.lastOrNull()?.id.orEmpty()) return null

    val chunkCount = chunkCount(source.size, chunkSize)
    val latestChunk = eventLog.latest(LOCAL_TRANSCRIPT_MIGRATION_CHUNK_EVENT)
    if (
        latestChunk == null ||
        latestChunk.sequence <= baseline.sequence ||
        latestChunk.data.longValue("baseline_sequence") != baseline.sequence
    ) {
        return ResumableLegacyMigration(baseline = baseline, nextChunkIndex = 0)
    }

    val latestIndex = latestChunk.data.intValue("chunk_index") ?: return null
    if (latestIndex !in 0 until chunkCount) return null
    return ResumableLegacyMigration(
        baseline = baseline,
        nextChunkIndex = latestIndex + 1,
    )
}

private fun chunkCount(messageCount: Int, chunkSize: Int): Int =
    if (messageCount == 0) 0 else ((messageCount - 1) / chunkSize) + 1

private fun kotlinx.serialization.json.JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun kotlinx.serialization.json.JsonObject.longValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
