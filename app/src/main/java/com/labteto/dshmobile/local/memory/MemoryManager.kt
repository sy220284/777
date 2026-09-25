package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.LocalConversationMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    private val store: MemoryStore,
    private val policy: MemoryPolicy,
    private val conflicts: MemoryConflictResolver,
) {
    fun captureExplicitUserDirective(
        text: String,
        mode: LocalConversationMode,
        projectId: String?,
        lineageId: String?,
        sourceSessionId: String,
    ): MemoryRecord? {
        val candidate = policy.extractExplicitUserDirective(text, mode, projectId) ?: return null
        return remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = projectId,
            lineageId = lineageId,
            sourceSessionId = sourceSessionId,
            importance = candidate.importance,
        )
    }

    fun captureChatRelationshipFact(
        text: String,
        lineageId: String?,
        sourceSessionId: String,
        subjectLabel: String? = null,
    ): MemoryRecord? {
        val candidate = policy.extractChatRelationshipFact(text, subjectLabel) ?: return null
        return remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = null,
            lineageId = lineageId,
            sourceSessionId = sourceSessionId,
            importance = candidate.importance,
        )
    }

    fun update(
        existing: MemoryRecord,
        content: String? = null,
        kind: MemoryKind? = null,
        importance: Int? = null,
        pinned: Boolean? = null,
    ): MemoryRecord {
        val clean = content?.trim() ?: existing.content
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        require(!policy.containsSensitiveData(clean)) { "敏感信息禁止写入长期记忆" }
        return store.update(
            id = existing.id,
            content = clean,
            kind = kind,
            importance = importance,
            pinned = pinned,
        )
    }

    fun remember(
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        projectId: String?,
        lineageId: String?,
        sourceSessionId: String,
        importance: Int,
    ): MemoryRecord {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        require(!policy.containsSensitiveData(clean)) { "敏感信息禁止写入长期记忆" }
        val candidate = MemoryCandidate(
            content = clean,
            scope = scope,
            kind = kind,
            importance = importance.coerceIn(0, 100),
        )
        val current = store.listActive(
            allowedScopes = setOf(scope),
            projectId = projectId,
            lineageId = lineageId,
            limit = 200,
        )
        val replaced = conflicts.findReplacement(candidate, current)
        return store.remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = projectId.takeIf { candidate.scope == MemoryScope.PROJECT },
            lineageId = lineageId.takeIf { candidate.scope == MemoryScope.LINEAGE },
            sourceSessionId = sourceSessionId,
            importance = candidate.importance,
            replaceIds = replaced?.let { setOf(it.id) }.orEmpty(),
        )
    }
}
