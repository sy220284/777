package com.labteto.dshmobile.local.chat

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Durable character diary projection.
 *
 * SessionEventLog remains the raw fact source. This store owns diary-level orchestration only;
 * persistence lives in [ChatDiaryDocumentStore] and entry semantics live in [ChatDiaryEntryPolicy].
 */
internal class ChatDiaryStore(
    root: File,
    json: Json,
) {
    private val documents = ChatDiaryDocumentStore(root, json)
    private val transfer = ChatDiaryTransferStore(documents, ChatDiaryRollbackRecovery(root, json))

    @Synchronized
    fun record(request: ChatDiaryWriteRequest): ChatDiaryEntry? {
        val delta = ChatDiaryEntryPolicy.sanitizeDelta(request) ?: return null
        val now = System.currentTimeMillis()
        val document = documents.read()
        request.projectionId?.let { id ->
            document.entries.firstOrNull { entry -> entry.revisions.any { it.projectionId == id } }
                ?.let { return it }
        }
        val entries = document.entries.toMutableList()
        val disclosure = ChatDiaryEntryPolicy.disclosureFor(request, delta)
        val candidateSources = ChatDiaryEntryPolicy.sourcesFor(request)
        val candidateRevision = ChatDiaryRevision(
            event = delta.event,
            feeling = delta.feeling,
            innerThought = delta.innerThought,
            relationshipMeaning = delta.relationshipMeaning,
            unresolvedEcho = delta.unresolvedEcho,
            importance = delta.importance,
            disclosure = disclosure,
            sources = candidateSources,
            updatedAt = now,
            projectionId = request.projectionId,
        )
        val candidate = ChatDiaryEntry(
            id = UUID.randomUUID().toString(),
            subjectKey = request.subjectKey.trim(),
            personaName = request.personaName.trim().take(120),
            event = delta.event,
            feeling = delta.feeling,
            innerThought = delta.innerThought,
            relationshipMeaning = delta.relationshipMeaning,
            unresolvedEcho = delta.unresolvedEcho,
            importance = delta.importance,
            sourceMode = request.sourceMode,
            disclosure = disclosure,
            sources = candidateSources,
            revisions = listOf(candidateRevision),
            generation = request.generation,
            createdAt = now,
            updatedAt = now,
        )

        val duplicateIndex = entries.indexOfLast { existing ->
            ChatDiaryEntryPolicy.isRefinementCandidate(existing, candidate, now)
        }
        val saved = if (duplicateIndex >= 0) {
            val previous = entries[duplicateIndex]
            ChatDiaryEntryPolicy.rebuild(
                entry = previous,
                revisions = ChatDiaryEntryPolicy.revisionsOf(previous) + candidateRevision,
                updatedAt = now,
            ).also { entries[duplicateIndex] = it }
        } else {
            candidate.also(entries::add)
        }

        entries.indices.forEach { index ->
            val existing = entries[index]
            if (existing.id != saved.id && ChatDiarySupersessionPolicy.supersedes(existing, saved)) {
                entries[index] = existing.copy(supersededBy = saved.id)
            }
        }

        documents.write(
            ChatDiaryDocument(
                entries = ChatDiaryEntryPolicy.compact(
                    entries = entries,
                    maxEntries = MAX_CHAT_DIARY_ENTRIES,
                    protectedId = saved.id,
                ),
            ),
        )
        return saved
    }


    /**
     * Explicit author correction of a derived diary event. Original conversation and previous
     * revisions remain untouched; the user revision takes precedence in active recall.
     *
     * The expected revision prevents an old screen editing a newer fact and the subject key
     * prevents actions on one character from mutating another character's diary.
     */
    @Synchronized
    fun correctEntry(
        subjectKey: String,
        id: String,
        expectedUpdatedAt: Long,
        correction: ChatDiaryDelta,
    ): Boolean {
        val event = correction.event.trim().take(ChatDiaryBounds.MAX_EVENT_CHARS)
        if (subjectKey.isBlank() || event.isBlank()) return false
        val existing = documents.read()
        val index = existing.entries.indexOfFirst {
            it.id == id && it.subjectKey == subjectKey && it.active &&
                it.updatedAt == expectedUpdatedAt
        }
        if (index < 0) return false
        val previous = existing.entries[index]
        val now = maxOf(System.currentTimeMillis(), previous.updatedAt + 1)
        val revision = ChatDiaryRevision(
            event = event,
            feeling = correction.feeling.trim().take(ChatDiaryBounds.MAX_FEELING_CHARS),
            innerThought = correction.innerThought.trim().take(ChatDiaryBounds.MAX_THOUGHT_CHARS),
            relationshipMeaning = correction.relationshipMeaning.trim().take(ChatDiaryBounds.MAX_RELATIONSHIP_CHARS),
            unresolvedEcho = correction.unresolvedEcho.trim().take(ChatDiaryBounds.MAX_ECHO_CHARS),
            importance = previous.importance,
            disclosure = previous.disclosure,
            sources = previous.sources,
            updatedAt = now,
            userCorrected = true,
        )
        val revised = ChatDiaryEntryPolicy.rebuild(
            previous.copy(supersededBy = null),
            ChatDiaryEntryPolicy.revisionsOf(previous) + revision,
            now,
        )
        val entries = existing.entries.toMutableList().apply { this[index] = revised }
        documents.write(existing.copy(entries = ChatDiarySupersessionPolicy.repairLinks(entries)))
        return true
    }

    /** A disabled diary entry is no longer eligible for active or historical recall. */
    @Synchronized
    fun deactivateEntry(subjectKey: String, id: String, expectedUpdatedAt: Long): Boolean {
        if (subjectKey.isBlank()) return false
        val existing = documents.read()
        val index = existing.entries.indexOfFirst {
            it.id == id && it.subjectKey == subjectKey && it.active &&
                it.updatedAt == expectedUpdatedAt
        }
        if (index < 0) return false
        val previous = existing.entries[index]
        val entries = existing.entries.toMutableList().apply {
            this[index] = previous.copy(
                active = false,
                updatedAt = maxOf(System.currentTimeMillis(), previous.updatedAt + 1),
            )
        }
        documents.write(existing.copy(entries = ChatDiarySupersessionPolicy.repairLinks(entries)))
        return true
    }

    @Synchronized
    fun search(
        query: String,
        subjectKey: String,
        groupAudience: Boolean,
        maxItems: Int,
    ): List<ChatDiaryEntry> =
        ChatDiaryRecallEngine.search(
            entries = documents.read().entries,
            query = query,
            subjectKey = subjectKey,
            groupAudience = groupAudience,
            maxItems = maxItems,
        )

    @Synchronized
    fun rollbackSourceSessionFrom(
        sourceSessionId: String,
        createdAtInclusive: Long,
        discardedMessageIds: Set<String> = emptySet(),
    ): Int {
        if (sourceSessionId.isBlank()) return 0
        val entries = documents.read().entries.toMutableList()
        val now = System.currentTimeMillis()
        var changed = 0
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (!entry.active || entry.sources.none { it.sessionId == sourceSessionId }) return@forEach
            val revisions = ChatDiaryEntryPolicy.revisionsOf(entry)
            val remaining = revisions.filterNot { revision ->
                revision.sources.any { source ->
                    source.sessionId == sourceSessionId && if (discardedMessageIds.isNotEmpty()) {
                        source.userMessageId in discardedMessageIds ||
                            source.assistantMessageId in discardedMessageIds
                    } else {
                        revision.updatedAt >= createdAtInclusive
                    }
                }
            }
            if (remaining.size != revisions.size) {
                entries[index] = if (remaining.isEmpty()) {
                    entry.copy(
                        active = false,
                        sources = emptyList(),
                        revisions = emptyList(),
                        updatedAt = now,
                    )
                } else {
                    ChatDiaryEntryPolicy.rebuild(entry, remaining, now)
                }
                changed++
            }
        }
        if (changed > 0) {
            documents.write(ChatDiaryDocument(entries = ChatDiarySupersessionPolicy.repairLinks(entries)))
        }
        return changed
    }

    @Synchronized
    fun detachSourceSessions(sessionIds: Set<String>): Int {
        if (sessionIds.isEmpty()) return 0
        val entries = documents.read().entries.toMutableList()
        val now = System.currentTimeMillis()
        var changed = 0
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (!entry.active || entry.sources.none { it.sessionId in sessionIds }) return@forEach
            val revisions = ChatDiaryEntryPolicy.revisionsOf(entry)
            val remaining = revisions.filterNot { revision ->
                revision.sources.any { it.sessionId in sessionIds }
            }
            if (remaining.size != revisions.size) {
                entries[index] = if (remaining.isEmpty()) {
                    entry.copy(
                        active = false,
                        sources = emptyList(),
                        revisions = emptyList(),
                        updatedAt = now,
                    )
                } else {
                    ChatDiaryEntryPolicy.rebuild(entry, remaining, now)
                }
                changed++
            }
        }
        if (changed > 0) {
            documents.write(ChatDiaryDocument(entries = ChatDiarySupersessionPolicy.repairLinks(entries)))
        }
        return changed
    }

    @Synchronized
    fun listActive(subjectKey: String, limit: Int = 100): List<ChatDiaryEntry> {
        val stored = documents.read().entries
        // Older files could mark a fact obsolete based solely on its disclosure label.
        // Normalize the view without rewriting the saved source/history on a read.
        val current = if (stored.any { it.supersededBy != null }) {
            ChatDiarySupersessionPolicy.repairLinks(stored)
        } else stored
        return current.asSequence()
            .filter { it.active && it.subjectKey == subjectKey }
            .sortedByDescending(ChatDiaryEntry::updatedAt)
            .take(limit.coerceIn(1, MAX_CHAT_DIARY_ENTRIES))
            .toList()
    }

    @Synchronized
    internal fun listForTransfer(subjectKey: String): List<ChatDiaryEntry> = transfer.list(subjectKey)

    @Synchronized
    internal fun <T> importForTransfer(subjectKey: String, personaName: String, entries: List<ChatDiaryEntry>, commit: () -> T): T =
        transfer.import(subjectKey, personaName, entries, commit)
}