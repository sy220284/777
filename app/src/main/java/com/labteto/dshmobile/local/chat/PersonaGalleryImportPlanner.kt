package com.labteto.dshmobile.local.chat

import java.util.UUID

internal sealed interface PersonaGalleryPreparedImport {
    data class Archive(
        val baseDocument: GalleryDocument,
        val entry: PersonaGalleryEntry,
        val diaryEntries: List<ChatDiaryEntry>,
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
        val importedStories = source.stories.map { raw ->
            val sourceId = raw.id.trim().take(120)
            val storyId = sourceId
                .takeIf { it.isNotBlank() && seenStoryIds.add(it) }
                ?: "story-${UUID.randomUUID()}".also { seenStoryIds.add(it) }
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
}
