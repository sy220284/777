package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.chat.ChatDiaryStore
import com.labteto.dshmobile.local.chat.ChatMemorySelector
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.chatLongTermMemoryBudget
import com.labteto.dshmobile.local.chat.shouldRecallDiary
import com.labteto.dshmobile.local.chat.takeWithinModelTokenBudget
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
    private val diaryStore: ChatDiaryStore,
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
    ): String = chatMemoryContext(query, snapshot)

    fun chatMemoryContext(
        query: String,
        snapshot: LocalHarnessState,
        viewerSubjectKey: String? = null,
        viewerName: String? = null,
        groupAudience: Boolean = snapshot.groupChat.enabled,
    ): String {
        if (!snapshot.autoRecall) return ""
        val subjectKey = viewerSubjectKey ?: chatRelationshipSubjectKey(
            snapshot.galleryId,
            snapshot.personaId,
        )
        if (subjectKey.isNullOrBlank()) return ""
        if (viewerSubjectKey == null && snapshot.chatPersona.isUnboundChatPersona()) return ""

        val recallFacts = ChatMemorySelector.shouldRecall(query)
        val recallDiary = shouldRecallDiary(query)
        if (!recallFacts && !recallDiary) return ""

        val subjectLabel = viewerName?.trim().takeUnless { it.isNullOrBlank() }
            ?: snapshot.chatPersona.name
        val contextWindow = documentedContextWindowTokens(
            snapshot.model,
            snapshot.baseUrl,
            snapshot.modelSelection.activeProfile?.contextWindowTokensOverride,
        )
        val budget = chatLongTermMemoryBudget(contextWindow)
        val blocks = mutableListOf<String>()

        if (recallFacts) {
            val relationshipKinds = setOf(
                MemoryKind.RELATIONSHIP_FACT,
                MemoryKind.RELATIONSHIP_STATE,
                MemoryKind.RELATIONSHIP_PREFERENCE,
            )
            val recalled = memoryStore.search(
                query = ChatMemorySelector.semanticQuery(query, subjectLabel),
                allowedScopes = setOf(MemoryScope.GLOBAL, MemoryScope.LINEAGE),
                projectId = null,
                lineageId = snapshot.lineageId,
                allowedKinds = relationshipKinds,
                maxItems = 4,
                maxChars = (budget.factTokens * 3).coerceAtLeast(720),
                recordFilter = { memory ->
                    relationshipMemoryMatchesSubject(
                        memory = memory,
                        currentSubjectKey = subjectKey,
                        currentLineageId = snapshot.lineageId,
                        subjectLabel = subjectLabel,
                    )
                },
            ).distinctBy { it.id }
            if (recalled.isNotEmpty()) {
                blocks += takeWithinModelTokenBudget(
                    buildString {
                        appendLine("【本轮相关长期事实】仅用于补足当前输入缺失的信息。")
                        recalled.forEach { appendLine("- ${it.content}") }
                    }.trim(),
                    budget.factTokens,
                )
            }
        }

        if (recallDiary) {
            val diary = diaryStore.search(
                query = query,
                subjectKey = subjectKey,
                groupAudience = groupAudience,
                maxItems = when {
                    budget.diaryTokens <= 500 -> 1
                    budget.diaryTokens <= 900 -> 2
                    else -> 3
                },
            )
            if (diary.isNotEmpty()) {
                blocks += takeWithinModelTokenBudget(
                    buildString {
                        appendLine("【相关人物日记｜角色自己的长期经历】")
                        appendLine("用于恢复经历与当时心理，不逐条复述；当前输入和当前状态优先。")
                        diary.forEach { entry ->
                            appendLine("- 事件：${entry.event}")
                            entry.feeling.takeIf(String::isNotBlank)?.let { appendLine("  感受：$it") }
                            entry.innerThought.takeIf(String::isNotBlank)?.let { appendLine("  心里：$it") }
                            entry.relationshipMeaning.takeIf(String::isNotBlank)?.let { appendLine("  关系意义：$it") }
                            entry.unresolvedEcho.takeIf(String::isNotBlank)?.let { appendLine("  余波：$it") }
                        }
                    }.trim(),
                    budget.diaryTokens,
                )
            }
        }

        return takeWithinModelTokenBudget(
            blocks.filter(String::isNotBlank).joinToString("\n\n") +
                if (blocks.isEmpty()) "" else "\n\n与本轮冲突时以本轮为准；不要为了展示记忆而主动回顾。",
            budget.totalTokens,
        )
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
