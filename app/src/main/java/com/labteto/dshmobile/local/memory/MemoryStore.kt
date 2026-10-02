package com.labteto.dshmobile.local.memory

import android.content.Context
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

internal fun compactMemoryRecords(
    records: List<MemoryRecord>,
    maxRecords: Int,
    protectedIds: Set<String> = emptySet(),
): List<MemoryRecord> {
    if (maxRecords <= 0 || records.isEmpty()) return emptyList()
    if (records.size <= maxRecords) return records

    val predecessors = records
        .asSequence()
        .filter { !it.supersededBy.isNullOrBlank() }
        .groupBy { it.supersededBy!! }
        .mapValues { (_, values) -> values.sortedByDescending(MemoryRecord::updatedAt) }
    val activeRoots = records
        .filter(MemoryRecord::active)
        .sortedWith(
            compareByDescending<MemoryRecord> { if (it.id in protectedIds) 1 else 0 }
                .thenByDescending { if (it.pinned) 1 else 0 }
                .thenByDescending(MemoryRecord::importance)
                .thenByDescending(MemoryRecord::updatedAt),
        )

    val selected = linkedSetOf<String>()
    val selectedRoots = mutableListOf<String>()
    activeRoots.forEach { root ->
        val nearestPredecessor = predecessors[root.id].orEmpty().firstOrNull()
        val required = 1 + if (nearestPredecessor != null && nearestPredecessor.id !in selected) 1 else 0
        if (selected.size + required > maxRecords) return@forEach
        selected += root.id
        selectedRoots += root.id
        nearestPredecessor?.let { selected += it.id }
    }
    if (selected.isEmpty()) {
        activeRoots.firstOrNull()?.let { selected += it.id }
    }

    // Fill deeper rollback history breadth-first. A retained active root that has a predecessor is
    // admitted together with that nearest predecessor, so capacity pressure cannot leave a kept
    // replacement without the state needed to roll it back.
    var frontier = (selectedRoots + selected).distinct()
    val traversed = mutableSetOf<String>()
    while (frontier.isNotEmpty() && selected.size < maxRecords) {
        val nextFrontier = mutableListOf<String>()
        frontier.forEach { successorId ->
            if (!traversed.add(successorId)) return@forEach
            predecessors[successorId].orEmpty().forEach { predecessor ->
                if (selected.size >= maxRecords) return@forEach
                if (selected.add(predecessor.id)) {
                    nextFrontier += predecessor.id
                } else if (predecessor.id !in traversed) {
                    nextFrontier += predecessor.id
                }
            }
        }
        frontier = nextFrontier
    }
    return records.filter { it.id in selected }
}


internal data class MemoryConsolidationResult(
    val records: List<MemoryRecord>,
    val mergedCount: Int,
)

/**
 * Merge formatting-only duplicates without weakening rollback provenance.
 *
 * Exact duplicates are already folded on write. This maintenance pass handles older records and
 * punctuation/spacing variants, merges all known source messages, and rewires predecessor links to
 * the surviving newest record.
 */
internal fun consolidateMemoryRecords(
    records: List<MemoryRecord>,
    maxSourceMessages: Int = 16,
): MemoryConsolidationResult {
    if (records.size < 2) return MemoryConsolidationResult(records, 0)

    data class Key(
        val scope: MemoryScope,
        val projectId: String?,
        val lineageId: String?,
        val subjectKey: String?,
        val kind: MemoryKind,
        val content: String,
    )

    val duplicateGroups = records.asSequence()
        .filter(MemoryRecord::active)
        .groupBy { record ->
            Key(
                scope = record.scope,
                projectId = record.projectId,
                lineageId = record.lineageId,
                subjectKey = record.subjectKey,
                kind = record.kind,
                content = canonicalMemoryIdentity(record.content),
            )
        }
        .values
        .filter { group -> group.size > 1 && canonicalMemoryIdentity(group.first().content).isNotBlank() }
        .toList()
    if (duplicateGroups.isEmpty()) return MemoryConsolidationResult(records, 0)

    val loserToWinner = mutableMapOf<String, String>()
    val mergedWinners = mutableMapOf<String, MemoryRecord>()
    duplicateGroups.forEach { group ->
        val winner = group.maxWith(
            compareBy<MemoryRecord> { it.updatedAt }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        group.filter { it.id != winner.id }.forEach { loser -> loserToWinner[loser.id] = winner.id }
        val allMergedSources = group.asSequence()
            .flatMap { it.sourceMessages.asSequence() }
            .distinct()
            .toList()
        val boundedSourceLimit = maxSourceMessages.coerceAtLeast(1)
        val sourcesTruncated = allMergedSources.size > boundedSourceLimit
        val mergedSources = allMergedSources.takeLast(boundedSourceLimit)
        mergedWinners[winner.id] = winner.copy(
            sourceSessionId = winner.sourceSessionId
                ?: group.asSequence().sortedByDescending(MemoryRecord::updatedAt)
                    .mapNotNull(MemoryRecord::sourceSessionId)
                    .firstOrNull(),
            sourceMessages = mergedSources,
            hasUnboundSource = group.any(MemoryRecord::hasUnboundSource) || sourcesTruncated,
            importance = group.maxOf(MemoryRecord::importance),
            pinned = group.any(MemoryRecord::pinned),
            createdAt = group.minOf(MemoryRecord::createdAt),
            updatedAt = group.maxOf(MemoryRecord::updatedAt),
        )
    }

    val merged = records.mapNotNull { record ->
        if (record.id in loserToWinner) {
            null
        } else {
            val winner = mergedWinners[record.id] ?: record
            val successor = winner.supersededBy?.let { loserToWinner[it] ?: it }
            if (successor == winner.supersededBy) winner else winner.copy(supersededBy = successor)
        }
    }
    return MemoryConsolidationResult(
        records = merged,
        mergedCount = loserToWinner.size,
    )
}

private fun canonicalMemoryIdentity(text: String): String =
    text.lowercase()
        .replace(Regex("""[\s，。！？；：、,.!?;:'"“”‘’()（）\[\]【】|｜=_-]+"""), "")
        .take(2_000)

@Singleton
class MemoryStore internal constructor(
    private val root: File,
    private val json: Json,
) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "local-harness/memory"), json)

    init { root.mkdirs() }
    private val file = File(root, "memories.json")
    private val backup = File(root, "memories.json.bak")

    private var cachedDocument: MemoryDocument? = null
    private var cachedStamp: DocumentStamp? = null
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
        importance: Int = 50,
        pinned: Boolean = false,
        replaceIds: Set<String> = emptySet(),
    ): MemoryRecord {
        val clean = content.trim().take(MAX_MEMORY_CONTENT_CHARS)
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        require(scope != MemoryScope.PROJECT || !projectId.isNullOrBlank()) { "项目记忆缺少项目编号" }
        require(scope != MemoryScope.LINEAGE || !lineageId.isNullOrBlank()) { "对话链记忆缺少对话链编号" }

        val now = System.currentTimeMillis()
        val records = readDocument().records.toMutableList()
        val duplicateIndex = records.indexOfFirst {
            it.active &&
                it.scope == scope &&
                it.projectId == projectId &&
                it.lineageId == lineageId &&
                it.subjectKey == subjectKey &&
                it.content.equals(clean, ignoreCase = true)
        }
        if (duplicateIndex >= 0) {
            val incomingSource = sourceRef(sourceSessionId, sourceMessageId)
            val mergedSources = mergeSourceMessages(
                records[duplicateIndex].sourceMessages,
                incomingSource,
            )
            val refreshed = records[duplicateIndex].copy(
                kind = kind,
                importance = importance.coerceIn(0, 100),
                pinned = pinned || records[duplicateIndex].pinned,
                sourceSessionId = sourceSessionId ?: records[duplicateIndex].sourceSessionId,
                sourceMessages = mergedSources.sources,
                hasUnboundSource = records[duplicateIndex].hasUnboundSource ||
                    incomingSource == null ||
                    mergedSources.truncated,
                subjectKey = subjectKey ?: records[duplicateIndex].subjectKey,
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
            writeDocument(MemoryDocument(records = records))
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
            sourceMessages = sourceRef(sourceSessionId, sourceMessageId)?.let(::listOf).orEmpty(),
            hasUnboundSource = sourceMessageId.isNullOrBlank(),
            subjectKey = subjectKey,
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
        writeDocument(
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
        val records = readDocument().records.toMutableList()
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
        writeDocument(MemoryDocument(records = records))
        return updated
    }

    @Synchronized
    fun forget(id: String): Boolean {
        val records = readDocument().records.toMutableList()
        val index = records.indexOfFirst { it.id == id && it.active }
        if (index < 0) return false
        records[index] = records[index].copy(
            active = false,
            supersededBy = null,
            updatedAt = System.currentTimeMillis(),
        )
        writeDocument(MemoryDocument(records = records))
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
        val records = readDocument().records.toMutableList()
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
            if (changed) writeDocument(MemoryDocument(records = records))
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
        if (changed) writeDocument(MemoryDocument(records = records))
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
        val records = readDocument().records.toMutableList()
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
        if (changed > 0) writeDocument(MemoryDocument(records = records))
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
        val coarse = readDocument().records.asSequence()
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
        val current = readDocument().records
        val consolidated = consolidateMemoryRecords(current, MAX_SOURCE_MESSAGES)
        val compacted = compactMemoryRecords(consolidated.records, MAX_RECORDS)
        if (consolidated.mergedCount > 0 || compacted.size != current.size) {
            writeDocument(MemoryDocument(records = compacted))
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
    ): List<MemoryRecord> = readDocument().records.asSequence()
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

    private fun sourceRef(sessionId: String?, messageId: String?): MemorySourceRef? =
        if (sessionId.isNullOrBlank() || messageId.isNullOrBlank()) null
        else MemorySourceRef(sessionId = sessionId, messageId = messageId)

    private data class SourceMergeResult(
        val sources: List<MemorySourceRef>,
        val truncated: Boolean,
    )

    private fun mergeSourceMessages(
        current: List<MemorySourceRef>,
        incoming: MemorySourceRef?,
    ): SourceMergeResult {
        val all = (current + listOfNotNull(incoming)).distinct()
        return SourceMergeResult(
            sources = all.takeLast(MAX_SOURCE_MESSAGES),
            truncated = all.size > MAX_SOURCE_MESSAGES,
        )
    }

    private fun readDocument(): MemoryDocument {
        val stamp = documentStamp()
        cachedDocument?.takeIf { cachedStamp == stamp }?.let { return it }

        val document = decodeDocument(file) ?: if (!file.isFile) {
            decodeDocument(backup) ?: MemoryDocument().also {
                if (backup.isFile) {
                    AppLog.error(
                        "MemoryStore",
                        "长期记忆主文件缺失且备份无法解析；本次以空文档启动，损坏备份保留在原位置",
                    )
                }
            }
        } else {
            val corrupt = File(root, "memories.corrupt-${System.currentTimeMillis()}.json")
            val moved = runCatching { file.renameTo(corrupt) }.getOrDefault(false)
            if (!moved) runCatching { file.copyTo(corrupt, overwrite = false) }

            val recovered = decodeDocument(backup)
            if (recovered != null) {
                runCatching { backup.copyTo(file, overwrite = true) }
                recovered
            } else {
                AppLog.error(
                    "MemoryStore",
                    "长期记忆主文件损坏且备份不可用；已隔离主文件，本次以空文档启动",
                )
                MemoryDocument()
            }
        }
        cachedDocument = document
        cachedStamp = documentStamp()
        return document
    }

    private fun decodeDocument(source: File): MemoryDocument? {
        if (!source.isFile) return null
        return runCatching {
            json.decodeFromString(MemoryDocument.serializer(), source.readText())
        }.getOrNull()
    }

    private fun writeDocument(document: MemoryDocument) {
        file.parentFile?.mkdirs()
        if (decodeDocument(file) != null) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }

        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(MemoryDocument.serializer(), document))
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (decodeDocument(backup) == null && decodeDocument(file) != null) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        cachedDocument = document
        cachedStamp = documentStamp()
    }

    private fun documentStamp(): DocumentStamp = DocumentStamp(
        primaryModified = file.takeIf(File::isFile)?.lastModified() ?: -1L,
        primaryLength = file.takeIf(File::isFile)?.length() ?: -1L,
        backupModified = backup.takeIf(File::isFile)?.lastModified() ?: -1L,
        backupLength = backup.takeIf(File::isFile)?.length() ?: -1L,
    )

    private data class DocumentStamp(
        val primaryModified: Long,
        val primaryLength: Long,
        val backupModified: Long,
        val backupLength: Long,
    )

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
