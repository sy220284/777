package com.labteto.dshmobile.local.session

internal data class LocalSessionAccessScope(
    val projectId: String?,
    val lineageId: String?,
)

internal fun interface LocalActiveSessionScopeProvider {
    fun scope(sessionId: String): LocalSessionAccessScope?
}

internal class LocalSessionAccessCoordinator(
    private val summaries: () -> List<LocalSessionSummary>,
    private val currentSessionId: () -> String,
    private val currentScope: () -> LocalSessionAccessScope,
    private val activeScope: (String) -> LocalSessionAccessScope?,
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    /**
     * Stable, authorized pagination over every session in the current project/lineage.
     * An interrupted search can resume even when a session was deleted between pages.
     */
    fun search(
        query: String,
        scopeSessionId: String = currentSessionId(),
        cursor: String? = null,
    ): String {
        val byId = summaries().associateBy(LocalSessionSummary::id)
        val origin = scope(scopeSessionId, byId)
        val candidates = (byId.keys + scopeSessionId).asSequence().distinct()
            .filter { id ->
                id == scopeSessionId || (origin != null &&
                    scope(id, byId)?.let { target -> sharesScope(origin, target) } == true)
            }.sorted().toList()
        // Sorted immutable IDs avoid page duplication if drawer recency changes.
        val start = cursor?.takeIf(String::isNotBlank)?.let { previous ->
            val index = candidates.binarySearch(previous)
            if (index >= 0) index + 1 else -index - 1
        } ?: 0
        val hits = mutableListOf<String>()
        var next = start
        var scanned = 0
        while (next < candidates.size &&
            scanned < MAX_SESSION_SEARCH_SCANNED &&
            hits.size < MAX_SESSION_SEARCH_HITS) {
            val id = candidates[next++]
            scanned += 1
            val result = eventLogFor(id).search(query, limit = 1)
            result.takeUnless { it == "未找到会话事件" || it == "会话事件日志为空" }
                ?.let { hits += "会话 $id\n$it" }
        }
        val suffix = if (next < candidates.size) {
            "\n\nnext_cursor=${candidates[next - 1]}；仍有会话未检索，使用 session_search 的 cursor 参数继续。"
        } else ""
        return if (hits.isEmpty()) "本页未找到当前项目或会话链中的历史事件$suffix"
        else hits.joinToString("\n\n") + suffix
    }

    fun authorizeNavigationPreview(sessionId: String) {
        // Drawer selection is an explicit navigation to ANY of this user's sessions,
        // including a different project. Do not open the event log while checking it.
        require(sessionId.isNotBlank() &&
            (sessionId == currentSessionId() || summaries().any { it.id == sessionId })
        ) { "目标会话不存在：$sessionId" }
    }

    fun authorizedLog(
        requestedId: String?,
        defaultSessionId: String = currentSessionId(),
    ): LocalSessionEventLog {
        val id = requestedId?.takeIf(String::isNotBlank) ?: defaultSessionId
        require(canRead(defaultSessionId, id, summaries().associateBy(LocalSessionSummary::id))) {
            "会话不存在或不属于当前项目/会话链：$id"
        }
        return eventLogFor(id)
    }

    private fun sharesScope(source: Pair<String?, String?>, target: Pair<String?, String?>): Boolean {
        val sameProject = source.first?.takeIf(String::isNotBlank)?.let { it == target.first } == true
        return sameProject || (!source.second.isNullOrBlank() && source.second == target.second)
    }

    private fun canRead(
        scopeSessionId: String,
        targetSessionId: String,
        summaries: Map<String, LocalSessionSummary>,
    ): Boolean {
        if (scopeSessionId == targetSessionId) return true
        val source = scope(scopeSessionId, summaries) ?: return false
        val target = scope(targetSessionId, summaries) ?: return false
        return sharesScope(source, target)
    }

    private fun scope(
        sessionId: String,
        summaries: Map<String, LocalSessionSummary>,
    ): Pair<String?, String?>? {
        if (sessionId == currentSessionId()) {
            return currentScope().let { it.projectId to it.lineageId }
        }
        activeScope(sessionId)?.let { active ->
            return active.projectId to active.lineageId
        }
        return summaries[sessionId]?.let { summary ->
            summary.projectId to summary.lineageId?.ifBlank { summary.id }
        }
    }

    private companion object {
        const val MAX_SESSION_SEARCH_HITS = 50
        const val MAX_SESSION_SEARCH_SCANNED = 64
    }
}
