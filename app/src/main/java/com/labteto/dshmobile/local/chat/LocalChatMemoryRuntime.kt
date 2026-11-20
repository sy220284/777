package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryManager
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.observability.AppLog
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A read-only trace of recalled facts sent in a confirmed assistant turn. */
internal data class ChatRecallEvidence(
    val kind: String,
    val content: String,
    val sourceSessionId: String = "",
    val sourceMessageId: String = "",
)

/** Append a durable, bounded receipt only after a real assistant message was committed. */
internal fun appendChatRecallEvidence(
    log: LocalSessionEventLog,
    assistantMessageId: String,
    sourceUserMessageId: String?,
    references: List<ChatRecallEvidence>,
) {
    if (assistantMessageId.isBlank() || references.isEmpty()) return
    log.append("chat/recall-used", buildJsonObject {
        put("assistant_message_id", assistantMessageId)
        put("source_user_message_id", sourceUserMessageId)
        put("references", JsonArray(references.take(12).map { item -> buildJsonObject {
            put("kind", item.kind)
            put("content", item.content.take(1200))
            put("source_session_id", item.sourceSessionId)
            put("source_message_id", item.sourceMessageId)
        } }))
    })
}

/** Read only the latest evidence for the exact assistant message. No parallel fact store. */
internal fun readChatRecallEvidence(log: LocalSessionEventLog, assistantMessageId: String): List<ChatRecallEvidence> {
    if (assistantMessageId.isBlank()) return emptyList()
    val latest = log.latestMatching(setOf("chat/recall-used")) { data ->
        data["assistant_message_id"]?.jsonPrimitive?.contentOrNull == assistantMessageId
    } ?: return emptyList()
    val items = latest.data["references"] as? JsonArray ?: return emptyList()
    return items.mapNotNull { element ->
        val data = element as? JsonObject ?: return@mapNotNull null
        val content = data["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val kind = data["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isBlank() || kind !in setOf("fact", "diary")) return@mapNotNull null
        ChatRecallEvidence(
            kind = kind, content = content,
            sourceSessionId = data["source_session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sourceMessageId = data["source_message_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }.distinct()
}

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
    private val memoryProjection: LocalChatMemoryProjectionPort,
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
                subjectKey = scopedRelationshipSubjectKey(chatRelationshipSubjectKey(
                    snapshot.chat.galleryId, snapshot.chat.personaId,
                ), snapshot.chat.chatContext.experienceId.ifBlank { snapshot.sessionId }),
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLog.warn("ChatMemory",
                "failure=true operation=capture_relationship_memory " +
                    "session_id=${snapshot.sessionId.take(80)} cause_type=${error.javaClass.simpleName}")
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
        onRecallUsed: (List<ChatRecallEvidence>) -> Unit = {},
    ): String = relationshipContext(
        query = query,
        snapshot = runtimeStateStore.state.value,
        viewerSubjectKey = viewerSubjectKey,
        viewerName = viewerName,
        groupAudience = groupAudience,
        onRecallUsed = onRecallUsed,
    )

    internal fun relationshipContext(
        query: String,
        snapshot: LocalHarnessState,
        viewerSubjectKey: String? = null,
        viewerName: String? = null,
        groupAudience: Boolean = snapshot.chat.groupChat.enabled,
        excludedAssistantMessageIds: Set<String> = emptySet(),
        onRecallUsed: (List<ChatRecallEvidence>) -> Unit = {},
    ): String {
        if (!snapshot.autoRecall) return ""
        val subjectKey = viewerSubjectKey ?: chatRelationshipSubjectKey(
            snapshot.chat.galleryId,
            snapshot.chat.personaId,
        )
        if (subjectKey.isNullOrBlank()) return ""
        if (viewerSubjectKey == null && snapshot.chat.chatPersona.isUnboundChatPersona()) return ""

        // Retired assistant variants are display-only even on later ordinary turns.
        // Only diary entries sourced from the selected branch can become recall context.
        val excludedReplies = excludedAssistantMessageIds +
            chatInactiveAssistantAnswerIds(snapshot.chat.chatBranches)
        val conversation = if (snapshot.chat.groupChat.enabled) snapshot.chat.groupChat.context
            else snapshot.chat.chatContext
        val recallQuery = ChatMemorySelector.recallQuery(query, conversation) ?: return ""
        val searchDiary = shouldSearchDiary(recallQuery)

        val subjectLabel = viewerName?.trim()?.takeIf(String::isNotBlank)
            ?: snapshot.chat.chatPersona.name
        val contextWindow = documentedContextWindowTokens(
            snapshot.modelState.model,
            snapshot.modelState.baseUrl,
            snapshot.modelState.modelSelection.activeProfile?.contextWindowTokensOverride,
        )
        val budget = chatLongTermMemoryBudget(contextWindow)
        val blocks = mutableListOf<String>()
        val recallEvidence = mutableListOf<ChatRecallEvidence>()
        run {
            val relationshipKinds = setOf(
                MemoryKind.RELATIONSHIP_FACT,
                MemoryKind.RELATIONSHIP_STATE,
                MemoryKind.RELATIONSHIP_PREFERENCE,
            )
            val recalled = memoryStore.search(
                query = ChatMemorySelector.semanticQuery(
                    recallQuery, "", conversation,
                ),
                allowedScopes = setOf(MemoryScope.GLOBAL, MemoryScope.LINEAGE),
                projectId = null,
                lineageId = snapshot.lineageId,
                allowedKinds = relationshipKinds,
                maxItems = 4,
                maxChars = (budget.factTokens * 3).coerceAtLeast(720),
                additionalLineageIds = conversation.experienceSessionIds.toSet(),
                recordFilter = { memory ->
                    // The active story state owns the current relationship. Historical
                    // RELATIONSHIP_STATE records are retrieval fallbacks, not a second
                    // concurrent authority able to contradict an already-active state.
                    val currentStateKnown = viewerSubjectKey == null &&
                        snapshot.chat.chatState.updatedAt != 0L &&
                        (snapshot.chat.chatState.relationshipState !in setOf("熟悉中", "熟悉", "刚认识", "初识") ||
                            snapshot.chat.chatState.dynamics.stage != "FAMILIAR")
                    (memory.kind != MemoryKind.RELATIONSHIP_STATE || !currentStateKnown) &&
                    relationshipMemoryMatchesSubject(
                        memory = memory,
                        currentSubjectKey = subjectKey,
                        currentLineageId = snapshot.lineageId,
                        subjectLabel = subjectLabel,
                        experienceId = conversation.experienceId.ifBlank { snapshot.sessionId },
                        sourceSessionIds = (conversation.experienceSessionIds + snapshot.sessionId).toSet(),
                    )
                },
            ).filter { memory ->
                ChatUnifiedTurnContext.allowsHistoricalRecord(query, conversation, memory.content)
            }.distinctBy { it.id }
            if (recalled.isNotEmpty()) {
                recalled.forEach { fact ->
                    val source = fact.sourceMessages.lastOrNull()
                    recallEvidence += ChatRecallEvidence(
                        "fact", fact.content, source?.sessionId.orEmpty(),
                        source?.messageId.orEmpty(),
                    )
                }
                blocks += takeWithinModelTokenBudget(
                    buildString {
                        appendLine(
                            if (groupAudience) {
                                "【本轮相关精确事实｜当前人物所知】这些事实属于当前人物；其他群成员的所知以实际公开事件为准。"
                            } else {
                                "【本轮相关长期事实】"
                            },
                        )
                        recalled.forEach { appendLine("- ${it.content}") }
                    }.trim(),
                    budget.factTokens,
                )
            }
        }

        val diaryReady = !searchDiary || runCatching {
            memoryProjection.recoverPendingDiaryInvalidations()
        }.onFailure { error ->
            AppLog.warn("ChatMemory",
                "failure=true operation=recover_diary_invalidations " +
                    "session_id=${snapshot.sessionId.take(80)} cause_type=${error.javaClass.simpleName}")
            runtimeStateStore.projection.publishError("关联日记清理待恢复，本轮暂不使用日记：${error.message}")
        }.isSuccess
        if (searchDiary && diaryReady) {
            val diary = persistence.diaryStore.search(
                // Use only recent public user evidence to resolve demonstratives such as
                // "after that"; private thoughts never become search terms.
                query = ChatMemorySelector.semanticQuery(
                    recallQuery, "", conversation,
                ),
                subjectKey = subjectKey,
                groupAudience = groupAudience,
                maxItems = diaryRecallItemLimit(recallQuery),
                experienceId = conversation.experienceId.ifBlank { snapshot.sessionId },
                sourceSessionIds = (conversation.experienceSessionIds + snapshot.sessionId).toSet(),
            ).filter { entry ->
                // Superseded generated answers are retained for viewing, not recalled as facts
                // while sampling another answer for the same user turn.
                entry.sources.none { source ->
                    source.sessionId == snapshot.sessionId &&
                        source.assistantMessageId in excludedReplies
                } && ChatUnifiedTurnContext.allowsHistoricalRecord(
                    query, conversation, renderRecalledChatDiary(entry, groupAudience),
                )
            }
            if (diary.isNotEmpty()) {
                diary.forEach { entry ->
                    val source = entry.sources.lastOrNull()
                    recallEvidence += ChatRecallEvidence(
                        "diary", entry.event, source?.sessionId.orEmpty(),
                        source?.assistantMessageId?.ifBlank { source.userMessageId }.orEmpty(),
                    )
                }
                blocks += takeWithinModelTokenBudget(
                    buildString {
                        appendLine("【相关人物日记｜角色自己的长期经历】")
                        appendLine(diaryRecallUsageInstruction(groupAudience))
                        packCompleteCharacterUnits(
                            diary.map { renderRecalledChatDiary(it, groupAudience) },
                            (budget.diaryTokens - 100).coerceAtLeast(0),
                        ).forEach(::appendLine)
                    }.trim(),
                    budget.diaryTokens,
                )
            }
        }

        val projected = takeWithinModelTokenBudget(
            blocks.filter(String::isNotBlank).joinToString("\n\n") +
                if (blocks.isEmpty()) "" else "\n\n已被明确修正的旧版本仅属于历史；人物日记中的感受和猜测属于人物主观认识。",
            budget.totalTokens,
        )
        onRecallUsed(recallEvidence.filter { it.content.isNotBlank() && projected.contains(it.content) }.distinct())
        return projected
    }
}
