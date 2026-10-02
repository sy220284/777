package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Owns durable Gallery-document IO and the bounded hot-history projection.
 *
 * Persona business rules stay in [ChatPersonaGalleryStore]; cold dialogue bytes stay in
 * [PersonaGalleryHistoryStore]. This boundary only serializes the compact Gallery document.
 */
internal class PersonaGalleryDocumentStore(
    private val file: File,
    private val json: Json,
) {
    private val durableFile = RecoveringChatDocumentFile(file)
    private val backupFile = File(file.parentFile, "${file.name}.bak")
    private var cachedDocument: GalleryDocument? = null
    private var cachedStamp: DocumentStamp? = null

    @Synchronized
    fun read(): GalleryDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }
        val document = durableFile.read(
            defaultValue = ::GalleryDocument,
            decode = { encoded -> json.decodeFromString(GalleryDocument.serializer(), encoded) },
        )
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    @Synchronized
    fun write(document: GalleryDocument) {
        val hotDocument = document.copy(
            entries = document.entries.map { entry ->
                entry.copy(
                    stories = entry.stories.map { story ->
                        story.copy(
                            history = story.history
                                .filter { it.role == "user" || it.role == "assistant" }
                                .takeLast(PersonaGalleryHistoryStore.HOT_GALLERY_HISTORY_MESSAGES),
                            historyTotalCount = maxOf(story.historyTotalCount, story.history.size),
                        )
                    },
                )
            },
        )
        val encoded = json.encodeToString(GalleryDocument.serializer(), hotDocument)
        durableFile.write(encoded) { candidate ->
            runCatching { json.decodeFromString(GalleryDocument.serializer(), candidate) }.isSuccess
        }
        cachedDocument = hotDocument
        cachedStamp = documentStamp()
    }

    private fun documentStamp(): DocumentStamp = DocumentStamp(
        primaryModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
        primaryLength = file.takeIf(File::isFile)?.length() ?: -1L,
        backupModified = backupFile.takeIf(File::isFile)?.lastModified() ?: -1L,
        backupLength = backupFile.takeIf(File::isFile)?.length() ?: -1L,
    )

    private data class DocumentStamp(
        val primaryModified: Long,
        val primaryLength: Long,
        val backupModified: Long,
        val backupLength: Long,
    )
}
