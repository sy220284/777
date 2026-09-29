package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatMemorySelector
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.isUnboundChatPersona
import com.labteto.dshmobile.local.chat.chatRelationshipSubjectKey
import com.labteto.dshmobile.local.chat.relationshipMemoryMatchesSubject
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class LocalMemoryCoordinator(
    private val state: MutableStateFlow<LocalHarnessState>,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val currentSessionId: () -> String,
    private val eventLog: () -> LocalSessionEventLog,
    private val persist: () -> Unit,
) {
    suspend fun captureAutoMemoryDirective(
        text: String,
        explicitSourceMessageId: String? = null,
    ) {
        val snapshot = state.value
        if (!snapshot.autoMemory || text.isBlank()) return
        val sourceMessageId = explicitSourceMessageId
            ?.takeIf(String::isNotBlank)
            ?: snapshot.transcriptIndex.latestUserMessageId

        val remembered = if (snapshot.usageMode == LocalUsageMode.CHAT) {
            if (snapshot.groupChat.enabled) {
                null
            } else {
                runCatching {
                    memoryManager.captureChatRelationshipFact(
                        text = text,
                        lineageId = snapshot.lineageId,
                        sourceSessionId = currentSessionId(),
                        sourceMessageId = sourceMessageId,
                        subjectLabel = snapshot.chatPersona.name
                            .takeUnless {
                                it == PersonaProfile.DEFAULT_PERSONA_ID || it == "默认角色"
                            },
                        subjectKey = chatRelationshipSubjectKey(
                            snapshot.galleryId,
                            snapshot.personaId,
                        ),
                    )
                }.getOrNull()
            }
        } else {
            runCatching {
                memoryManager.captureExplicitUserDirective(
                    text = text,
                    mode = snapshot.conversationMode,
                    projectId = snapshot.projectId,
                    lineageId = snapshot.lineageId,
                    sourceSessionId = currentSessionId(),
                    sourceMessageId = sourceMessageId,
                )
            }.getOrNull()
        }

        remembered?.let {
            eventLog().append("memory/auto", buildJsonObject {
                put("id", it.id)
                put("scope", it.scope.name.lowercase())
                put("kind", it.kind.name.lowercase())
                it.sourceMessages.lastOrNull()?.let { source ->
                    put("source_message_id", source.messageId)
                }
                put(
                    "source",
                    if (snapshot.usageMode == LocalUsageMode.CHAT) {
                        "chat-relationship"
                    } else {
                        "directive"
                    },
                )
            })
        }
    }

    fun chatRelationshipMemoryContext(
        query: String,
        snapshot: LocalHarnessState,
    ): String {
        if (
            snapshot.chatPersona.isUnboundChatPersona() ||
            !snapshot.autoRecall ||
            !ChatMemorySelector.shouldRecall(query)
        ) return ""
        val relationshipKinds = setOf(
            MemoryKind.RELATIONSHIP_FACT,
            MemoryKind.RELATIONSHIP_STATE,
            MemoryKind.RELATIONSHIP_PREFERENCE,
        )
        val currentSubjectKey = chatRelationshipSubjectKey(
            snapshot.galleryId,
            snapshot.personaId,
        )
        val recalled = memoryStore.search(
            query = ChatMemorySelector.semanticQuery(query, snapshot.chatPersona.name),
            allowedScopes = setOf(MemoryScope.GLOBAL, MemoryScope.LINEAGE),
            projectId = null,
            lineageId = snapshot.lineageId,
            allowedKinds = relationshipKinds,
            maxItems = 4,
            maxChars = 4_000,
            recordFilter = { memory ->
                relationshipMemoryMatchesSubject(
                    memory = memory,
                    currentSubjectKey = currentSubjectKey,
                    currentLineageId = snapshot.lineageId,
                    subjectLabel = snapshot.chatPersona.name,
                )
            },
        ).distinctBy { it.id }
        if (recalled.isEmpty()) return ""

        return buildString {
            appendLine("【本轮相关长期记忆】仅用于补足当前输入缺失的信息；已在当前状态出现的内容忽略。")
            recalled.forEach { appendLine("- ${it.content}") }
            append("与本轮冲突时以本轮为准；除非用户追问，不主动回顾。")
        }
    }

    fun hydrateNewChatStateFromRelationshipMemory() {
        val snapshot = state.value
        if (
            snapshot.usageMode != LocalUsageMode.CHAT ||
            !snapshot.autoRecall ||
            snapshot.chatState.updatedAt != 0L
        ) return

        val subject = snapshot.chatPersona.name.trim()
            .takeIf { it.isNotBlank() && it != "默认角色" }
            ?: return
        val prefix = "关系状态：我和$subject｜"
        val latest = memoryStore.listActive(
            allowedScopes = setOf(MemoryScope.GLOBAL),
            projectId = null,
            lineageId = null,
            limit = 2_000,
        ).asSequence()
            .filter {
                it.kind == MemoryKind.RELATIONSHIP_STATE &&
                    relationshipMemoryMatchesSubject(
                        memory = it,
                        currentSubjectKey = chatRelationshipSubjectKey(
                            snapshot.galleryId,
                            snapshot.personaId,
                        ),
                        currentLineageId = snapshot.lineageId,
                        subjectLabel = snapshot.chatPersona.name,
                    ) &&
                    it.content.startsWith(prefix)
            }
            .maxByOrNull { it.updatedAt }
            ?: return

        val stored = latest.content.substringAfter("｜", "").trim()
        val stage = when (stored) {
            "暧昧" -> "AMBIGUOUS"
            "在一起", "确定关系", "异地", "订婚", "结婚", "同居" -> "COMMITTED"
            "冷战" -> "CONFLICT"
            "分手", "离婚" -> "SEPARATED"
            "复合" -> "REPAIRING"
            else -> return
        }
        val label = when (stage) {
            "AMBIGUOUS" -> "暧昧期"
            "COMMITTED" -> "稳定关系"
            "CONFLICT" -> "矛盾期"
            "SEPARATED" -> "已分开"
            "REPAIRING" -> "修复中"
            else -> snapshot.chatState.relationshipState
        }
        if (
            snapshot.chatState.dynamics.stage == stage &&
            snapshot.chatState.relationshipState == label
        ) return

        state.update { current ->
            if (
                current.sessionId != snapshot.sessionId ||
                current.chatState.updatedAt != 0L
            ) {
                current
            } else {
                current.copy(
                    chatState = current.chatState.copy(
                        relationshipState = label,
                        dynamics = current.chatState.dynamics.copy(stage = stage),
                    ),
                )
            }
        }
        eventLog().append("chat/relationship-hydrate", buildJsonObject {
            put("subject", subject)
            put(
                "subject_key",
                chatRelationshipSubjectKey(
                    snapshot.galleryId,
                    snapshot.personaId,
                ).orEmpty(),
            )
            put("state", stored)
            put("stage", stage)
            put("memory_id", latest.id)
        })
        persist()
    }
}
