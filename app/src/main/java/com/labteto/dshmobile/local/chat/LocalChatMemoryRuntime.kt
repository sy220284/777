package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat-owned long-term memory boundary used by foreground/group generation.
 *
 * Chat relationship facts and diary recall stay inside ChatFeature. The shared Memory stores remain
 * the durable capability; Work directive capture does not need to understand Chat persona semantics.
 */
@Singleton
internal class LocalChatMemoryRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val memoryStore: MemoryStore,
    private val memoryManager: MemoryManager,
    private val persistence: LocalChatPersistence,
    private val sessionStorage: LocalSessionStorageRuntime,
) {
    internal suspend fun captureAutoMemoryDirective(
        text: String,
        explicitSourceMessageId: String? = null,
    ) {
        val snapshot = runtimeStateStore.state.value
        if (
            !snapshot.autoMemory ||
            text.isBlank() ||
            snapshot.usageMode != com.labteto.dshmobile.local.LocalUsageMode.CHAT ||
            snapshot.chat.groupChat.enabled
        ) return

        val sourceMessageId = explicitSourceMessageId
            ?.takeIf(String::isNotBlank)
            ?: snapshot.transcriptIndex.latestUserMessageId
        val remembered = runCatching {
            memoryManager.captureChatRelationshipFact(
                text = text,
                lineageId = snapshot.lineageId,
                sourceSessionId = snapshot.sessionId,
                sourceMessageId = sourceMessageId,
                subjectLabel = snapshot.chat.chatPersona.name
                    .takeUnless {
                        it == PersonaProfile.DEFAULT_PERSONA_ID || it == "默认角色"
                    },
                subjectKey = chatRelationshipSubjectKey(
                    snapshot.chat.galleryId,
                    snapshot.chat.personaId,
                ),
            )
        }.getOrNull() ?: return

        sessionStorage.eventLogs.get(snapshot.sessionId).append("memory/auto", buildJsonObject {
            put("id", remembered.id)
            put("scope", remembered.scope.name.lowercase())
            put("kind", remembered.kind.name.lowercase())
            remembered.sourceMessages.lastOrNull()?.let { source ->
                put("source_message_id", source.messageId)
            }
            put("source", "chat-relationship")
        })
    }

    internal fun relationshipContext(
        query: String,
        viewerSubjectKey: String? = null,
        viewerName: String? = null,
        groupAudience: Boolean = false,
    ): String = relationshipContext(
        query = query,
        snapshot = runtimeStateStore.state.value,
        viewerSubjectKey = viewerSubjectKey,
        viewerName = viewerName,
        groupAudience = groupAudience,
    )

    internal fun relationshipContext(
        query: String,
        snapshot: LocalHarnessState,
        viewerSubjectKey: String? = null,
        viewerName: String? = null,
        groupAudience: Boolean = snapshot.chat.groupChat.enabled,
    ): String {
        if (!snapshot.autoRecall) return ""
        val subjectKey = viewerSubjectKey ?: chatRelationshipSubjectKey(
            snapshot.chat.galleryId,
            snapshot.chat.personaId,
        )
        if (subjectKey.isNullOrBlank()) return ""
        if (viewerSubjectKey == null && snapshot.chat.chatPersona.isUnboundChatPersona()) return ""

        val recallFacts = ChatMemorySelector.shouldRecall(query)
        val searchDiary = shouldSearchDiary(query)
        if (!recallFacts && !searchDiary) return ""

        val subjectLabel = viewerName?.trim()?.takeIf(String::isNotBlank)
            ?: snapshot.chat.chatPersona.name
        val contextWindow = documentedContextWindowTokens(
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
            snapshot.modelState.modelSelection.activeProfile?.contextWindowTokensOverride,
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
                query = ChatMemorySelector.semanticQuery(
                    query, subjectLabel,
                    if (snapshot.chat.groupChat.enabled) snapshot.chat.groupChat.context else snapshot.chat.chatContext,
                ),
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
                        appendLine(
                            if (groupAudience) {
                                "【本轮相关精确事实｜允许带入群聊】这些是已有事实记忆，可自然用于当前角色在群聊中的判断和表达。"
                            } else {
                                "【本轮相关长期事实】仅用于补足当前输入缺失的信息。"
                            },
                        )
                        recalled.forEach { appendLine("- ${it.content}") }
                    }.trim(),
                    budget.factTokens,
                )
            }
        }

        if (searchDiary) {
            val diary = persistence.diaryStore.search(
                // Use only recent public user evidence to resolve demonstratives such as
                // "after that"; private thoughts never become search terms.
                query = ChatMemorySelector.semanticQuery(
                    query, "",
                    if (snapshot.chat.groupChat.enabled) snapshot.chat.groupChat.context
                    else snapshot.chat.chatContext,
                ),
                subjectKey = subjectKey,
                groupAudience = groupAudience,
                maxItems = diaryRecallItemLimit(query),
            )
            if (diary.isNotEmpty()) {
                blocks += takeWithinModelTokenBudget(
                    buildString {
                        appendLine("【相关人物日记｜角色自己的长期经历】")
                        appendLine(diaryRecallUsageInstruction(groupAudience))
                        appendLine("当前输入和当前状态优先。")
                        diary.forEach { entry ->
                            appendLine("- 事件：${entry.event}")
                            entry.feeling.takeIf(String::isNotBlank)?.let { appendLine("  感受：$it") }
                            entry.innerThought.takeIf(String::isNotBlank)?.let { appendLine("  心里：$it") }
                            entry.relationshipMeaning.takeIf(String::isNotBlank)?.let {
                                appendLine("  关系意义：$it")
                            }
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
}
