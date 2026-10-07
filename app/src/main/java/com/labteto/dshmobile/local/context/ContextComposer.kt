package com.labteto.dshmobile.local.context

import com.labteto.dshmobile.harness.context.AgentContextAssembler
import com.labteto.dshmobile.harness.context.AgentContextMemory
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.profile.UserProfileStore
import com.labteto.dshmobile.local.session.LocalConversationMode
import javax.inject.Inject
import javax.inject.Singleton

data class ContextRequest(
    val query: String,
    val mode: LocalConversationMode,
    val projectId: String?,
    val lineageId: String?,
    val handoffSummary: String?,
    val projectInstructions: String = "",
)

data class ContextComposition(
    val stable: String,
    val dynamic: String,
) {
    fun combined(): String = listOf(stable, dynamic)
        .filter(String::isNotBlank)
        .joinToString("\n\n")
}

@Singleton
class ContextComposer @Inject constructor(
    private val profileStore: UserProfileStore,
    private val memoryStore: MemoryStore,
) {
    private val assembler = AgentContextAssembler()
    private val layers = PromptLayerComposer()

    fun userProfile() = profileStore.read()

    fun compose(request: ContextRequest): String = composeParts(request).combined()

    fun composeParts(request: ContextRequest): ContextComposition {
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
                allowedKinds = WORK_MEMORY_KINDS,
                maxItems = MAX_MEMORY_ITEMS,
                maxChars = MAX_MEMORY_CHARS,
            )
        } else {
            emptyList()
        }
        val mappedMemories = memories.map { memory ->
            AgentContextMemory(
                scope = memory.scope.name,
                kind = memory.kind.name,
                content = memory.content,
            )
        }
        val layered = layers.compose(
            listOf(
                PromptLayer(
                    id = "user-rules",
                    priority = 200,
                    stability = PromptLayerStability.STABLE,
                    content = assembler.compose(
                        rules = profile.customRules,
                        memories = emptyList(),
                        handoffSummary = null,
                    ),
                ),
                PromptLayer(
                    id = "project-instructions",
                    priority = 300,
                    stability = PromptLayerStability.STABLE,
                    content = request.projectInstructions.takeIf {
                        request.projectId != null && request.mode != LocalConversationMode.INDEPENDENT
                    }.orEmpty(),
                ),
                PromptLayer(
                    id = "query-recall",
                    priority = 600,
                    stability = PromptLayerStability.DYNAMIC,
                    content = assembler.compose(
                        rules = "",
                        memories = mappedMemories,
                        handoffSummary = request.handoffSummary.takeIf {
                            request.mode == LocalConversationMode.CONTINUATION
                        },
                    ),
                ),
            ),
        )
        return ContextComposition(
            stable = layered.stable,
            dynamic = layered.dynamic,
        )
    }

    private companion object {
        const val MAX_MEMORY_ITEMS = 6
        const val MAX_MEMORY_CHARS = 3_500
        val WORK_MEMORY_KINDS = MemoryKind.values().filterNot {
            it in setOf(
                MemoryKind.RELATIONSHIP_FACT,
                MemoryKind.RELATIONSHIP_STATE,
                MemoryKind.RELATIONSHIP_PREFERENCE,
            )
        }.toSet()
    }
}
