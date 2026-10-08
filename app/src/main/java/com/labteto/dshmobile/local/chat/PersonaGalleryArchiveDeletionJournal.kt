package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.persistence.RecoveringDocumentFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
internal data class PersonaArchiveDeleteIntent(val entryId: String, val storyId: String? = null)

/** Durable intent before metadata commit; referenced archives are never deleted during recovery. */
internal class PersonaGalleryArchiveDeletionJournal(file: File, private val json: Json) {
    private val durable = RecoveringDocumentFile(
        File(requireNotNull(file.parentFile), "persona-archive-deletes-v1.json"),
    )
    private val codec = ListSerializer(PersonaArchiveDeleteIntent.serializer())

    fun queue(entryId: String, storyId: String? = null) {
        val intent = PersonaArchiveDeleteIntent(entryId, storyId)
        val current = durable.read(defaultValue = { emptyList() }, decode = { json.decodeFromString(codec, it) })
        if (intent !in current) write(current + intent)
    }

    fun drain(document: GalleryDocument, history: PersonaGalleryHistoryCoordinator) {
        val pending = durable.read(defaultValue = { emptyList() }, decode = { json.decodeFromString(codec, it) })
        if (pending.isEmpty()) return
        pending.forEach { intent ->
            val current = document.entries.firstOrNull { it.id == intent.entryId }
            when {
                intent.storyId == null && current == null -> history.deleteEntry(intent.entryId)
                intent.storyId != null && current?.stories?.none { it.id == intent.storyId } != false ->
                    history.deleteStory(intent.entryId, intent.storyId)
            }
        }
        write(emptyList())
    }

    private fun write(intents: List<PersonaArchiveDeleteIntent>) {
        val serialized = json.encodeToString(codec, intents)
        durable.write(serialized) { candidate ->
            runCatching { json.decodeFromString(codec, candidate) }.isSuccess
        }
    }
}
