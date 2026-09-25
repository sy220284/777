package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.LocalHarnessMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A snapshot of a character and the actual story, independent of the live session. */
@Serializable
data class PersonaGalleryEntry(
    val id: String,
    val persona: PersonaProfile,
    val storyNotes: String = "",
    val history: List<LocalHarnessMessage> = emptyList(),
    val chatState: ChatCharacterState = ChatCharacterState(),
    val sourceSessionId: String = "",
    val updatedAt: Long = 0L,
) {
    /** Only a small, clearly labelled excerpt is sent to the model; the archive keeps the full story. */
    fun storyContext(): String = buildString {
        if (storyNotes.isNotBlank()) appendLine("剧情提要：${storyNotes.trim().take(2_500)}")
        if (chatState.updatedAt > 0L) {
            appendLine("保存时的关系：${chatState.relationshipState.take(80)}")
            chatState.dynamics.sharedMoments.takeLast(6).takeIf { it.isNotEmpty() }?.let {
                appendLine("已发生的共同经历：${it.joinToString("；").take(800)}")
            }
            chatState.unresolvedThreads.takeLast(3).takeIf { it.isNotEmpty() }?.let {
                appendLine("仍待推进的线索：${it.joinToString("；").take(400)}")
            }
        }
        val excerpt = history.asReversed().asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { "${if (it.role == "user") "用户" else persona.name}：${it.content.trim().take(600)}" }
            .take(16).toList().asReversed().joinToString("\n").takeLast(3_000)
        if (excerpt.isNotBlank()) appendLine("已保存故事的最近对话摘录：\n$excerpt")
    }.trim()
}

@Serializable
private data class GalleryDocument(
    val version: Int = 1,
    val entries: List<PersonaGalleryEntry> = emptyList(),
)

@Singleton
class ChatPersonaGalleryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val file = File(context.filesDir, "local-harness/chat/persona-gallery.json")

    @Synchronized
    fun list(): List<PersonaGalleryEntry> = read().entries.sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(
        persona: PersonaProfile,
        sourceSessionId: String,
        history: List<LocalHarnessMessage>,
        chatState: ChatCharacterState,
        notes: String,
        existingId: String? = null,
    ): PersonaGalleryEntry {
        val doc = read()
        val id = existingId?.takeIf { candidate -> doc.entries.any { it.id == candidate } }
            ?: "gallery-${UUID.randomUUID()}"
        val entry = PersonaGalleryEntry(
            id = id,
            persona = persona.copy(id = id),
            storyNotes = notes.trim().take(4_000),
            history = history.filter { it.role == "user" || it.role == "assistant" },
            chatState = chatState,
            sourceSessionId = sourceSessionId,
            updatedAt = System.currentTimeMillis(),
        )
        write(doc.copy(entries = doc.entries.filterNot { it.id == id } + entry))
        return entry
    }

    @Synchronized
    fun updateNotes(id: String, notes: String): Boolean {
        val doc = read()
        if (doc.entries.none { it.id == id }) return false
        write(doc.copy(entries = doc.entries.map {
            if (it.id == id) it.copy(storyNotes = notes.trim().take(4_000), updatedAt = System.currentTimeMillis()) else it
        }))
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val doc = read()
        if (doc.entries.none { it.id == id }) return false
        write(doc.copy(entries = doc.entries.filterNot { it.id == id }))
        return true
    }

    private fun read(): GalleryDocument = if (!file.isFile) GalleryDocument() else
        json.decodeFromString(GalleryDocument.serializer(), file.readText())

    private fun write(doc: GalleryDocument) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(GalleryDocument.serializer(), doc))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
