package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview

internal class LocalConversationFilesCoordinator(
    private val workspaceFilesProvider: () -> List<LocalWorkspaceFile>,
    private val workspaceFileAt: (String) -> LocalWorkspaceFile?,
    private val previewWorkspaceFile: (String) -> LocalWorkspaceFilePreview,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val currentSessionId: () -> String,
    private val currentEventLog: () -> LocalSessionEventLog,
    private val maxCacheEntries: Int = 12,
) {
    private data class CacheEntry(
        val identity: String,
        val generation: Long,
        val eventStamp: Long,
        val index: LocalConversationFileIndex,
    )

    private val lock = Any()
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    fun workspaceFiles(): List<LocalWorkspaceFile> = workspaceFilesProvider()

    fun preview(path: String): LocalWorkspaceFilePreview = previewWorkspaceFile(path)

    fun invalidate(sessionIds: Collection<String>) {
        if (sessionIds.isEmpty()) return
        synchronized(lock) {
            sessionIds.forEach(cache::remove)
        }
    }

    fun conversationFiles(sessionId: String): LocalConversationFiles {
        val log = if (sessionId == currentSessionId()) currentEventLog() else eventLogFor(sessionId)
        val generation = log.resetGeneration
        val identity = log.historyInstanceIdentity
        val eventStamp = log.latestOf(CONVERSATION_FILE_EVENT_TYPES)?.sequence ?: -1L
        val cached = synchronized(lock) {
            cache[sessionId]?.takeIf {
                it.identity == identity && it.generation == generation && it.eventStamp <= eventStamp
            }
        }
        if (cached?.eventStamp == eventStamp) return cached.index.snapshot(workspaceFileAt)

        val index = cached?.index?.copy() ?: LocalConversationFileIndex()
        log.forEachOfTypesAfter(cached?.eventStamp ?: -1L, CONVERSATION_FILE_EVENT_TYPES) { event ->
            if (event.sequence <= eventStamp) index.apply(event)
        }
        check(!log.isClosed && generation == log.resetGeneration) { "会话文件历史已更新，请重试" }
        val committed = synchronized(lock) {
            val newer = cache[sessionId]?.takeIf {
                it.identity == identity && it.generation == generation && it.eventStamp > eventStamp
            }
            val value = newer ?: CacheEntry(identity, generation, eventStamp, index)
            cache[sessionId] = value
            while (cache.size > maxCacheEntries) cache.remove(cache.keys.first())
            value
        }
        // Resolve real files afresh: missing or newly published bytes never require a history replay.
        return committed.index.snapshot(workspaceFileAt)
    }

    private companion object {
        val CONVERSATION_FILE_EVENT_TYPES = setOf(
            "user/message",
            "tool/call",
            "tool/result",
            "chat/active-transcript",
            LOCAL_AGENT_INBOX_EVENT_TYPE,
        )
    }
}
