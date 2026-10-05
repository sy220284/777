package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_TIMELINE_REWRITE_ID_KEY = "_dsh_timeline_rewrite_id"
internal const val LOCAL_TIMELINE_REWRITE_MODEL_HISTORY_KEY = "_dsh_timeline_rewrite_model_history"
internal const val LOCAL_TIMELINE_REWRITE_STATE_KEY = "_dsh_timeline_rewrite_state"
private const val LOCAL_TIMELINE_REWRITE_PROJECTION_KEY = "_dsh_timeline_rewrite_projection"
private const val LOCAL_TIMELINE_REWRITE_PROJECTION_COMMITTED = "chat/timeline-rewrite-projection-committed"

@Serializable
internal data class LocalTimelineRewriteState(
    val plan: List<String>,
    val todos: List<LocalTodoItem>,
    val goal: LocalGoal?,
    val planMode: Boolean,
    val chatState: ChatCharacterState,
    val chatContext: ChatContextState,
    val chatBranches: LocalChatBranchState,
    val groupChat: LocalGroupChatState,
)

@Serializable
private data class LocalDirectGalleryRewrite(
    val galleryId: String,
    val storyId: String,
    val messageKeys: List<String>,
    val replacementChatState: ChatCharacterState,
)

@Serializable
private data class LocalGroupGalleryRewrite(
    val galleryId: String,
    val replacementChatState: ChatCharacterState,
)

@Serializable
private data class LocalTimelineRewriteProjectionPlan(
    val sourceSessionId: String,
    val createdAtInclusive: Long,
    val discardedMessageIds: List<String>,
    val directGallery: LocalDirectGalleryRewrite? = null,
    val groupGallery: List<LocalGroupGalleryRewrite> = emptyList(),
)

internal data class LocalTimelineRewriteProjectionInput(
    val sourceSessionId: String,
    val createdAtInclusive: Long,
    val discardedMessageIds: List<String>,
    val directGalleryId: String? = null,
    val directStoryId: String? = null,
    val directMessageKeys: List<String> = emptyList(),
    val directReplacementChatState: ChatCharacterState? = null,
    val groupGalleryStates: List<Pair<String, ChatCharacterState>> = emptyList(),
)

private val timelineRewriteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun appendTimelineRewriteCommit(
    eventLog: LocalSessionEventLog,
    reason: String,
    activeTranscript: List<LocalHarnessMessage>,
    modelHistory: List<JsonObject>,
    state: LocalTimelineRewriteState,
    projection: LocalTimelineRewriteProjectionInput,
    editedMessageId: String,
    editedModelMessage: JsonObject,
): LocalSessionEventLog.Event {
    val rewriteId = UUID.randomUUID().toString()
    val directGallery = projection.directGalleryId?.let { galleryId ->
        val storyId = requireNotNull(projection.directStoryId)
        val replacement = requireNotNull(projection.directReplacementChatState)
        LocalDirectGalleryRewrite(
            galleryId = galleryId,
            storyId = storyId,
            messageKeys = projection.directMessageKeys,
            replacementChatState = replacement,
        )
    }
    val plan = LocalTimelineRewriteProjectionPlan(
        sourceSessionId = projection.sourceSessionId,
        createdAtInclusive = projection.createdAtInclusive,
        discardedMessageIds = projection.discardedMessageIds.distinct(),
        directGallery = directGallery,
        groupGallery = projection.groupGalleryStates.map { (galleryId, chatState) ->
            LocalGroupGalleryRewrite(galleryId, chatState)
        },
    )
    return eventLog.append("chat/active-transcript", buildJsonObject {
        put("reason", reason)
        put("transcript", encodeTranscriptMessages(activeTranscript))
        put(LOCAL_TIMELINE_REWRITE_ID_KEY, rewriteId)
        put("edited_message_id", editedMessageId)
        put("edited_model_message", editedModelMessage)
        put(LOCAL_TIMELINE_REWRITE_MODEL_HISTORY_KEY, JsonArray(modelHistory))
        put(
            LOCAL_TIMELINE_REWRITE_STATE_KEY,
            timelineRewriteJson.encodeToJsonElement(LocalTimelineRewriteState.serializer(), state),
        )
        put(
            LOCAL_TIMELINE_REWRITE_PROJECTION_KEY,
            timelineRewriteJson.encodeToJsonElement(LocalTimelineRewriteProjectionPlan.serializer(), plan),
        )
    })
}

internal fun decodeTimelineRewriteState(data: JsonObject): LocalTimelineRewriteState? {
    val encoded = data[LOCAL_TIMELINE_REWRITE_STATE_KEY] as? JsonObject ?: return null
    return runCatching {
        timelineRewriteJson.decodeFromJsonElement(LocalTimelineRewriteState.serializer(), encoded)
    }.getOrNull()
}

internal fun timelineRewriteModelHistory(data: JsonObject): List<JsonObject>? {
    val encoded = data[LOCAL_TIMELINE_REWRITE_MODEL_HISTORY_KEY] as? JsonArray ?: return null
    val decoded = ArrayList<JsonObject>(encoded.size)
    encoded.forEach { element ->
        decoded += element as? JsonObject ?: return null
    }
    return decoded
}

internal fun timelineRewriteEditedModelMessage(
    data: JsonObject,
    messageId: String,
): JsonObject? {
    val editedId = (data["edited_message_id"] as? JsonPrimitive)?.contentOrNull
    if (editedId != messageId) return null
    return data["edited_model_message"] as? JsonObject
}

internal fun isTimelineRewriteEditedMessage(data: JsonObject, messageId: String): Boolean =
    (data["edited_message_id"] as? JsonPrimitive)?.contentOrNull == messageId &&
        (data[LOCAL_TIMELINE_REWRITE_ID_KEY] as? JsonPrimitive)?.contentOrNull != null

internal fun recoverPendingTimelineRewriteProjection(
    eventLog: LocalSessionEventLog,
    memoryStore: MemoryStore,
    galleryStore: ChatPersonaGalleryStore,
    diaryStore: ChatDiaryStore? = null,
): Boolean {
    val rewrite = eventLog.latestMatching(setOf("chat/active-transcript")) { data ->
        data[LOCAL_TIMELINE_REWRITE_ID_KEY]?.jsonPrimitive?.contentOrNull != null &&
            data[LOCAL_TIMELINE_REWRITE_PROJECTION_KEY] is JsonObject
    } ?: return false
    val rewriteId = rewrite.data[LOCAL_TIMELINE_REWRITE_ID_KEY]
        ?.jsonPrimitive?.contentOrNull
        ?: return false
    val alreadyCommitted = eventLog.latestMatching(
        setOf(LOCAL_TIMELINE_REWRITE_PROJECTION_COMMITTED),
    ) { data ->
        data[LOCAL_TIMELINE_REWRITE_ID_KEY]?.jsonPrimitive?.contentOrNull == rewriteId
    }
    if (alreadyCommitted != null && alreadyCommitted.sequence > rewrite.sequence) return false

    val encoded = rewrite.data[LOCAL_TIMELINE_REWRITE_PROJECTION_KEY] as? JsonObject ?: return false
    val plan = timelineRewriteJson.decodeFromJsonElement(
        LocalTimelineRewriteProjectionPlan.serializer(),
        encoded,
    )

    memoryStore.rollbackSourceSessionFrom(
        sourceSessionId = plan.sourceSessionId,
        createdAtInclusive = plan.createdAtInclusive,
        discardedMessageIds = plan.discardedMessageIds.toSet(),
    )
    diaryStore?.rollbackSourceSessionFrom(
        sourceSessionId = plan.sourceSessionId,
        createdAtInclusive = plan.createdAtInclusive,
        discardedMessageIds = plan.discardedMessageIds.toSet(),
    )
    plan.directGallery?.let { direct ->
        galleryStore.excludeHistoryMessages(
            id = direct.galleryId,
            storyId = direct.storyId,
            messageKeys = direct.messageKeys,
            replacementChatState = direct.replacementChatState,
        )
    }
    plan.groupGallery.forEach { group ->
        galleryStore.replaceGroupChatState(group.galleryId, group.replacementChatState)
    }

    eventLog.append(LOCAL_TIMELINE_REWRITE_PROJECTION_COMMITTED, buildJsonObject {
        put(LOCAL_TIMELINE_REWRITE_ID_KEY, rewriteId)
    })
    return true
}
