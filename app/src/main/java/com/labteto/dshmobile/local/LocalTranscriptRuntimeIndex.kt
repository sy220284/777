package com.labteto.dshmobile.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Small transcript facts that remain valid even after the visible/runtime message list becomes a
 * bounded hot window.
 *
 * Keep this deliberately tiny: complete history belongs to Session Event, while these values serve
 * constant-time product decisions that previously rescanned the whole transcript.
 */
@Serializable
data class LocalTranscriptRuntimeIndex(
    val firstUserTitle: String? = null,
    val latestCreatedAt: Long = 0L,
    val latestDialogueCreatedAt: Long = 0L,
    val latestDialogueMessageId: String? = null,
    val latestUserMessageId: String? = null,
    val latestUserContent: String? = null,
    val latestMessageId: String? = null,
    val latestMessageRole: String? = null,
    val totalMessageCount: Long = 0L,
    val hasDialogue: Boolean = false,
    val branchingEligible: Boolean = true,
    val lastTurnDialogueRole: String? = null,
)

internal fun buildLocalTranscriptRuntimeIndex(
    messages: List<LocalHarnessMessage>,
): LocalTranscriptRuntimeIndex =
    appendLocalTranscriptRuntimeIndex(LocalTranscriptRuntimeIndex(), messages)

internal fun projectLocalTranscriptRuntimeIndexTail(
    snapshot: LocalTranscriptRuntimeIndex,
    events: List<LocalSessionEventLog.Event>,
    sequenceExclusive: Long,
): LocalTranscriptRuntimeIndex {
    var current = snapshot
    events.asSequence()
        .filter { event -> event.sequence > sequenceExclusive }
        .sortedBy(LocalSessionEventLog.Event::sequence)
        .forEach { event ->
            val decoded = decodeTranscriptMessages(event.data) ?: return@forEach
            if (event.type == "chat/active-transcript") {
                current = buildLocalTranscriptRuntimeIndex(decoded)
                return@forEach
            }
            if (decoded.isEmpty()) return@forEach
            val replaced = if (event.type == "assistant/message") {
                (event.data["replaces"] as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
            if (replaced != null && current.totalMessageCount > 0L) {
                current = current.copy(totalMessageCount = current.totalMessageCount - 1L)
            }
            current = appendLocalTranscriptRuntimeIndex(current, decoded)
        }
    return current
}

internal fun appendLocalTranscriptRuntimeIndex(
    current: LocalTranscriptRuntimeIndex,
    messages: List<LocalHarnessMessage>,
): LocalTranscriptRuntimeIndex {
    if (messages.isEmpty()) return current
    var firstUserTitle = current.firstUserTitle
    var latestCreatedAt = current.latestCreatedAt
    var latestDialogueCreatedAt = current.latestDialogueCreatedAt
    var latestDialogueMessageId = current.latestDialogueMessageId
    var latestUserMessageId = current.latestUserMessageId
    var latestUserContent = current.latestUserContent
    var latestMessageId = current.latestMessageId
    var latestMessageRole = current.latestMessageRole
    var totalMessageCount = current.totalMessageCount
    var hasDialogue = current.hasDialogue
    var branchingEligible = current.branchingEligible
    var lastTurnDialogueRole = current.lastTurnDialogueRole

    messages.forEach { message ->
        totalMessageCount += 1L
        latestMessageId = message.id
        latestMessageRole = message.role
        if (message.createdAt > latestCreatedAt) latestCreatedAt = message.createdAt

        if (message.role !in setOf("user", "assistant", "system")) {
            branchingEligible = false
        }

        if (message.role == "user" || message.role == "assistant") {
            hasDialogue = true
            latestDialogueMessageId = message.id
            if (message.createdAt > latestDialogueCreatedAt) {
                latestDialogueCreatedAt = message.createdAt
            }
        }

        if (message.role == "user") {
            if (firstUserTitle == null) {
                firstUserTitle = message.content.lineSequence().firstOrNull()?.take(40)
            }
            latestUserMessageId = message.id
            latestUserContent = message.content
            if (chatMessageHasAttachmentContext(message)) branchingEligible = false
        }

        if (message.role == "user" || (message.role == "assistant" && !message.proactive)) {
            if (lastTurnDialogueRole == null && message.role != "user") {
                branchingEligible = false
            } else if (lastTurnDialogueRole == message.role) {
                branchingEligible = false
            }
            lastTurnDialogueRole = message.role
        }
    }

    return LocalTranscriptRuntimeIndex(
        firstUserTitle = firstUserTitle,
        latestCreatedAt = latestCreatedAt,
        latestDialogueCreatedAt = latestDialogueCreatedAt,
        latestDialogueMessageId = latestDialogueMessageId,
        latestUserMessageId = latestUserMessageId,
        latestUserContent = latestUserContent,
        latestMessageId = latestMessageId,
        latestMessageRole = latestMessageRole,
        totalMessageCount = totalMessageCount,
        hasDialogue = hasDialogue,
        branchingEligible = branchingEligible,
        lastTurnDialogueRole = lastTurnDialogueRole,
    )
}
