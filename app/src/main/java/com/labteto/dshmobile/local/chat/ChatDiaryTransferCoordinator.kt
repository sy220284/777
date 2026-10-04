package com.labteto.dshmobile.local.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Owns portable diary export/import projection and destination identity rebinding. */
@Singleton
class ChatDiaryTransferCoordinator internal constructor(
    root: File,
    json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/chat-diary"), json)
    private val documents = ChatDiaryDocumentStore(root, json)

    @Synchronized
    internal fun list(subjectKey: String): List<ChatDiaryEntry> =
        documents.read().entries.asSequence()
            .filter { it.subjectKey == subjectKey }
            .sortedWith(compareBy<ChatDiaryEntry>(ChatDiaryEntry::createdAt).thenBy(ChatDiaryEntry::id))
            .take(MAX_CHAT_DIARY_ENTRIES)
            .toList()

    @Synchronized
    internal fun import(
        subjectKey: String,
        personaName: String,
        transferred: List<ChatDiaryEntry>,
    ): Int {
        val targetSubjectKey = subjectKey.trim()
        if (targetSubjectKey.isBlank() || transferred.isEmpty()) return 0

        val entries = documents.read().entries.toMutableList()
        val existingById = entries.associateBy(ChatDiaryEntry::id)
        val candidates = transferred.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy(ChatDiaryEntry::id)
            .take(MAX_CHAT_DIARY_ENTRIES)
            .toList()
        if (candidates.isEmpty()) return 0

        val usedIds = entries.mapTo(hashSetOf(), ChatDiaryEntry::id)
        val idMap = linkedMapOf<String, String>()
        candidates.forEach { entry ->
            val sourceId = entry.id.trim()
            val existing = existingById[sourceId]
            val targetId = if (existing == null || existing.subjectKey == targetSubjectKey) {
                sourceId
            } else {
                generateSequence { UUID.randomUUID().toString() }
                    .first { candidate -> candidate !in usedIds }
            }
            usedIds += targetId
            idMap[sourceId] = targetId
        }

        val cleanPersonaName = personaName.trim().take(120)
        var changed = 0
        candidates.forEach { raw ->
            val sourceId = raw.id.trim()
            val targetId = idMap[sourceId] ?: return@forEach
            val imported = raw.copy(
                id = targetId,
                subjectKey = targetSubjectKey,
                personaName = cleanPersonaName.ifBlank { raw.personaName.trim().take(120) },
                sources = emptyList(),
                revisions = raw.revisions.takeLast(8).map { revision ->
                    revision.copy(sources = emptyList())
                },
                supersededBy = raw.supersededBy
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(idMap::get),
            )
            val existingIndex = entries.indexOfFirst {
                it.id == targetId && it.subjectKey == targetSubjectKey
            }
            if (existingIndex < 0) {
                entries += imported
                changed++
            } else if (imported.updatedAt > entries[existingIndex].updatedAt) {
                entries[existingIndex] = imported
                changed++
            }
        }
        if (changed == 0) return 0

        val repaired = ChatDiarySupersessionPolicy.repairLinks(entries)
        val protectedId = repaired.asSequence()
            .filter(ChatDiaryEntry::active)
            .maxByOrNull(ChatDiaryEntry::updatedAt)
            ?.id
            .orEmpty()
        documents.write(
            ChatDiaryDocument(
                entries = ChatDiaryEntryPolicy.compact(
                    entries = repaired,
                    maxEntries = MAX_CHAT_DIARY_ENTRIES,
                    protectedId = protectedId,
                ),
            ),
        )
        return changed
    }
}
