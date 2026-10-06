package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val postTurnJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private const val DIARY_PROJECTION = "diary_projection"
private const val PROJECTED_EVENT = "chat/post-turn-projected"

/** One authority event commits both the processed cursor and recoverable diary projection input. */
internal fun appendChatPostTurnCommit(
    log: LocalSessionEventLog,
    state: LocalChatProjectionState,
    diary: ChatDiaryWriteRequest?,
): LocalSessionEventLog.Event {
    val data = encodeChatDomainStateEvent(state.toLocalChatDurableState(), "post-turn-updated")
    return log.append(LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE, JsonObject(data +
        if (diary == null) emptyMap() else mapOf(
            DIARY_PROJECTION to postTurnJson.encodeToJsonElement(ChatDiaryWriteRequest.serializer(), diary),
        ),
    ))
}

/** Replay derived diary writes idempotently; a crash before the projection marker cannot duplicate them. */
internal fun recoverChatPostTurnProjections(log: LocalSessionEventLog, store: ChatDiaryStore, checkActive: () -> Unit = {}) {
    val marker = log.latest(PROJECTED_EVENT)
    val lower = maxOf(marker?.sequence ?: -1L,
        marker?.data?.get("through_sequence")?.jsonPrimitive?.longOrNull ?: -1L)
    val upper = log.latestSequence()
    if (upper <= lower) return
    val discarded = hashSetOf<String>()
    fun scan(consume: (LocalSessionEventLog.Event) -> Unit) {
        var cursor = lower
        while (cursor < upper) {
            checkActive()
            val page = log.pageAfter(cursor, 200).filter { it.sequence <= upper }
            if (page.isEmpty()) break
            page.forEach(consume)
            cursor = page.last().sequence
        }
    }
    // An edit may already have rolled back these sources before a missing diary projection resumes.
    scan { event ->
        val rewrite = event.data["_dsh_timeline_rewrite_projection"] as? JsonObject
        rewrite?.get("discardedMessageIds")?.jsonArray?.forEach {
            it.jsonPrimitive.contentOrNull?.let(discarded::add)
        }
    }
    scan { event ->
        if (event.type == LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE &&
            event.data["reason"]?.jsonPrimitive?.contentOrNull == "post-turn-updated") {
            event.data[DIARY_PROJECTION]?.let { encoded ->
                val request = postTurnJson.decodeFromJsonElement(ChatDiaryWriteRequest.serializer(), encoded)
                if ((request.sourceUserMessageIds + request.sourceAssistantMessageIds).none { it in discarded }) {
                    checkActive()
                    store.record(request.copy(projectionId = "${request.sourceSessionId}:${event.sequence}"))
                }
            }
        }
    }
    checkActive()
    log.append(PROJECTED_EVENT, buildJsonObject { put("through_sequence", upper) })
}
