package com.labteto.dshmobile.local.chat

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Durable character diary projection.
 *
 * SessionEventLog remains the raw fact source. Diary entries are sparse narrative projections:
 * grounded event anchors plus the character's private interpretation. They are never a second
 * transcript and are safe to invalidate when their source branch is rewritten.
 */
internal class ChatDiaryStore(
    private val root: File,
    private val json: Json,
) {
    init { root.mkdirs() }

    private val file = File(root, "diary.json")
    private val backup = File(root, "diary.json.bak")
    private var cached: ChatDiaryDocument? = null
    private var cachedStamp: Pair<Long, Long>? = null

    @Synchronized
    fun record(request: ChatDiaryWriteRequest): ChatDiaryEntry? {
        val delta = sanitizeDelta(request) ?: return null
        val now = System.currentTimeMillis()
        val document = readDocument()
        val entries = document.entries.toMutableList()
        val disclosure = normalizedDisclosure(delta.disclosure, request.sourceMode)

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
            sourceSessionId = request.sourceSessionId,
            sourceUserMessageIds = request.sourceUserMessageIds.cleanIds(),
            sourceAssistantMessageIds = request.sourceAssistantMessageIds.cleanIds(),
            generation = request.generation,
            createdAt = now,
            updatedAt = now,
        )

        val duplicateIndex = entries.indexOfLast { existing ->
            existing.active &&
                existing.subjectKey == candidate.subjectKey &&
                now - existing.updatedAt <= DUPLICATE_WINDOW_MILLIS &&
                diarySimilarity(existing.event, candidate.event) >= DUPLICATE_SIMILARITY
        }
        val saved = if (duplicateIndex >= 0) {
            val previous = entries[duplicateIndex]
            previous.copy(
                event = richer(previous.event, candidate.event, MAX_EVENT_CHARS),
                feeling = richer(previous.feeling, candidate.feeling, MAX_FEELING_CHARS),
                innerThought = richer(previous.innerThought, candidate.innerThought, MAX_THOUGHT_CHARS),
                relationshipMeaning = richer(
                    previous.relationshipMeaning,
                    candidate.relationshipMeaning,
                    MAX_RELATIONSHIP_CHARS,
                ),
                unresolvedEcho = richer(previous.unresolvedEcho, candidate.unresolvedEcho, MAX_ECHO_CHARS),
                importance = maxOf(previous.importance, candidate.importance),
                disclosure = stricterDisclosure(previous.disclosure, candidate.disclosure),
                sourceUserMessageIds = (previous.sourceUserMessageIds + candidate.sourceUserMessageIds)
                    .distinct().takeLast(MAX_SOURCE_IDS),
                sourceAssistantMessageIds = (previous.sourceAssistantMessageIds + candidate.sourceAssistantMessageIds)
                    .distinct().takeLast(MAX_SOURCE_IDS),
                updatedAt = now,
            ).also { entries[duplicateIndex] = it }
        } else {
            candidate.also(entries::add)
        }

        writeDocument(
            ChatDiaryDocument(
                entries = compact(entries, MAX_ENTRIES, protectedId = saved.id),
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
        val queryCore = normalizeDiaryText(query)
        val queryTerms = diaryTerms(query)
        val broad = shouldRecallDiary(query)
        val now = System.currentTimeMillis()

        return readDocument().entries.asSequence()
            .filter { entry ->
                entry.active &&
                    entry.subjectKey == cleanSubject &&
                    (!groupAudience || entry.disclosure != ChatDiaryDisclosure.PRIVATE)
            }
            .map { entry ->
                val lexical = diaryTerms(entry.event + " " + entry.relationshipMeaning + " " + entry.unresolvedEcho)
                    .count(queryTerms::contains)
                val phrase = if (queryCore.isBlank()) 0 else {
                    (diarySimilarity(queryCore, entry.searchText()) * 100).toInt()
                }
                val ageDays = ((now - entry.updatedAt).coerceAtLeast(0L) / DAY_MILLIS).toInt()
                val recency = (30 - ageDays).coerceIn(0, 30)
                entry to (lexical * 24 + phrase + entry.importance * 12 + recency)
            }
            .filter { (entry, score) -> broad || score >= MIN_RECALL_SCORE || entry.importance >= 5 }
            .sortedWith(
                compareByDescending<Pair<ChatDiaryEntry, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt },
            )
            .map(Pair<ChatDiaryEntry, Int>::first)
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
        val entries = readDocument().entries.toMutableList()
        val now = System.currentTimeMillis()
        var changed = 0
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (!entry.active || entry.sourceSessionId != sourceSessionId) return@forEach
            val sourcedIds = entry.sourceUserMessageIds + entry.sourceAssistantMessageIds
            val invalid = if (discardedMessageIds.isNotEmpty()) {
                sourcedIds.any(discardedMessageIds::contains)
            } else {
                entry.createdAt >= createdAtInclusive
            }
            if (invalid) {
                entries[index] = entry.copy(active = false, updatedAt = now)
                changed++
            }
        }
        if (changed > 0) writeDocument(ChatDiaryDocument(entries = entries))
        return changed
    }

    @Synchronized
    fun listActive(subjectKey: String, limit: Int = 100): List<ChatDiaryEntry> =
        readDocument().entries.asSequence()
            .filter { it.active && it.subjectKey == subjectKey }
            .sortedByDescending(ChatDiaryEntry::updatedAt)
            .take(limit.coerceIn(1, MAX_ENTRIES))
            .toList()

    private fun sanitizeDelta(request: ChatDiaryWriteRequest): ChatDiaryDelta? {
        val raw = request.delta ?: return null
        if (request.subjectKey.isBlank() || request.sourceSessionId.isBlank()) return null
        val significance = request.turnSignificance.trim().uppercase()
        if (significance == "NONE") return null

        val event = raw.event.trim().take(MAX_EVENT_CHARS)
        val importance = raw.importance.coerceIn(0, 5)
        if (event.length < MIN_EVENT_CHARS || importance < MIN_IMPORTANCE) return null
        if (significance != "MAJOR" && importance < MINOR_IMPORTANCE) return null
        if (!diaryEventGrounded(event, request.evidenceText)) return null

        return ChatDiaryDelta(
            event = event,
            feeling = raw.feeling.trim().take(MAX_FEELING_CHARS),
            innerThought = raw.innerThought.trim().take(MAX_THOUGHT_CHARS),
            relationshipMeaning = raw.relationshipMeaning.trim().take(MAX_RELATIONSHIP_CHARS),
            unresolvedEcho = raw.unresolvedEcho.trim().take(MAX_ECHO_CHARS),
            importance = importance,
            disclosure = raw.disclosure.trim().uppercase(),
        ).takeIf { delta ->
            significance == "MAJOR" ||
                delta.feeling.isNotBlank() ||
                delta.innerThought.isNotBlank() ||
                delta.relationshipMeaning.isNotBlank() ||
                delta.unresolvedEcho.isNotBlank()
        }
    }

    private fun diaryEventGrounded(event: String, evidence: String): Boolean {
        val a = normalizeDiaryText(event)
        val b = normalizeDiaryText(evidence)
        if (a.length < 2 || b.length < 2) return false
        if (b.contains(a) || a.contains(b.take(120))) return true
        val aa = diaryBigrams(a)
        val bb = diaryBigrams(b)
        if (aa.isEmpty() || bb.isEmpty()) return false
        val shared = aa.count(bb::contains)
        val coverage = shared.toDouble() / aa.size.toDouble()
        return shared >= 2 && coverage >= MIN_EVIDENCE_COVERAGE
    }

    private fun normalizedDisclosure(raw: String, mode: ChatDiarySourceMode): ChatDiaryDisclosure {
        val requested = runCatching { ChatDiaryDisclosure.valueOf(raw.trim().uppercase()) }.getOrNull()
        return when (mode) {
            ChatDiarySourceMode.DIRECT -> when (requested) {
                ChatDiaryDisclosure.PRIVATE -> ChatDiaryDisclosure.PRIVATE
                else -> ChatDiaryDisclosure.SHAREABLE
            }
            ChatDiarySourceMode.GROUP -> when (requested) {
                ChatDiaryDisclosure.PRIVATE -> ChatDiaryDisclosure.PRIVATE
                else -> ChatDiaryDisclosure.PUBLIC
            }
        }
    }

    private fun stricterDisclosure(
        left: ChatDiaryDisclosure,
        right: ChatDiaryDisclosure,
    ): ChatDiaryDisclosure = when {
        left == ChatDiaryDisclosure.PRIVATE || right == ChatDiaryDisclosure.PRIVATE ->
            ChatDiaryDisclosure.PRIVATE
        left == ChatDiaryDisclosure.SHAREABLE || right == ChatDiaryDisclosure.SHAREABLE ->
            ChatDiaryDisclosure.SHAREABLE
        else -> ChatDiaryDisclosure.PUBLIC
    }

    private fun compact(
        entries: List<ChatDiaryEntry>,
        maxEntries: Int,
        protectedId: String,
    ): List<ChatDiaryEntry> {
        if (entries.size <= maxEntries) return entries
        val retainedIds = entries.asSequence()
            .filter(ChatDiaryEntry::active)
            .sortedWith(
                compareByDescending<ChatDiaryEntry> { if (it.id == protectedId) 1 else 0 }
                    .thenByDescending(ChatDiaryEntry::importance)
                    .thenByDescending(ChatDiaryEntry::updatedAt),
            )
            .take(maxEntries)
            .mapTo(hashSetOf(), ChatDiaryEntry::id)
        return entries.filter { it.id in retainedIds }
    }

    private fun readDocument(): ChatDiaryDocument {
        val stamp = stamp()
        cached?.takeIf { cachedStamp == stamp }?.let { return it }
        val decoded = decode(file) ?: decode(backup) ?: ChatDiaryDocument()
        cached = decoded
        cachedStamp = stamp()
        return decoded
    }

    private fun decode(target: File): ChatDiaryDocument? {
        if (!target.isFile) return null
        return runCatching {
            json.decodeFromString(ChatDiaryDocument.serializer(), target.readText())
        }.getOrNull()
    }

    private fun writeDocument(document: ChatDiaryDocument) {
        root.mkdirs()
        val temp = File(root, "diary.json.tmp")
        temp.writeText(json.encodeToString(ChatDiaryDocument.serializer(), document))
        if (file.isFile) runCatching { file.copyTo(backup, overwrite = true) }
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!backup.isFile) runCatching { file.copyTo(backup, overwrite = true) }
        cached = document
        cachedStamp = stamp()
    }

    private fun stamp(): Pair<Long, Long> =
        (file.lastModified() to file.length())

    private fun ChatDiaryEntry.searchText(): String =
        listOf(event, feeling, innerThought, relationshipMeaning, unresolvedEcho)
            .filter(String::isNotBlank)
            .joinToString(" ")

    private fun List<String>.cleanIds(): List<String> =
        asSequence().map(String::trim).filter(String::isNotBlank).distinct().takeLast(MAX_SOURCE_IDS).toList()

    private fun richer(left: String, right: String, limit: Int): String =
        listOf(left.trim(), right.trim())
            .filter(String::isNotBlank)
            .maxByOrNull(String::length)
            .orEmpty()
            .take(limit)

    private fun diarySimilarity(left: String, right: String): Double {
        val a = diaryBigrams(normalizeDiaryText(left))
        val b = diaryBigrams(normalizeDiaryText(right))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count(b::contains).toDouble()
        return shared / a.union(b).size.toDouble()
    }

    private fun diaryTerms(text: String): Set<String> {
        val normalized = normalizeDiaryText(text)
        val terms = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { it.value }
            .take(48)
            .toMutableSet()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            match.value.windowed(2).take(24).forEach(terms::add)
        }
        return terms
    }

    private fun normalizeDiaryText(text: String): String =
        text.lowercase()
            .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
            .take(1_200)

    private fun diaryBigrams(text: String): Set<String> =
        if (text.length < 2) emptySet()
        else (0 until text.length - 1).mapTo(linkedSetOf()) { text.substring(it, it + 2) }

    private companion object {
        const val MAX_ENTRIES = 4_000
        const val MAX_SOURCE_IDS = 16
        const val MAX_EVENT_CHARS = 320
        const val MAX_FEELING_CHARS = 220
        const val MAX_THOUGHT_CHARS = 260
        const val MAX_RELATIONSHIP_CHARS = 220
        const val MAX_ECHO_CHARS = 180
        const val MIN_EVENT_CHARS = 6
        const val MIN_IMPORTANCE = 2
        const val MINOR_IMPORTANCE = 3
        const val MIN_RECALL_SCORE = 36
        const val DUPLICATE_WINDOW_MILLIS = 6 * 60 * 60 * 1_000L
        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        const val DUPLICATE_SIMILARITY = 0.72
        const val MIN_EVIDENCE_COVERAGE = 0.18
    }
}
