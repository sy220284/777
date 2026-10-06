package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog

/** Chat alone interprets durable Chat control events. The app supplies an ordered event tail. */
internal fun projectChatSessionControls(
    snapshot: LocalHarnessSession,
    events: List<LocalSessionEventLog.Event>,
): LocalChatDurableState {
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

    events.forEach { event ->
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
                "chat/branch-state" -> {
                    decodeChatBranchStateEvent(event.data)?.let { chatBranches = it }
                }
        }
    }
    return LocalChatDurableState(
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
