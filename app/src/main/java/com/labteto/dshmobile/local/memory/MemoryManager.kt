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
        val current = store.listActive(
            allowedScopes = setOf(candidate.scope),
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
