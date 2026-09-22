package com.labteto.dshmobile.local.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class MemoryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val root = File(context.filesDir, "local-harness/memory").apply { mkdirs() }
    private val file = File(root, "memories.json")

    @Synchronized
    fun remember(
        content: String,
        scope: MemoryScope,
        kind: MemoryKind = MemoryKind.FACT,
        projectId: String? = null,
        lineageId: String? = null,
        sourceSessionId: String? = null,
        importance: Int = 50,
        pinned: Boolean = false,
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
                it.content.equals(clean, ignoreCase = true)
        }
        if (duplicateIndex >= 0) {
            val refreshed = records[duplicateIndex].copy(
                kind = kind,
                importance = importance.coerceIn(0, 100),
                pinned = pinned || records[duplicateIndex].pinned,
                updatedAt = now,
            )
            records[duplicateIndex] = refreshed
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
            importance = importance.coerceIn(0, 100),
            pinned = pinned,
            createdAt = now,
            updatedAt = now,
        )
        records += record
        writeDocument(MemoryDocument(records = records.takeLast(MAX_RECORDS)))
        return record
    }

    @Synchronized
    fun search(
        query: String,
        allowedScopes: Set<MemoryScope>,
        projectId: String?,
        lineageId: String?,
        maxItems: Int = DEFAULT_MAX_ITEMS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<MemoryRecord> {
        val boundedItems = maxItems.coerceIn(1, 20)
        val boundedChars = maxChars.coerceIn(256, 12_000)
        val terms = terms(query)
        val candidates = readDocument().records.asSequence()
            .filter { it.active && it.scope in allowedScopes }
            .filter {
                when (it.scope) {
                    MemoryScope.GLOBAL -> true
                    MemoryScope.PROJECT -> projectId != null && it.projectId == projectId
                    MemoryScope.LINEAGE -> lineageId != null && it.lineageId == lineageId
                }
            }
            .map { it to score(it, terms) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryRecord, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt },
            )
            .map { it.first }
            .toList()

        var used = 0
        val selected = mutableListOf<MemoryRecord>()
        for (record in candidates) {
            val cost = record.content.length + 32
            if (selected.isNotEmpty() && used + cost > boundedChars) break
            selected += record
            used += cost
            if (selected.size >= boundedItems) break
        }
        return selected
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
        .take(limit.coerceIn(1, 200))
        .toList()

    private fun score(record: MemoryRecord, queryTerms: Set<String>): Int {
        val contentTerms = terms(record.content)
        val overlap = if (queryTerms.isEmpty()) 0 else queryTerms.count(contentTerms::contains)
        val direct = if (
            queryTerms.isNotEmpty() &&
            queryTerms.any { record.content.contains(it, ignoreCase = true) }
        ) 8 else 0
        val pinned = if (record.pinned) 12 else 0
        val importance = record.importance / 10
        return overlap * 12 + direct + pinned + importance
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

    private fun readDocument(): MemoryDocument {
        if (!file.isFile) return MemoryDocument()
        return runCatching {
            json.decodeFromString(MemoryDocument.serializer(), file.readText())
        }.getOrDefault(MemoryDocument())
    }

    private fun writeDocument(document: MemoryDocument) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(MemoryDocument.serializer(), document))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_MEMORY_CONTENT_CHARS = 2_000
        const val MAX_RECORDS = 2_000
        const val DEFAULT_MAX_ITEMS = 6
        const val DEFAULT_MAX_CHARS = 3_500
    }
}
