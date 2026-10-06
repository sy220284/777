package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview

internal class LocalConversationFilesCoordinator(
    private val workspaceFilesProvider: () -> List<LocalWorkspaceFile>,
    private val previewWorkspaceFile: (String) -> LocalWorkspaceFilePreview,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val currentSessionId: () -> String,
    private val currentEventLog: () -> LocalSessionEventLog,
    private val maxCacheEntries: Int = 12,
) {
    private data class CacheEntry(
        val eventStamp: Long,
        val workspaceStamp: Long,
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
        val files = workspaceFilesProvider()
        val log = if (sessionId == currentSessionId()) currentEventLog() else eventLogFor(sessionId)
        val eventStamp = log.latestOf(CONVERSATION_FILE_EVENT_TYPES)?.sequence ?: -1L
        val workspaceStamp = workspaceFilesStamp(files)

        synchronized(lock) {
            cache[sessionId]
                ?.takeIf { it.eventStamp == eventStamp && it.workspaceStamp == workspaceStamp }
                ?.value
        }?.let { return it }

        val projected = log.withEvents { localConversationFiles(it, files) }
        synchronized(lock) {
            cache[sessionId] = CacheEntry(
                eventStamp = eventStamp,
                workspaceStamp = workspaceStamp,
                value = projected,
            )
            while (cache.size > maxCacheEntries) {
                val eldest = cache.entries.firstOrNull()?.key ?: break
                cache.remove(eldest)
            }
        }
        return projected
    }

    private fun workspaceFilesStamp(files: List<LocalWorkspaceFile>): Long {
        var stamp = 1_469_598_103_934_665_603L
        files.forEach { file ->
            stamp = stamp xor file.path.hashCode().toLong()
            stamp *= 1_099_511_628_211L
            stamp = stamp xor file.bytes
            stamp *= 1_099_511_628_211L
            stamp = stamp xor file.modifiedAt
            stamp *= 1_099_511_628_211L
        }
        return stamp
    }

    private companion object {
        val CONVERSATION_FILE_EVENT_TYPES = setOf(
            "user/message",
            "tool/call",
            "tool/result",
            "chat/active-transcript",
        )
    }
}
