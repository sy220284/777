package com.labteto.dshmobile.local.session

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
        val eventStamp: Long,
        val value: LocalConversationFiles,
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
        val eventStamp = log.latestOf(CONVERSATION_FILE_EVENT_TYPES)?.sequence ?: -1L
        val cached = synchronized(lock) {
            cache[sessionId]?.takeIf { it.eventStamp == eventStamp }?.value
        }
        if (cached != null) return refreshExistingFiles(cached)

        // Cache all durable referenced paths, including currently missing paths. A file
        // created later can become visible without replaying the whole event history.
        val projected = log.withEvents { events ->
            localConversationFiles(events) { path -> LocalWorkspaceFile(path, 0L, 0L) }
        }
        synchronized(lock) {
            cache[sessionId] = CacheEntry(eventStamp = eventStamp, value = projected)
            while (cache.size > maxCacheEntries) {
                val oldest = cache.entries.firstOrNull()?.key ?: break
                cache.remove(oldest)
            }
        }
        return refreshExistingFiles(projected)
    }

    private fun refreshExistingFiles(files: LocalConversationFiles): LocalConversationFiles =
        LocalConversationFiles(
            artifacts = files.artifacts.mapNotNull { workspaceFileAt(it.path) },
            involved = files.involved.mapNotNull { workspaceFileAt(it.path) },
        )

    private companion object {
        val CONVERSATION_FILE_EVENT_TYPES = setOf(
            "user/message",
            "tool/call",
            "tool/result",
            "chat/active-transcript",
        )
    }
}
