package com.labteto.dshmobile.local.chat

/** Applies the same bounded text rules to untrusted persona-transfer diary data as local diary writes. */
internal object ChatDiaryTransferSanitizer {
    fun sanitize(
        raw: ChatDiaryEntry,
        targetId: String,
        targetSubjectKey: String,
        personaName: String,
        supersededBy: String?,
    ): ChatDiaryEntry = raw.copy(
        id = targetId,
        subjectKey = targetSubjectKey,
        personaName = personaName.ifBlank { raw.personaName.trim().take(120) },
        event = raw.event.trim().take(ChatDiaryBounds.MAX_EVENT_CHARS),
        feeling = raw.feeling.trim().take(ChatDiaryBounds.MAX_FEELING_CHARS),
        innerThought = raw.innerThought.trim().take(ChatDiaryBounds.MAX_THOUGHT_CHARS),
        relationshipMeaning = raw.relationshipMeaning.trim().take(ChatDiaryBounds.MAX_RELATIONSHIP_CHARS),
        unresolvedEcho = raw.unresolvedEcho.trim().take(ChatDiaryBounds.MAX_ECHO_CHARS),
        importance = raw.importance.coerceIn(0, 5),
        sources = emptyList(),
        revisions = raw.revisions.takeLast(ChatDiaryBounds.MAX_REFINEMENT_REVISIONS).map { revision ->
            revision.copy(
                event = revision.event.trim().take(ChatDiaryBounds.MAX_EVENT_CHARS),
                feeling = revision.feeling.trim().take(ChatDiaryBounds.MAX_FEELING_CHARS),
                innerThought = revision.innerThought.trim().take(ChatDiaryBounds.MAX_THOUGHT_CHARS),
                relationshipMeaning = revision.relationshipMeaning.trim().take(ChatDiaryBounds.MAX_RELATIONSHIP_CHARS),
                unresolvedEcho = revision.unresolvedEcho.trim().take(ChatDiaryBounds.MAX_ECHO_CHARS),
                importance = revision.importance.coerceIn(0, 5),
                sources = emptyList(),
            )
        },
        supersededBy = supersededBy,
    )
}
