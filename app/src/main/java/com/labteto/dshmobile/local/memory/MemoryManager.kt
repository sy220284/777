package com.labteto.dshmobile.local.memory

import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.observability.AppLog
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
        sourceMessageId: String? = null,
    ): MemoryRecord? {
        val candidate = policy.extractExplicitUserDirective(text, mode, projectId) ?: return null
        return remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = projectId,
            lineageId = lineageId,
            sourceSessionId = sourceSessionId,
            sourceMessageId = sourceMessageId,
            subjectKey = candidate.subjectKey,
            importance = candidate.importance,
        )
    }

    fun captureChatRelationshipFact(
        text: String,
        lineageId: String?,
        sourceSessionId: String,
        sourceMessageId: String? = null,
        subjectLabel: String? = null,
        subjectKey: String? = null,
    ): MemoryRecord? {
        val extracted = policy.extractChatRelationshipFact(text, subjectLabel) ?: return null
        val cleanLabel = subjectLabel?.trim()?.takeIf(String::isNotBlank)
        val belongsToCharacter = subjectKey != null && cleanLabel != null && (
            extracted.scope == MemoryScope.LINEAGE ||
                extracted.content.startsWith("关系状态：我和$cleanLabel｜") ||
                extracted.content.startsWith("关系对象：$cleanLabel｜")
            )
        val candidate = extracted.copy(
            subjectKey = subjectKey.takeIf { belongsToCharacter },
        )
        return remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = null,
            lineageId = lineageId,
            sourceSessionId = sourceSessionId,
            sourceMessageId = sourceMessageId,
            importance = candidate.importance,
            subjectKey = candidate.subjectKey,
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
            expectedUpdatedAt = existing.updatedAt,
            content = clean,
            kind = kind,
            importance = importance,
            pinned = pinned,
        )
    }

    /** Restore a user edit with the same sensitive-data and CAS rules as normal editing. */
    fun restoreManualEdit(existing: MemoryRecord): MemoryRecord {
        val previous = requireNotNull(existing.previousManualRevision) { "没有可恢复的手动修改版本" }
        require(!policy.containsSensitiveData(previous.content)) { "上一版本包含敏感信息，拒绝恢复" }
        return store.restoreManualEdit(existing.id, existing.updatedAt)
    }

    fun remember(
        content: String,
        scope: MemoryScope,
        kind: MemoryKind,
        projectId: String?,
        lineageId: String?,
        sourceSessionId: String,
        importance: Int,
        subjectKey: String? = null,
        sourceMessageId: String? = null,
    ): MemoryRecord {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        require(!policy.containsSensitiveData(clean)) { "敏感信息禁止写入长期记忆" }
        val candidate = MemoryCandidate(
            content = clean,
            scope = scope,
            kind = kind,
            importance = importance.coerceIn(0, 100),
            subjectKey = subjectKey,
        )
        val current = store.listActive(
            allowedScopes = setOf(scope),
            projectId = projectId,
            lineageId = lineageId,
            limit = 2_000,
        )
        val replaced = conflicts.findReplacement(candidate, current)
        val remembered = store.remember(
            content = candidate.content,
            scope = candidate.scope,
            kind = candidate.kind,
            projectId = projectId.takeIf { candidate.scope == MemoryScope.PROJECT },
            lineageId = lineageId.takeIf { candidate.scope == MemoryScope.LINEAGE },
            sourceSessionId = sourceSessionId,
            sourceMessageId = sourceMessageId,
            subjectKey = candidate.subjectKey,
            importance = candidate.importance,
            replaceIds = replaced?.let { setOf(it.id) }.orEmpty(),
        )
        // The memory record has already been committed. Optional consolidation must not
        // transform a successful write into a false failure (or trigger duplicate retries).
        try {
            store.consolidateIfDue()
        } catch (error: Exception) {
            AppLog.warn("MemoryManager", "记忆已保存，但后台整理失败", error)
        }
        return remembered
    }
}
