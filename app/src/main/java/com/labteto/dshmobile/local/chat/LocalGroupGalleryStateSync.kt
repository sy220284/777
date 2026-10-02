package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatContinuityState
import com.labteto.dshmobile.local.chat.ChatPersonaGalleryStore
import com.labteto.dshmobile.local.chat.ChatSceneState

internal data class LocalGroupGalleryProjectionFailure(
    val galleryId: String,
    val detail: String,
)

internal data class LocalGroupGalleryProjectionResult(
    val projected: Int,
    val failures: List<LocalGroupGalleryProjectionFailure>,
)

/**
 * Project the durable Session member state into Persona Gallery.
 *
 * Session is the source of truth. Gallery is a cross-session projection, so recovery simply
 * re-applies the current Session state instead of replaying a second pending/applied state machine.
 */
internal fun projectGroupGalleryState(
    groupChat: LocalGroupChatState,
    galleryStore: ChatPersonaGalleryStore,
): LocalGroupGalleryProjectionResult {
    if (!groupChat.enabled) return LocalGroupGalleryProjectionResult(0, emptyList())

    var projected = 0
    val failures = mutableListOf<LocalGroupGalleryProjectionFailure>()
    groupChat.members.forEach { member ->
        if (member.galleryId.isBlank() || member.chatState.updatedAt <= 0L) return@forEach
        val privateState = member.chatState.copy(
            scene = ChatSceneState(),
            continuity = ChatContinuityState(),
        )
        try {
            if (galleryStore.updateGroupChatState(member.galleryId, privateState) != null) {
                projected += 1
            }
        } catch (error: Exception) {
            failures += LocalGroupGalleryProjectionFailure(
                galleryId = member.galleryId,
                detail = error.message.orEmpty().take(1_000),
            )
        }
    }
    return LocalGroupGalleryProjectionResult(projected, failures)
}
