package com.labteto.dshmobile.local.chat

import java.io.File
import kotlinx.serialization.json.Json

/**
 * One-time V1–V4 Gallery migration. The current Gallery store only invokes this boundary;
 * legacy files never participate in normal V5 reads or writes after the marker is committed.
 */
internal class PersonaGallerySchemaMigrationCoordinator(
    private val currentFile: File,
    private val json: Json,
) {
    private val root = requireNotNull(currentFile.parentFile)
    private val legacyFile = File(root, "persona-gallery.json")
    private val legacyHistoryRoot = File(root, "persona-history")
    private val currentHistoryRoot = File(root, "persona-history-v5")
    private val marker = File(root, "persona-gallery-v1-v4-to-v5.done")

    fun migrateIfNeeded() {
        if (currentFile.name != "persona-gallery-v5.json") return
        val currentBackup = File(root, "${currentFile.name}.bak")
        val currentReadable = listOf(currentFile, currentBackup)
            .asSequence()
            .filter(File::isFile)
            .any { candidate ->
                runCatching {
                    json.decodeFromString(GalleryDocument.serializer(), candidate.readText())
                }.getOrNull()?.version == 5
            }
        if (marker.isFile && currentReadable) return
        if (!PersonaSchemaMigration.hasDurableSource(legacyFile)) return

        val documentStore = PersonaGalleryDocumentStore(currentFile, json)
        val history = PersonaGalleryHistoryCoordinator(currentHistoryRoot, json)
        val current = runCatching { documentStore.read() }
            .getOrElse { GalleryDocument() }
        require(current.version == 5) { "人物图集版本不受支持" }

        val legacyDocument = PersonaSchemaMigration.readLegacyGalleryDocument(legacyFile, json)
        val oldHistory = PersonaGalleryHistoryStore(legacyHistoryRoot, json)
        val mergedEntries = current.entries.toMutableList()

        legacyDocument.entries.forEach { oldEntry ->
            val convertedBase = PersonaSchemaMigration.toCurrent(oldEntry)
            val converted = convertedBase.copy(
                stories = convertedBase.stories.map { story ->
                    val archived = oldHistory.all(oldEntry.id, story.id)
                    val fullHistory = archived.ifEmpty { story.history }
                    story.copy(
                        history = fullHistory,
                        historyTotalCount = maxOf(story.historyTotalCount, fullHistory.size),
                        historyArchived = false,
                    )
                },
            )
            // Migration only auto-merges the same durable identity. Name-based matching is too
            // risky here because two distinct user-created characters can legitimately share a name.
            val index = mergedEntries.indexOfFirst { it.id == converted.id }
            val merged = if (index >= 0) {
                val currentEntry = history.hydrate(history.migrate(mergedEntries[index]))
                val entryId = currentEntry.id
                currentEntry.copy(
                    persona = PersonaSchemaMigration.mergeCurrentFirst(
                        currentEntry.persona,
                        converted.persona,
                    ).copy(id = entryId),
                    portraitPath = currentEntry.portraitPath.ifBlank { converted.portraitPath },
                    groupChatState = mergeChatState(currentEntry.groupChatState, converted.groupChatState),
                    stories = mergeStories(currentEntry.stories, converted.stories),
                    updatedAt = maxOf(currentEntry.updatedAt, converted.updatedAt),
                )
            } else {
                converted
            }

            val archivedMerged = history.archiveEntry(merged)
            if (index >= 0) {
                mergedEntries[index] = archivedMerged
            } else {
                mergedEntries += archivedMerged
            }
        }

        documentStore.write(current.copy(version = 5, entries = mergedEntries))
        markDone()
    }

    private fun mergeStories(
        current: List<PersonaGalleryStory>,
        legacy: List<PersonaGalleryStory>,
    ): List<PersonaGalleryStory> {
        val merged = current.toMutableList()
        legacy.forEach { old ->
            val sameId = merged.indexOfFirst { it.id == old.id }
            if (sameId >= 0) {
                merged[sameId] = mergeGalleryStories(merged[sameId], old)
            } else {
                val combined = mergeGalleryStoryLists(merged, listOf(old))
                merged.clear()
                merged += combined
            }
        }
        return merged.sortedByDescending(PersonaGalleryStory::updatedAt)
    }

    private fun markDone() {
        marker.parentFile?.mkdirs()
        val temporary = File(marker.parentFile, marker.name + ".tmp")
        temporary.outputStream().use { output ->
            output.write("v5\n".toByteArray())
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(marker)) {
            temporary.copyTo(marker, overwrite = true)
            check(temporary.delete()) { "人物图集迁移标记临时文件无法清理" }
        }
    }
}
