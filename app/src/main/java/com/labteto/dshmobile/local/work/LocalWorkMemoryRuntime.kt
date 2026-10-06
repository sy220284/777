package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.memory.MemoryManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Work-owned capture of explicit user directives into long-term memory. */
@Singleton
internal class LocalWorkMemoryRuntime @Inject constructor(
    private val memoryManager: MemoryManager,
) {
    internal suspend fun captureAutoMemoryDirective(
        text: String,
        sourceMessageId: String?,
        binding: LocalWorkRunBinding,
    ) {
        val snapshot = binding.aggregateSnapshot()
        if (!snapshot.autoMemory || text.isBlank()) return

        val remembered = runCatching {
            memoryManager.captureExplicitUserDirective(
                text = text,
                mode = snapshot.conversationMode,
                projectId = snapshot.projectId,
                lineageId = snapshot.lineageId,
                sourceSessionId = binding.sessionId,
                sourceMessageId = sourceMessageId
                    ?.takeIf(String::isNotBlank)
                    ?: snapshot.transcriptIndex.latestUserMessageId,
            )
        }.getOrNull() ?: return

        binding.eventLog.append("memory/auto", buildJsonObject {
            put("id", remembered.id)
            put("scope", remembered.scope.name.lowercase())
            put("kind", remembered.kind.name.lowercase())
            remembered.sourceMessages.lastOrNull()?.let { source ->
                put("source_message_id", source.messageId)
            }
            put("source", "directive")
        })
    }
}
