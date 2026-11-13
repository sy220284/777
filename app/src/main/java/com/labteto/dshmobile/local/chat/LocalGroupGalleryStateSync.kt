package com.labteto.dshmobile.local.chat

internal data class LocalGroupGalleryProjectionFailure(
    val galleryId: String,
    val detail: String,
)

internal data class LocalGroupGalleryProjectionResult(
    val projected: Int,
    val failures: List<LocalGroupGalleryProjectionFailure>,
)

/** Session owns durable member facts; Gallery receives one atomic projection per group batch. */
internal fun projectGroupGalleryState(
    groupChat: LocalGroupChatState,
    galleryStore: ChatPersonaGalleryStore,
): LocalGroupGalleryProjectionResult {
    if (!groupChat.enabled) return LocalGroupGalleryProjectionResult(0, emptyList())

    val updates = groupChat.members.mapNotNull { member ->
        if (member.galleryId.isBlank() || member.chatState.updatedAt <= 0L) return@mapNotNull null
        member.galleryId to member.chatState.copy(
            scene = ChatSceneState(),
            continuity = ChatContinuityState(),
        )
    }
    if (updates.isEmpty()) return LocalGroupGalleryProjectionResult(0, emptyList())
    return try {
        val written = galleryStore.updateGroupChatStates(updates)
        LocalGroupGalleryProjectionResult(updates.count { it.first in written }, emptyList())
    } catch (error: Exception) {
        LocalGroupGalleryProjectionResult(
            0,
            updates.map { it.first }.distinct().map { id ->
                LocalGroupGalleryProjectionFailure(id, error.message.orEmpty().take(1_000))
            },
        )
    }
}
