package com.labteto.dshmobile.local

internal class LocalSessionAccessCoordinator(
    private val summaries: () -> List<LocalSessionSummary>,
    private val currentSessionId: () -> String,
    private val currentState: () -> LocalHarnessState,
    private val activeState: (String) -> LocalHarnessState?,
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    fun search(query: String, scopeSessionId: String = currentSessionId()): String {
        val hits = mutableListOf<String>()
        val snapshot = summaries()
        val candidates = (snapshot.map(LocalSessionSummary::id) + scopeSessionId).distinct()
        var scanned = 0
        for (id in candidates) {
            if (hits.size >= MAX_SESSION_SEARCH_HITS || scanned >= MAX_SESSION_SEARCH_SCANNED) break
            if (!canRead(scopeSessionId, id, snapshot)) continue
            scanned += 1
            val result = eventLogFor(id).search(query, limit = 1)
            result.takeUnless { it == "未找到会话事件" || it == "会话事件日志为空" }
                ?.let { hits += "会话 $id\n$it" }
        }
        val suffix = if (scanned >= MAX_SESSION_SEARCH_SCANNED) {
            "\n\n搜索已达到单次扫描预算 $MAX_SESSION_SEARCH_SCANNED 个会话；可缩小项目/关键词后继续。"
        } else ""
        return if (hits.isEmpty()) "未找到当前项目或会话链中的历史事件$suffix"
        else hits.joinToString("\n\n") + suffix
    }

    fun authorizedLog(
        requestedId: String?,
        defaultSessionId: String = currentSessionId(),
    ): LocalSessionEventLog {
        val id = requestedId?.takeIf(String::isNotBlank) ?: defaultSessionId
        require(canRead(defaultSessionId, id, summaries())) {
            "会话不存在或不属于当前项目/会话链：$id"
        }
        return eventLogFor(id)
    }

    private fun canRead(
        scopeSessionId: String,
        targetSessionId: String,
        summaries: List<LocalSessionSummary>,
    ): Boolean {
        if (scopeSessionId == targetSessionId) return true
        val source = scope(scopeSessionId, summaries) ?: return false
        val target = scope(targetSessionId, summaries) ?: return false
        val sameProject = source.first?.takeIf(String::isNotBlank)?.let { it == target.first } == true
        return sameProject || (!source.second.isNullOrBlank() && source.second == target.second)
    }

    private fun scope(
        sessionId: String,
        summaries: List<LocalSessionSummary>,
    ): Pair<String?, String?>? {
        if (sessionId == currentSessionId()) {
            val current = currentState()
            return current.projectId to current.lineageId.ifBlank { current.sessionId }
        }
        activeState(sessionId)?.let { active ->
            return active.projectId to active.lineageId.ifBlank { active.sessionId }
        }
        return summaries.firstOrNull { it.id == sessionId }?.let { summary ->
            summary.projectId to summary.lineageId?.ifBlank { summary.id }
        }
    }
}
