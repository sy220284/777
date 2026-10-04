package com.labteto.dshmobile.local.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class MemoryStore internal constructor(
    root: File,
    json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/memory"), json)

    private val documents = MemoryDocumentStore(root, json)
    private var lastConsolidatedAt: Long = 0L

    @Synchronized
    fun remember(
        content: String,
        scope: MemoryScope,
        kind: MemoryKind = MemoryKind.FACT,
        projectId: String? = null,
        lineageId: String? = null,
        sourceSessionId: String? = null,
        sourceMessageId: String? = null,
        subjectKey: String? = null,
        disclosure: MemoryDisclosure = MemoryDisclosure.SHAREABLE,
        disclosureExplicit: Boolean = false,
        importance: Int = 50,
        pinned: Boolean = false,
        replaceIds: Set<String> = emptySet(),
    ): MemoryRecord {
        val clean = content.trim().take(MAX_MEMORY_CONTENT_CHARS)
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        require(scope != MemoryScope.PROJECT || !projectId.isNullOrBlank()) { "项目记忆缺少项目编号" }
        require(scope != MemoryScope.LINEAGE || !lineageId.isNullOrBlank()) { "对话链记忆缺少对话链编号" }

        val now = System.currentTimeMillis()
        val records = documents.read().records.toMutableList()
        val duplicateIndex = records.indexOfFirst {
            it.active &&
                it.scope == scope &&
                it.projectId == projectId &&
                it.lineageId == lineageId &&
                it.subjectKey == subjectKey &&
                it.content.equals(clean, ignoreCase = true)
        }
        if (duplicateIndex >= 0) {
            val incomingSource = memorySourceRef(sourceSessionId, sourceMessageId)
            val mergedSources = mergeMemorySourceMessages(
                records[duplicateIndex].sourceMessages,
                incomingSource,
            )
            val existing = records[duplicateIndex]
            val refreshed = existing.copy(
                kind = kind,
                importance = importance.coerceIn(0, 100),
                pinned = pinned || records[duplicateIndex].pinned,
                sourceSessionId = sourceSessionId ?: records[duplicateIndex].sourceSessionId,
                sourceMessages = mergedSources.sources,
                hasUnboundSource = records[duplicateIndex].hasUnboundSource ||
                    incomingSource == null ||
                    mergedSources.truncated,
                subjectKey = subjectKey ?: existing.subjectKey,
                disclosure = if (disclosureExplicit) disclosure else existing.disclosure,
                disclosureExplicit = if (disclosureExplicit) true else existing.disclosureExplicit,
                updatedAt = now,
            )
            records[duplicateIndex] = refreshed
            replaceIds.asSequence()
                .filter { it != refreshed.id }
                .forEach { replacedId ->
                    val index = records.indexOfFirst { it.id == replacedId && it.active }
                    if (index >= 0) {
                        records[index] = records[index].copy(
                            active = false,
                            supersededBy = refreshed.id,
                            updatedAt = now,
                        )
                    }
                }
            documents.write(MemoryDocument(records = records))
            return refreshed
        }

        val record = MemoryRecord(
            id = UUID.randomUUID().toString(),
            scope = scope,
            kind = kind,
            content = clean,
            projectId = projectId,
            lineageId = lineageId,
            sourceSessionId = sourceSessionId,
            sourceMessages = memorySourceRef(sourceSessionId, sourceMessageId)?.let(::listOf).orEmpty(),
            hasUnboundSource = sourceMessageId.isNullOrBlank(),
            subjectKey = subjectKey,
            disclosure = disclosure,
            disclosureExplicit = disclosureExplicit,
            importance = importance.coerceIn(0, 100),
            pinned = pinned,
            createdAt = now,
            updatedAt = now,
        )
        replaceIds.forEach { replacedId ->
            val index = records.indexOfFirst { it.id == replacedId && it.active }
            if (index >= 0) {
                records[index] = records[index].copy(
                    active = false,
                    supersededBy = record.id,
                    updatedAt = now,
                )
            }
        }
        records += record
        documents.write(
            MemoryDocument(
                records = compactMemoryRecords(
                    records = records,
                    maxRecords = MAX_RECORDS,
                    protectedIds = setOf(record.id),
                ),
            ),
        )
        return record
    }

    @Synchronized
    fun update(
        id: String,
        content: String? = null,
        kind: MemoryKind? = null,
        importance: Int? = null,
        pinned: Boolean? = null,
    ): MemoryRecord {
        val records = documents.read().records.toMutableList()
        val index = records.indexOfFirst { it.id == id && it.active }
        require(index >= 0) { "长期记忆不存在或已停用：$id" }
        val current = records[index]
        val clean = content?.trim()?.take(MAX_MEMORY_CONTENT_CHARS) ?: current.content
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        val updated = current.copy(
            content = clean,
            kind = kind ?: current.kind,
            importance = (importance ?: current.importance).coerceIn(0, 100),
            pinned = pinned ?: current.pinned,
            updatedAt = System.currentTimeMillis(),
        )
        records[index] = updated
        documents.write(MemoryDocument(records = records))
        return updated
    }

    @Synchronized
    fun forget(id: String): Boolean {
        val records = documents.read().records.toMutableList()
        val index = records.indexOfFirst { it.id == id && it.active }
        if (index < 0) return false
        records[index] = records[index].copy(
            active = false,
            supersededBy = null,
            updatedAt = System.currentTimeMillis(),
        )
        documents.write(MemoryDocument(records = records))
        return true
    }

    /**
     * Roll back memories created by the discarded suffix of one chat timeline.
     *
     * A replacement memory may have superseded an older valid record. When the replacement belongs
     * to the discarded suffix, reactivate the predecessor instead of leaving a ghost tombstone.
     */
    @Synchronized
    fun rollbackSourceSessionFrom(
        sourceSessionId: String,
        createdAtInclusive: Long,
        discardedMessageIds: Set<String> = emptySet(),
    ): Int {
        if (sourceSessionId.isBlank()) return 0
        val records = documents.read().records.toMutableList()
        val invalidIds = linkedSetOf<String>()
        val now = System.currentTimeMillis()
        var changed = false

        records.indices.forEach { index ->
            val current = records[index]
            if (current.sourceMessages.isNotEmpty() && discardedMessageIds.isNotEmpty()) {
                val remaining = current.sourceMessages.filterNot { source ->
                    source.sessionId == sourceSessionId && source.messageId in discardedMessageIds
                }
                if (remaining.size != current.sourceMessages.size) {
                    if (remaining.isEmpty() && !current.hasUnboundSource) {
                        invalidIds += current.id
                    } else {
                        records[index] = current.copy(
                            sourceMessages = remaining,
                            sourceSessionId = if (
                                current.sourceSessionId == sourceSessionId &&
                                remaining.isNotEmpty()
                            ) {
                                remaining.last().sessionId
                            } else {
                                current.sourceSessionId
                            },
                            updatedAt = now,
                        )
                        changed = true
                    }
                }
            } else if (
                current.sourceSessionId == sourceSessionId &&
                current.createdAt >= createdAtInclusive
            ) {
                // Backward compatibility for memories written before message-level provenance.
                invalidIds += current.id
            }
        }
        if (invalidIds.isEmpty()) {
            if (changed) documents.write(MemoryDocument(records = records))
            return 0
        }

        records.indices.forEach { index ->
            val current = records[index]
            when {
                current.id in invalidIds -> {
                    if (current.active || current.supersededBy != null) {
                        records[index] = current.copy(
                            active = false,
                            supersededBy = null,
                            sourceMessages = emptyList(),
                            updatedAt = now,
                        )
                        changed = true
                    }
                }
                current.supersededBy in invalidIds -> {
                    records[index] = current.copy(
                        active = true,
                        supersededBy = null,
                        updatedAt = now,
                    )
                    changed = true
                }
            }
        }
        if (changed) documents.write(MemoryDocument(records = records))
        return invalidIds.size
    }

    /**
     * A deleted conversation must not remain as a dangling provenance pointer. The memory itself
     * is intentionally preserved because global/project/lineage memories are designed to outlive
     * one transcript; only the deleted source reference is detached.
     */
    @Synchronized
    fun detachSourceSessions(sessionIds: Set<String>): Int {
        if (sessionIds.isEmpty()) return 0
        val records = documents.read().records.toMutableList()
        val now = System.currentTimeMillis()
        var changed = 0
        records.indices.forEach { index ->
            val current = records[index]
            val remainingSources = current.sourceMessages.filterNot { it.sessionId in sessionIds }
            if (current.sourceSessionId in sessionIds || remainingSources.size != current.sourceMessages.size) {
                records[index] = current.copy(
                    sourceSessionId = current.sourceSessionId.takeUnless { it in sessionIds },
                    sourceMessages = remainingSources,
                    updatedAt = now,
                )
                changed++
            }
        }
        if (changed > 0) documents.write(MemoryDocument(records = records))
        return changed
    }

    @Synchronized
    fun search(
        query: String,
        allowedScopes: Set<MemoryScope>,
        projectId: String?,
        lineageId: String?,
        allowedKinds: Set<MemoryKind> = MemoryKind.values().toSet(),
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        recordFilter: (MemoryRecord) -> Boolean = { true },
    ): List<MemoryRecord> {
        val boundedItems = maxItems.coerceIn(1, 20)
        val boundedChars = maxChars.coerceIn(256, 12_000)
        val queryTerms = terms(query)
        val broadRecall = BROAD_RECALL_HINT.containsMatchIn(query)
        val candidateLimit = if (broadRecall) {
            MAX_RECALL_CANDIDATES
        } else {
            maxOf(MIN_RECALL_CANDIDATES, boundedItems * 4).coerceAtMost(MAX_RECALL_CANDIDATES)
        }

        // Stage 1: cheap lexical/scope filtering. Explicit recall questions keep a bounded fallback
        // set so "第一次/上次那件事" can still reach a memory whose wording is very different.
        val coarse = documents.read().records.asSequence()
            .filter { it.active && it.scope in allowedScopes && it.kind in allowedKinds }
            .filter(recordFilter)
            .filter {
                when (it.scope) {
                    MemoryScope.GLOBAL -> true
                    MemoryScope.PROJECT -> projectId != null && it.projectId == projectId
                    MemoryScope.LINEAGE -> lineageId != null && it.lineageId == lineageId
                }
            }
            .map { record ->
                val lexical = lexicalScore(record, queryTerms)
                Triple(record, lexical, coarseScore(record, lexical))
            }
            .filter { (record, lexical, _) -> lexical > 0 || record.pinned || broadRecall }
            .sortedWith(
                compareByDescending<Triple<MemoryRecord, Int, Int>> { it.third }
                    .thenByDescending { it.first.updatedAt },
            )
            .take(candidateLimit)
            .toList()

        // Stage 2: rerank only the small candidate set using phrase overlap and query intent.
        val reranked = coarse.asSequence()
            .map { (record, lexical, coarseValue) ->
                record to fineRecallScore(
                    record = record,
                    query = query,
                    lexical = lexical,
                    coarse = coarseValue,
                )
            }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt },
            )
            .map(Pair<MemoryRecord, Int>::first)
            .toList()

        var used = 0
        val selected = mutableListOf<MemoryRecord>()
        for (record in reranked) {
            val cost = record.content.length + 32
            if (selected.isNotEmpty() && used + cost > boundedChars) break
            selected += record
            used += cost
            if (selected.size >= boundedItems) break
        }
        return selected
    }

    /**
     * Low-frequency maintenance for long-lived memory stores.
     * Called after durable writes; the in-process interval avoids turning every memory capture into
     * another full scan.
     */
    @Synchronized
    fun consolidateIfDue(
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Int {
        if (!force && lastConsolidatedAt > 0L && now - lastConsolidatedAt < CONSOLIDATION_INTERVAL_MILLIS) {
            return 0
        }
        val current = documents.read().records
        val consolidated = consolidateMemoryRecords(current, MAX_SOURCE_MESSAGES)
        val compacted = compactMemoryRecords(consolidated.records, MAX_RECORDS)
        if (consolidated.mergedCount > 0 || compacted.size != current.size) {
            documents.write(MemoryDocument(records = compacted))
        }
        lastConsolidatedAt = now
        return consolidated.mergedCount
    }

    @Synchronized
    fun listActive(
        allowedScopes: Set<MemoryScope>,
        projectId: String?,
        lineageId: String?,
        limit: Int = 50,
    ): List<MemoryRecord> = documents.read().records.asSequence()
        .filter { it.active && it.scope in allowedScopes }
        .filter {
            when (it.scope) {
                MemoryScope.GLOBAL -> true
                MemoryScope.PROJECT -> projectId != null && it.projectId == projectId
                MemoryScope.LINEAGE -> lineageId != null && it.lineageId == lineageId
            }
        }
        .sortedByDescending(MemoryRecord::updatedAt)
        .take(limit.coerceIn(1, MAX_RECORDS))
        .toList()

    private fun lexicalScore(record: MemoryRecord, queryTerms: Set<String>): Int {
        if (queryTerms.isEmpty()) return 0
        val contentTerms = terms(record.content)
        val overlap = queryTerms.count(contentTerms::contains)
        val direct = if (queryTerms.any { record.content.contains(it, ignoreCase = true) }) 8 else 0
        return overlap * 12 + direct
    }

    private fun coarseScore(record: MemoryRecord, lexical: Int): Int =
        lexical + (if (record.pinned) 12 else 0) + record.importance / 10

    private fun fineRecallScore(
        record: MemoryRecord,
        query: String,
        lexical: Int,
        coarse: Int,
    ): Int {
        val queryCore = canonicalMemoryIdentity(query)
        val contentCore = canonicalMemoryIdentity(record.content)
        val phrase = phraseOverlapScore(queryCore, contentCore)
        val kindBoost = when {
            RELATIONSHIP_STATUS_QUERY.containsMatchIn(query) &&
                record.kind == MemoryKind.RELATIONSHIP_STATE -> 50
            EPISODIC_RECALL_QUERY.containsMatchIn(query) &&
                record.kind == MemoryKind.RELATIONSHIP_FACT -> 35
            PREFERENCE_RECALL_QUERY.containsMatchIn(query) &&
                record.kind == MemoryKind.RELATIONSHIP_PREFERENCE -> 30
            else -> 0
        }
        return coarse * 10 + lexical * 8 + phrase + kindBoost
    }

    private fun phraseOverlapScore(left: String, right: String): Int {
        if (left.length < 2 || right.length < 2) return 0
        if (left.contains(right) || right.contains(left)) return 80
        val a = (0 until left.length - 1).mapTo(linkedSetOf()) { left.substring(it, it + 2) }
        val b = (0 until right.length - 1).mapTo(linkedSetOf()) { right.substring(it, it + 2) }
        if (a.isEmpty() || b.isEmpty()) return 0
        val shared = a.count(b::contains)
        return (shared * 60) / minOf(a.size, b.size)
    }

    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase().take(4_000)
        val words = Regex("[\\p{L}\\p{N}_-]{2,}")
            .findAll(normalized)
            .map { it.value }
            .take(64)
            .toMutableSet()
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(normalized).forEach { match ->
            match.value.windowed(2).take(32).forEach(words::add)
        }
        return words
    }


    private companion object {
        const val MAX_MEMORY_CONTENT_CHARS = 2_000
        const val MAX_RECORDS = 2_000
        const val DEFAULT_MAX_ITEMS = 6
        const val DEFAULT_MAX_CHARS = 3_500
        const val MAX_SOURCE_MESSAGES = 16
        const val MIN_RECALL_CANDIDATES = 12
        const val MAX_RECALL_CANDIDATES = 48
        const val CONSOLIDATION_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L

        val BROAD_RECALL_HINT = Regex(
            """(?i)(?:还记得|你记得|记不记得|第一次|上次|以前|之前|当时|remember|last\s+time|before)""",
        )
        val EPISODIC_RECALL_QUERY = Regex(
            """(?:还记得|你记得|记不记得|第一次|上次|以前|之前|当时|那天|那次)""",
        )
        val RELATIONSHIP_STATUS_QUERY = Regex(
            """(?:什么关系|算什么关系|喜欢我|爱我|讨厌我|在一起|分手|复合|对象|女朋友|男朋友|老婆|老公)""",
        )
        val PREFERENCE_RECALL_QUERY = Regex(
            """(?:喜欢|不喜欢|讨厌|介意|希望|习惯|偏好)""",
        )
    }
}
