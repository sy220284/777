package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.ConversationSnapshot
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SubagentListEntry
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.UnknownSubagentListEntry
import com.labteto.dshmobile.core.wire.newPromptRequestId
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Owns the connected session's subagent list, commands and child-transcript projection. */
internal class SessionSubagentRuntime(
    private val apiForHost: (String?) -> DshApiClient?,
    private val currentSessionId: () -> String?,
    private val activeHostKey: () -> String?,
    private val remoteStreams: SessionRemoteStreamCoordinator,
    private val onConnectionError: (String?) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val _subagents = MutableStateFlow<List<SubagentListEntry>>(emptyList())
    val subagents: StateFlow<List<SubagentListEntry>> = _subagents.asStateFlow()

    private val _conversation = MutableStateFlow<ConversationSnapshot?>(null)
    val conversation: StateFlow<ConversationSnapshot?> = _conversation.asStateFlow()

    private val _mode = MutableStateFlow<String?>(null)
    val mode: StateFlow<String?> = _mode.asStateFlow()

    fun resetSession() {
        remoteStreams.cancelSubagentFollow()
        _subagents.value = emptyList()
        _conversation.value = null
        _mode.value = null
    }

    suspend fun refresh() {
        val parentSessionId = currentSessionId() ?: return
        val scope = SessionAsyncScope(activeHostKey(), parentSessionId)
        val api = apiForHost(scope.hostKey) ?: return
        when (val result = api.subagentList(parentSessionId)) {
            is RpcResult.Ok -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                _subagents.value = result.value.entries
            }
            is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                onConnectionError(result.error.message)
            }
        }
    }

    suspend fun interrupt(childSessionId: String) {
        val parentSessionId = currentSessionId() ?: return
        val scope = SessionAsyncScope(activeHostKey(), parentSessionId)
        val api = apiForHost(scope.hostKey) ?: return
        when (
            val result = api.subagentInterrupt(
                childSessionId = childSessionId,
                parentSessionId = parentSessionId,
            )
        ) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> if (scope.isCurrent(activeHostKey, currentSessionId)) {
                onConnectionError(result.error.message)
            }
        }
    }

    suspend fun prompt(
        childSessionId: String,
        text: String,
        delivery: String = "queue",
    ): Boolean {
        val parentSessionId = currentSessionId() ?: return false
        val scope = SessionAsyncScope(activeHostKey(), parentSessionId)
        val api = apiForHost(scope.hostKey) ?: return false
        val request = SubagentPromptRequest(
            requestId = newPromptRequestId(),
            parentSessionId = parentSessionId,
            childSessionId = childSessionId,
            mode = "continuable",
            delivery = delivery,
            content = listOf(PromptContentPart.Text(text)),
            clientTimeZone = TimeZone.getDefault().id,
        )
        return when (val result = api.subagentPrompt(request)) {
            is RpcResult.Ok -> true
            is RpcResult.Err -> {
                if (scope.isCurrent(activeHostKey, currentSessionId)) onConnectionError(result.error.message)
                false
            }
        }
    }

    suspend fun openTranscript(childSessionId: String) {
        val parentSessionId = currentSessionId() ?: return
        val hostKey = activeHostKey() ?: return
        apiForHost(hostKey) ?: return
        val entry = _subagents.value.firstOrNull { entryId(it) == childSessionId }
        val transcriptMode = when (entry) {
            is SubagentListEntry.ChildOneShot -> "one-shot"
            is SubagentListEntry.ChildContinuable -> "continuable"
            else -> null
        }
        _mode.value = transcriptMode
        if (transcriptMode == null) {
            _conversation.value = null
            logger("subagent $childSessionId has no readable transcript mode", null)
            return
        }

        remoteStreams.cancelSubagentFollow()
        _conversation.value = null
        val events = mutableListOf<SessionEventEnvelope>()
        val live = AssistantLiveState()
        var hasMore = false
        val opened = remoteStreams.followSubagent(
            parentSessionId = parentSessionId,
            childSessionId = childSessionId,
            mode = transcriptMode,
            maxMessages = HISTORY_PAGE_SIZE,
        ) { frame ->
            if (activeHostKey() != hostKey || currentSessionId() != parentSessionId) return@followSubagent
            when (frame) {
                is SessionFollowFrame.Snapshot -> {
                    events.clear()
                    events.addAll(expandRecords(frame.records))
                    live.seed(frame.assistantStream)
                    hasMore = frame.hasMore
                }
                is SessionFollowFrame.Entry -> expandRecords(listOf(frame.record)).forEach { event ->
                    if (events.none { it.seq == event.seq }) events.add(event)
                    val data = event.data as? JsonObject
                    live.acceptDurable(
                        event.type,
                        data?.get("turn")?.jsonPrimitive?.intOrNull,
                        data?.get("step")?.jsonPrimitive?.intOrNull,
                        event.seq,
                        event.surfaceOp,
                    )
                }
                is SessionFollowFrame.AssistantStream -> live.accept(frame.frame)
            }
            _conversation.value = EventFold(childSessionId)
                .fold(events.sortedBy { it.seq }, live.transientEnvelopes())
                .copy(hasMore = hasMore)
        }
        if (!opened) {
            logger("cannot follow subagent $childSessionId: no connection generation", null)
        }
    }

    fun closeTranscript() {
        remoteStreams.cancelSubagentFollow()
    }

    private fun expandRecords(records: List<com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord>): List<SessionEventEnvelope> =
        ChunkRows.expandAll(records).map { wireEventToEnvelope(it) }

    private fun entryId(entry: SubagentListEntry): String? = when (entry) {
        is SubagentListEntry.ChildOneShot -> entry.id
        is SubagentListEntry.ChildContinuable -> entry.id
        is SubagentListEntry.Diagnostic -> entry.id
        is UnknownSubagentListEntry -> null
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 60
    }
}
