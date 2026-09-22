package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.local.LocalConversationMode
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.profile.UserProfileStore
import javax.inject.Inject
import javax.inject.Singleton

data class ContextRequest(
    val query: String,
    val mode: LocalConversationMode,
    val projectId: String?,
    val lineageId: String?,
    val handoffSummary: String?,
)

@Singleton
class ContextComposer @Inject constructor(
    private val profileStore: UserProfileStore,
    private val memoryStore: MemoryStore,
) {
    fun compose(request: ContextRequest): String {
        val profile = profileStore.read()
        val allowedScopes = when (request.mode) {
            LocalConversationMode.INDEPENDENT -> setOf(MemoryScope.GLOBAL)
            LocalConversationMode.PROJECT -> setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT)
            LocalConversationMode.CONTINUATION ->
                setOf(MemoryScope.GLOBAL, MemoryScope.PROJECT, MemoryScope.LINEAGE)
        }
        val memories = if (profile.autoRecall) {
            memoryStore.search(
                query = request.query,
                allowedScopes = allowedScopes,
                projectId = request.projectId,
                lineageId = request.lineageId,
                maxItems = MAX_MEMORY_ITEMS,
                maxChars = MAX_MEMORY_CHARS,
            )
        } else {
            emptyList()
        }

        return buildString {
            val rules = profile.customRules.trim().take(MAX_RULE_CHARS)
            if (rules.isNotEmpty()) {
                appendLine("【用户长期规则】")
                appendLine(rules)
            }
            if (memories.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("【相关长期记忆】")
                memories.forEach { memory ->
                    append("- [").append(memory.scope.name.lowercase()).append('/')
                        .append(memory.kind.name.lowercase()).append("] ")
                        .appendLine(memory.content.take(MAX_SINGLE_MEMORY_CHARS))
                }
                appendLine("若长期记忆与用户本轮明确新要求冲突，以本轮新要求为准。")
            }
            if (
                request.mode == LocalConversationMode.CONTINUATION &&
                !request.handoffSummary.isNullOrBlank()
            ) {
                if (isNotEmpty()) appendLine()
                appendLine("【上一会话交接】")
                appendLine(request.handoffSummary.trim().take(MAX_HANDOFF_CHARS))
            }
        }.trim()
    }

    private companion object {
        const val MAX_RULE_CHARS = 3_000
        const val MAX_MEMORY_ITEMS = 6
        const val MAX_MEMORY_CHARS = 3_500
        const val MAX_SINGLE_MEMORY_CHARS = 800
        const val MAX_HANDOFF_CHARS = 3_500
    }
}
