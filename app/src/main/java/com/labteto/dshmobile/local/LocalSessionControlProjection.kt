package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.chat.decodeChatBranchStateEvent
import com.labteto.dshmobile.local.chat.decodeChatDomainStateEvent
import com.labteto.dshmobile.local.chat.decodeTimelineRewriteState
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Composition adapter that folds Chat/Work control events over the durable Session envelope.
 *
 * Shared Session owns event storage and replay cursors; product-domain event interpretation stays
 * above that shared boundary.
 */
internal data class LocalSessionControlProjection(
    val plan: List<String>,
    val todos: List<LocalTodoItem>,
    val goal: LocalGoal?,
    val planMode: Boolean,
    val personaId: String,
    val galleryId: String?,
    val galleryStoryId: String?,
    val gallerySaveSuppressedThrough: Long,
    val replySuggestions: List<ChatReplySuggestion>,
    val handoffSummary: String?,
    val chatBranches: LocalChatBranchState,
    val chatState: ChatCharacterState,
    val chatContext: ChatContextState,
    val groupChat: LocalGroupChatState,
)

internal fun projectSessionControlTail(
    snapshot: LocalHarnessSession,
    events: List<LocalSessionEventLog.Event>,
    sequenceExclusive: Long,
): LocalSessionControlProjection {
    var plan = snapshot.plan
    var todos = snapshot.todos
    var goal = snapshot.goal
    var planMode = snapshot.planMode
    var personaId = snapshot.personaId
    var galleryId = snapshot.galleryId
    var galleryStoryId = snapshot.galleryStoryId
    var gallerySaveSuppressedThrough = snapshot.gallerySaveSuppressedThrough
    var replySuggestions = snapshot.replySuggestions
    var handoffSummary = snapshot.handoffSummary
    var chatBranches = snapshot.chatBranches
    var chatState = snapshot.chatState
    var chatContext = snapshot.chatContext
    var groupChat = snapshot.groupChat

    events
        .asSequence()
        .filter { event -> event.sequence > sequenceExclusive }
        .sortedBy { event -> event.sequence }
        .forEach { event ->
            when (event.type) {
                "chat/active-transcript" -> {
                    decodeTimelineRewriteState(event.data)?.let { rewrite ->
                        chatBranches = rewrite.chatBranches
                        chatState = rewrite.chatState
                        chatContext = rewrite.chatContext
                        groupChat = rewrite.groupChat
                        rewrite.replySuggestions?.let { replySuggestions = it }
                    }
                }
                LOCAL_CHAT_DOMAIN_STATE_EVENT_TYPE -> {
                    decodeChatDomainStateEvent(event.data)?.let { domain ->
                        personaId = domain.personaId
                        galleryId = domain.galleryId
                        galleryStoryId = domain.galleryStoryId
                        gallerySaveSuppressedThrough = domain.gallerySaveSuppressedThrough
                        replySuggestions = domain.replySuggestions
                        handoffSummary = domain.handoffSummary
                        chatBranches = domain.chatBranches
                        chatState = domain.chatState
                        chatContext = domain.chatContext
                        groupChat = domain.groupChat
                    }
                }
                "plan/state" -> decodePlanState(event.data)?.let { plan = it }
                "todo/state" -> decodeTodoState(event.data)?.let { todos = it }
                "goal/state" -> {
                    val description = (event.data["description"] as? JsonPrimitive)?.contentOrNull
                    val status = (event.data["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (
                        !description.isNullOrBlank() &&
                        status in setOf("active", "paused", "completed", "blocked")
                    ) {
                        goal = LocalGoal(
                            description = description.take(2_000),
                            status = status,
                            note = (event.data["note"] as? JsonPrimitive)?.contentOrNull?.take(2_000),
                        )
                    }
                }
                "plan/mode" -> {
                    (event.data["active"] as? JsonPrimitive)?.booleanOrNull?.let { planMode = it }
                }
                "chat/branch-state" -> {
                    decodeChatBranchStateEvent(event.data)?.let { chatBranches = it }
                }
            }
        }

    return LocalSessionControlProjection(
        plan = plan,
        todos = todos,
        goal = goal,
        planMode = planMode,
        personaId = personaId,
        galleryId = galleryId,
        galleryStoryId = galleryStoryId,
        gallerySaveSuppressedThrough = gallerySaveSuppressedThrough,
        replySuggestions = replySuggestions,
        handoffSummary = handoffSummary,
        chatBranches = chatBranches,
        chatState = chatState,
        chatContext = chatContext,
        groupChat = groupChat,
    )
}

private fun decodePlanState(data: JsonObject): List<String>? {
    val items = data["items"] as? JsonArray ?: return null
    val decoded = mutableListOf<String>()
    for (element in items) {
        val item = element as? JsonPrimitive ?: return null
        if (!item.isString) return null
        decoded += item.contentOrNull ?: return null
    }
    return decoded.take(20)
}

private fun decodeTodoState(data: JsonObject): List<LocalTodoItem>? {
    val items = data["items"] as? JsonArray ?: return null
    val allowed = setOf("pending", "in_progress", "completed")
    val decoded = mutableListOf<LocalTodoItem>()
    for (element in items) {
        val item = element as? JsonObject ?: return null
        val contentValue = item["content"] as? JsonPrimitive ?: return null
        val statusValue = item["status"] as? JsonPrimitive ?: return null
        if (!contentValue.isString || !statusValue.isString) return null
        val content = contentValue.contentOrNull?.trim().orEmpty()
        val status = statusValue.contentOrNull.orEmpty()
        if (content.isEmpty() || status !in allowed) return null
        decoded += LocalTodoItem(content.take(500), status)
    }
    return decoded.take(50)
}
