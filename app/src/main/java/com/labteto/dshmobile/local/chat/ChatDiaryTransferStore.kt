package com.labteto.dshmobile.local.chat

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Persona-transfer projection for the single [ChatDiaryStore] owner.
 *
 * This helper never creates a second document store. The caller holds ChatDiaryStore's monitor for
 * the complete import + Gallery commit window, so rollback cannot overwrite a concurrent diary write.
 */
internal class ChatDiaryTransferStore(
    private val documents: ChatDiaryDocumentStore,
) {
    fun list(subjectKey: String): List<ChatDiaryEntry> =
        documents.read().entries.asSequence()
            .filter { it.subjectKey == subjectKey }
            .sortedWith(compareBy<ChatDiaryEntry>(ChatDiaryEntry::createdAt).thenBy(ChatDiaryEntry::id))
            .take(MAX_CHAT_DIARY_ENTRIES)
            .toList()

    fun <T> import(
        subjectKey: String,
        personaName: String,
        transferred: List<ChatDiaryEntry>,
        commit: () -> T,
    ): T {
        val targetSubjectKey = subjectKey.trim()
        if (targetSubjectKey.isBlank() || transferred.isEmpty()) return commit()

        val before = documents.read()
        val merged = merge(before, targetSubjectKey, personaName, transferred)
        if (merged.changed > 0) documents.write(merged.document)
        return try {
            commit()
        } catch (error: Throwable) {
            if (merged.changed > 0) {
                try {
                    documents.restore(before)
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
            }
            throw error
        }
    }

    private fun merge(
        before: ChatDiaryDocument,
        targetSubjectKey: String,
        personaName: String,
        transferred: List<ChatDiaryEntry>,
    ): MergeResult {
        val entries = before.entries.toMutableList()
        val existingById = entries.associateBy(ChatDiaryEntry::id)
        val candidates = transferred.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id.trim() }
            .take(MAX_CHAT_DIARY_ENTRIES)
            .toList()
        if (candidates.isEmpty()) return MergeResult(before, 0)

        val reserved = hashSetOf<String>()
        val idMap = linkedMapOf<String, String>()
        candidates.forEach { entry ->
            val sourceId = entry.id.trim()
            idMap[sourceId] = targetId(sourceId, targetSubjectKey, existingById, reserved)
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
                revisions = raw.revisions.takeLast(8).map { it.copy(sources = emptyList()) },
                supersededBy = raw.supersededBy?.trim()?.takeIf(String::isNotBlank)?.let(idMap::get),
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
        if (changed == 0) return MergeResult(before, 0)

        val repaired = ChatDiarySupersessionPolicy.repairLinks(entries)
        val protectedId = repaired.asSequence()
            .filter(ChatDiaryEntry::active)
            .maxByOrNull(ChatDiaryEntry::updatedAt)
            ?.id
            .orEmpty()
        return MergeResult(
            document = before.copy(
                entries = ChatDiaryEntryPolicy.compact(
                    entries = repaired,
                    maxEntries = MAX_CHAT_DIARY_ENTRIES,
                    protectedId = protectedId,
                ),
            ),
            changed = changed,
        )
    }

    private fun targetId(
        sourceId: String,
        targetSubjectKey: String,
        existingById: Map<String, ChatDiaryEntry>,
        reserved: MutableSet<String>,
    ): String {
        val direct = existingById[sourceId]
        if ((direct == null || direct.subjectKey == targetSubjectKey) && reserved.add(sourceId)) {
            return sourceId
        }
        var attempt = 0
        while (true) {
            val seed = "777:persona-diary:$targetSubjectKey:$sourceId:$attempt"
            val candidate = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()
            val existing = existingById[candidate]
            if ((existing == null || existing.subjectKey == targetSubjectKey) && reserved.add(candidate)) {
                return candidate
            }
            attempt++
        }
    }

    private data class MergeResult(
        val document: ChatDiaryDocument,
        val changed: Int,
    )
}
