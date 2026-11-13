package com.labteto.dshmobile.local.chat


internal fun ChatContextState.withExperience(id: String, sessions: List<String>): ChatContextState = copy(
    experienceId = experienceId.ifBlank { id },
    experienceSessionIds = (experienceSessionIds + sessions).filter(String::isNotBlank).distinct(),
)

internal fun diaryMatchesExperience(
    entry: ChatDiaryEntry,
    experienceId: String,
    sessionIds: Set<String>,
): Boolean = when {
    experienceId.isBlank() -> true // Legacy management callers; production supplies a scope.
    entry.experienceId.isNotBlank() -> entry.experienceId == experienceId
    else -> entry.sources.any { it.sessionId in sessionIds }
}

internal fun scopedRelationshipSubjectKey(subject: String?, experienceId: String): String? =
    subject?.let { if (experienceId.isBlank()) it else "$it/experience:$experienceId" }
