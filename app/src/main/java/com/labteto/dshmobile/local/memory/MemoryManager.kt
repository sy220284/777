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
