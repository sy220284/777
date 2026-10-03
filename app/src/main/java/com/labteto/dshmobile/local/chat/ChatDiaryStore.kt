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

    @Synchronized
    fun record(request: ChatDiaryWriteRequest): ChatDiaryEntry? {
        val delta = ChatDiaryEntryPolicy.sanitizeDelta(request) ?: return null
        val now = System.currentTimeMillis()
        val document = documents.read()
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

    @Synchronized
    fun search(
        query: String,
        subjectKey: String,
        groupAudience: Boolean,
        maxItems: Int,
    ): List<ChatDiaryEntry> {
        val cleanSubject = subjectKey.trim()
        if (cleanSubject.isBlank()) return emptyList()
        val queryCore = ChatDiaryEntryPolicy.normalizeQuery(query)
        val queryTerms = ChatDiaryEntryPolicy.queryTerms(query)
        val broad = isExplicitDiaryRecall(query)
        val hasSpecificAnchor = broad && ChatDiaryEntryPolicy.hasSpecificRecallAnchor(query)
        val now = System.currentTimeMillis()

        val eligible = documents.read().entries.asSequence()
            .filter { entry ->
                entry.active &&
                    entry.subjectKey == cleanSubject &&
                    (!groupAudience || entry.disclosure != ChatDiaryDisclosure.PRIVATE)
            }
            .toList()
        val candidates = if (broad || eligible.size <= MAX_NORMAL_RECALL_CANDIDATES) {
            eligible
        } else {
            val recent = eligible.sortedByDescending(ChatDiaryEntry::updatedAt)
                .take(MAX_NORMAL_RECALL_CANDIDATES)
            val important = eligible.asSequence()
                .filter { it.importance >= IMPORTANT_RECALL_THRESHOLD }
                .sortedByDescending(ChatDiaryEntry::updatedAt)
                .take(MAX_IMPORTANT_RECALL_CANDIDATES)
                .toList()
            (recent + important).distinctBy(ChatDiaryEntry::id)
        }

        return candidates.asSequence()
            .map { entry ->
                entry to ChatDiaryEntryPolicy.matchScore(entry, queryCore, queryTerms, now)
            }
            .filter { (_, score) ->
                ChatDiaryEntryPolicy.isRecallMatch(score, broad, hasSpecificAnchor)
            }
            .sortedWith(
                compareByDescending<Pair<ChatDiaryEntry, ChatDiaryMatchScore>> { it.second.total }
                    .thenByDescending { it.first.updatedAt },
            )
            .map(Pair<ChatDiaryEntry, ChatDiaryMatchScore>::first)
            .take(maxItems.coerceIn(1, 6))
            .toList()
    }

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
        if (changed > 0) documents.write(ChatDiaryDocument(entries = entries))
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
        if (changed > 0) documents.write(ChatDiaryDocument(entries = entries))
        return changed
    }

    @Synchronized
    fun listActive(subjectKey: String, limit: Int = 100): List<ChatDiaryEntry> =
        documents.read().entries.asSequence()
            .filter { it.active && it.subjectKey == subjectKey }
            .sortedByDescending(ChatDiaryEntry::updatedAt)
            .take(limit.coerceIn(1, MAX_CHAT_DIARY_ENTRIES))
            .toList()

    private companion object {
        const val MAX_NORMAL_RECALL_CANDIDATES = 256
        const val MAX_IMPORTANT_RECALL_CANDIDATES = 64
        const val IMPORTANT_RECALL_THRESHOLD = 4
    }
}
