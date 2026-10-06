package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.ChatContextState
import com.labteto.dshmobile.local.chat.ChatReplySuggestion
import com.labteto.dshmobile.local.chat.LocalChatBranchState
import com.labteto.dshmobile.local.chat.LocalGroupChatState
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem

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
    val tail = events.filter { it.sequence > sequenceExclusive }.sortedBy { it.sequence }
    val chat = com.labteto.dshmobile.local.chat.projectChatSessionControls(snapshot, tail)
    val work = com.labteto.dshmobile.local.work.projectWorkSessionControls(snapshot, tail)
    return LocalSessionControlProjection(
        plan = work.plan,
        todos = work.todos,
        goal = work.goal,
        planMode = work.planMode,
        personaId = chat.personaId,
        galleryId = chat.galleryId,
        galleryStoryId = chat.galleryStoryId,
        gallerySaveSuppressedThrough = chat.gallerySaveSuppressedThrough,
        replySuggestions = chat.replySuggestions,
        handoffSummary = chat.handoffSummary,
        chatBranches = chat.chatBranches,
        chatState = chat.chatState,
        chatContext = chat.chatContext,
        groupChat = chat.groupChat,
    )
}
