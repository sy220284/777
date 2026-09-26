package com.labteto.dshmobile.local

/**
 * Small transcript facts that remain valid even after the visible/runtime message list becomes a
 * bounded hot window.
 *
 * Keep this deliberately tiny: complete history belongs to Session Event, while these values serve
 * constant-time product decisions that previously rescanned the whole transcript.
 */
data class LocalTranscriptRuntimeIndex(
    val firstUserTitle: String? = null,
    val latestCreatedAt: Long = 0L,
    val latestDialogueMessageId: String? = null,
    val hasDialogue: Boolean = false,
)

internal fun buildLocalTranscriptRuntimeIndex(
    messages: List<LocalHarnessMessage>,
): LocalTranscriptRuntimeIndex {
    var firstUserTitle: String? = null
    var latestCreatedAt = 0L
    var latestDialogueMessageId: String? = null
    var hasDialogue = false

    messages.forEach { message ->
        if (firstUserTitle == null && message.role == "user") {
            firstUserTitle = message.content.lineSequence().firstOrNull()?.take(40)
        }
        if (message.createdAt > latestCreatedAt) latestCreatedAt = message.createdAt
        if (message.role == "user" || message.role == "assistant") {
            hasDialogue = true
            latestDialogueMessageId = message.id
        }
    }

    return LocalTranscriptRuntimeIndex(
        firstUserTitle = firstUserTitle,
        latestCreatedAt = latestCreatedAt,
        latestDialogueMessageId = latestDialogueMessageId,
        hasDialogue = hasDialogue,
    )
}

internal fun appendLocalTranscriptRuntimeIndex(
    current: LocalTranscriptRuntimeIndex,
    messages: List<LocalHarnessMessage>,
): LocalTranscriptRuntimeIndex {
    if (messages.isEmpty()) return current
    var firstUserTitle = current.firstUserTitle
    var latestCreatedAt = current.latestCreatedAt
    var latestDialogueMessageId = current.latestDialogueMessageId
    var hasDialogue = current.hasDialogue

    messages.forEach { message ->
        if (firstUserTitle == null && message.role == "user") {
            firstUserTitle = message.content.lineSequence().firstOrNull()?.take(40)
        }
        if (message.createdAt > latestCreatedAt) latestCreatedAt = message.createdAt
        if (message.role == "user" || message.role == "assistant") {
            hasDialogue = true
            latestDialogueMessageId = message.id
        }
    }

    return LocalTranscriptRuntimeIndex(
        firstUserTitle = firstUserTitle,
        latestCreatedAt = latestCreatedAt,
        latestDialogueMessageId = latestDialogueMessageId,
        hasDialogue = hasDialogue,
    )
}
