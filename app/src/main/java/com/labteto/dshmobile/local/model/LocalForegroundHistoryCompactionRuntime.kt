package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared owner of durable compaction for the visible foreground model history.
 *
 * Detached Work bindings keep their own session-bound persistence callback. Chat and every other
 * visible foreground caller use this one shared history/cursor/Session projection.
 */
@Singleton
internal class LocalForegroundHistoryCompactionRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    private val compactor = LocalHistoryCompactor()
    private val checkpointCodec = ModelHistoryCheckpointCodec()

    internal fun persistOverflowCompaction(
        sessionId: String,
        summaryMode: LocalHistorySummaryMode,
    ) {
        val current = runtimeStateStore.state.value
        if (current.sessionId != sessionId) return

        val history = runtimeStateStore.foregroundRunHandle.modelHistory
        val eventLog = sessionStorage.eventLogs.get(sessionId)
        val sourceAsOfSequence = eventLog.latestSequence()
        val sourceCheckpointSequence = eventLog.latest(ModelHistoryCheckpointCodec.EVENT_TYPE)?.sequence
        val sourceMessageCount = history.snapshot().size
        val compaction = history.compactOverflow(
            compactor = compactor,
            summaryMode = summaryMode,
        ) ?: return

        runtimeStateStore.requestPressureStore.advanceGeneration(
            sessionId,
            compaction.estimatedTokensAfter,
        )
        eventLog.append("session/compaction", buildJsonObject {
            put("version", 2)
            put("trigger", "context-overflow")
            put("source_as_of_sequence", sourceAsOfSequence)
            sourceCheckpointSequence?.let { put("source_checkpoint_sequence", it) }
            put("source_message_count", sourceMessageCount)
            put("result_message_count", history.snapshot().size)
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("summary_digest", stableJsonSha256(JsonPrimitive(compaction.summary)))
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
        eventLog.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            checkpointCodec.encode(
                messages = durableModelHistorySnapshot(history.snapshot()),
                reason = "session/context-overflow",
                asOfSequence = eventLog.latestSequence(),
            ),
        )
        runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint = 0

        val resources = runtimeStateStore.resourceSnapshot()
        runtimeStateStore.projection.updateContextMetrics(
            sessionId = sessionId,
            contextChars = history.encodedChars,
            contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(
                runtimeStateStore.state.value,
                resources,
            ),
        )
        check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
            "前台模型历史压缩完成后会话已切换"
        }
    }
}
