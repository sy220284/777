package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.session.LocalHarnessMessage
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Owns the cold archive / hot-window projection boundary for persona stories.
 *
 * ChatPersonaGalleryStore keeps persona/story business rules; this coordinator keeps archive
 * migration, hydration, deletion and hot-window projection in one place.
 */
internal class PersonaGalleryHistoryCoordinator(
    root: File,
    json: Json,
) {
    private val store = PersonaGalleryHistoryStore(root, json)

    fun page(entry: PersonaGalleryEntry, storyId: String, limit: Int): PersonaGalleryHistoryPage {
        require(entry.stories.any { it.id == storyId }) { "人物故事不存在" }
        return store.tail(entry.id, storyId, limit)
    }

    fun hydrate(entry: PersonaGalleryEntry): PersonaGalleryEntry = entry.copy(
        stories = entry.stories.map { story ->
            story.copy(
                history = store.all(entry.id, story.id),
                historyTotalCount = story.historyTotalCount,
            )
        },
    )

    fun archiveEntry(entry: PersonaGalleryEntry): PersonaGalleryEntry = entry.copy(
        stories = entry.stories.map { story -> archiveStory(entry.id, story) },
    )

    fun archiveStory(entryId: String, story: PersonaGalleryStory): PersonaGalleryStory {
        val archive = store.merge(entryId, story.id, story.history)
        return story.copy(
            history = archive.messages,
            historyTotalCount = archive.totalCount,
            historyArchived = true,
        )
    }

    fun merge(
        entryId: String,
        storyId: String,
        incoming: List<LocalHarnessMessage>,
    ): PersonaGalleryHistoryPage = store.merge(entryId, storyId, incoming)

    fun exclude(
        entryId: String,
        story: PersonaGalleryStory,
        keys: Set<String>,
        replacementChatState: ChatCharacterState,
        updatedAt: Long,
    ): Pair<PersonaGalleryStory, Int> {
        val removed = keys.count { key -> store.deleteMessage(entryId, story.id, key) }
        val refreshed = store.tail(entryId, story.id, PersonaGalleryHistoryStore.HOT_GALLERY_HISTORY_MESSAGES)
        return story.copy(
            history = refreshed.messages,
            historyTotalCount = refreshed.totalCount,
            historyArchived = true,
            excludedMessageKeys = mergePersonaLines(
                story.excludedMessageKeys,
                keys.toList(),
                MAX_GALLERY_EXCLUDED_MESSAGE_KEYS,
            ),
            chatState = replacementChatState,
            updatedAt = updatedAt,
        ) to removed
    }

    fun containsMessage(entryId: String, storyId: String, messageKey: String): Boolean =
        store.containsMessage(entryId, storyId, messageKey)

    fun deleteMessage(
        entryId: String,
        story: PersonaGalleryStory,
        messageKey: String,
        updatedAt: Long,
    ): PersonaGalleryStory? {
        if (!store.deleteMessage(entryId, story.id, messageKey)) return null
        val refreshed = store.tail(entryId, story.id, PersonaGalleryHistoryStore.HOT_GALLERY_HISTORY_MESSAGES)
        return story.copy(
            history = refreshed.messages,
            historyTotalCount = refreshed.totalCount,
            historyArchived = true,
            excludedMessageKeys = mergePersonaLines(
                story.excludedMessageKeys,
                listOf(messageKey),
                MAX_GALLERY_EXCLUDED_MESSAGE_KEYS,
            ),
            updatedAt = updatedAt,
        )
    }

    fun migrate(entry: PersonaGalleryEntry): PersonaGalleryEntry = entry.copy(
        stories = entry.stories.map { story ->
            if (story.historyArchived) {
                hot(story)
            } else {
                val archive = store.migrateIfNeeded(entry.id, story.id, story.history)
                story.copy(
                    history = archive.messages,
                    historyTotalCount = archive.totalCount,
                    historyArchived = true,
                )
            }
        },
    )

    fun hotDocument(document: GalleryDocument): GalleryDocument = document.copy(
        entries = document.entries.map { entry ->
            entry.copy(stories = entry.stories.map(::hot))
        },
    )

    /** Replay durable message tombstones before exposing cold history after a restart. */
    fun pruneExcluded(entryId: String, storyId: String, excluded: Set<String>): Int =
        store.pruneExcluded(entryId, storyId, excluded)

    fun deleteEntry(entryId: String) = store.deleteEntry(entryId)

    fun deleteStory(entryId: String, storyId: String) = store.deleteStory(entryId, storyId)

    private fun hot(story: PersonaGalleryStory): PersonaGalleryStory = story.copy(
        history = story.history
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(PersonaGalleryHistoryStore.HOT_GALLERY_HISTORY_MESSAGES),
        historyTotalCount = maxOf(story.historyTotalCount, story.history.size),
    )
}
