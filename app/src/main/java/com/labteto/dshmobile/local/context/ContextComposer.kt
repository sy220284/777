package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.harness.context.AgentContextAssembler
import com.labteto.dshmobile.harness.context.AgentContextMemory
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
    private val assembler = AgentContextAssembler()

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

        return assembler.compose(
            rules = profile.customRules,
            memories = memories.map { memory ->
                AgentContextMemory(
                    scope = memory.scope.name,
                    kind = memory.kind.name,
                    content = memory.content,
                )
            },
            handoffSummary = request.handoffSummary.takeIf {
                request.mode == LocalConversationMode.CONTINUATION
            },
        )
    }

    private companion object {
        const val MAX_MEMORY_ITEMS = 6
        const val MAX_MEMORY_CHARS = 3_500
    }
}
