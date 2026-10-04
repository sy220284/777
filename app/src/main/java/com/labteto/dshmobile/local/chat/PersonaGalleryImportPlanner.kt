package com.labteto.dshmobile.local.chat

import java.util.UUID

internal sealed interface PersonaGalleryPreparedImport {
    data class Archive(
        val baseDocument: GalleryDocument,
        val entry: PersonaGalleryEntry,
        val diaryEntries: List<ChatDiaryEntry>,
        val rollbackEntry: PersonaGalleryEntry? = null,
    ) : PersonaGalleryPreparedImport

    data class Share(val payload: String) : PersonaGalleryPreparedImport
}

/** Pure import planning: resolve destination identity before any Gallery or diary bytes are committed. */
internal object PersonaGalleryImportPlanner {
    fun archive(
        current: GalleryDocument,
        source: PersonaGalleryEntry,
        diaryEntries: List<ChatDiaryEntry>,
        now: Long = System.currentTimeMillis(),
    ): PersonaGalleryPreparedImport.Archive {
        val importedPersona = fullSharePersona(source.persona).copy(updatedAt = now)
        require(isMeaningfulGalleryPersona(importedPersona)) { "人物设定内容不足，无法导入" }

        val seenStoryIds = hashSetOf<String>()
        val importedStories = source.stories.mapIndexed { index, raw ->
            val storyId = transferStoryId(raw.id, index, seenStoryIds)
            raw.copy(
                id = storyId,
                title = raw.title.trim().take(160),
                notes = raw.notes.trim().take(4_000),
                history = mergeHistory(emptyList(), raw.history),
                sourceSessionIds = emptyList(),
                excludedMessageKeys = emptyList(),
            )
        }

        val matched = current.entries.filter {
            samePersonaIdentity(it.persona, importedPersona)
        }.singleOrNull()
        val entryId = matched?.id ?: "gallery-${UUID.randomUUID()}"
        val mergedEntry = if (matched != null) {
            matched.copy(
                persona = mergePersonaProfiles(matched.persona, importedPersona)
                    .copy(id = entryId, updatedAt = now),
                groupChatState = mergeChatState(matched.groupChatState, source.groupChatState),
                stories = mergeGalleryStoryLists(matched.stories, importedStories),
                updatedAt = now,
            )
        } else {
            PersonaGalleryEntry(
                id = entryId,
                persona = importedPersona.copy(id = entryId),
                groupChatState = source.groupChatState,
                stories = importedStories,
                updatedAt = now,
            )
        }
        return PersonaGalleryPreparedImport.Archive(
            baseDocument = current,
            entry = migrateLegacyPersonaGalleryEntry(mergedEntry),
            diaryEntries = diaryEntries,
        )
    }

    private fun transferStoryId(
        rawId: String,
        index: Int,
        seen: MutableSet<String>,
    ): String {
        val sourceId = rawId.trim().take(120)
        if (SAFE_STORY_ID.matches(sourceId) && seen.add(sourceId)) return sourceId

        var attempt = 0
        while (true) {
            val seed = "777:persona-story:$index:$sourceId:$attempt"
            val candidate = "story-${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"
            if (seen.add(candidate)) return candidate
            attempt++
        }
    }

    private val SAFE_STORY_ID = Regex("[A-Za-z0-9._-]{1,120}")
}
