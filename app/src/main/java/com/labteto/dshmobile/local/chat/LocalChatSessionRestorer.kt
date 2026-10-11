package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.memory.MemoryStore
import com.labteto.dshmobile.local.model.chatSystemPrompt
import com.labteto.dshmobile.local.model.groupChatSystemPrompt
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LocalChatSessionRestore(val chat: LocalChatState, val needsPersist: Boolean)

/** Chat owns behavior reconciliation, timeline repair, group recovery and its prompt policy. */
@Singleton
internal class LocalChatSessionRestorer @Inject constructor(
    private val persistence: LocalChatPersistence,
    private val memoryStore: MemoryStore,
) {
    internal fun repairTimeline(log: LocalSessionEventLog) = recoverPendingTimelineRewriteProjection(
        log, memoryStore, persistence.galleryStore, persistence.diaryStore,
    )

    internal suspend fun restore(
        usageMode: LocalUsageMode,
        controls: LocalChatDurableState,
        messages: List<LocalHarnessMessage>,
        log: LocalSessionEventLog,
        sessionId: String = "",
    ): LocalChatSessionRestore = withContext(Dispatchers.IO) {
        val behavior = reconcileCharacterBehaviorTuning(
            persistence.personaStore, persistence.galleryStore,
            controls.personaId, controls.galleryId, controls.chatState,
        )
        val story = controls.galleryId?.let(persistence.galleryStore::findEntry)
            ?.stories?.firstOrNull { it.id == controls.galleryStoryId }
        val baseContext = controls.chatContext.boundDurablePending(log).withExperience(
            story?.id ?: sessionId, story?.sourceSessionIds.orEmpty() + sessionId,
        )
        val context = reconcileDiaryContext(baseContext, persistence.diaryStore.decisionsForExperience(
            chatRelationshipSubjectKey(controls.galleryId, controls.personaId), baseContext,
        ))
        val reconciledState = persistence.diaryStore.reconcileState(
            chatRelationshipSubjectKey(controls.galleryId, controls.personaId), behavior.chatState, context,
        )
        val group = if (usageMode == LocalUsageMode.CHAT) {
            controls.groupChat.copy(context = controls.groupChat.context.boundDurablePending(log, "group").withExperience(sessionId, listOf(sessionId)))
        } else LocalGroupChatState()
        val reconciledGroup = reconcileGroupCharacterBehaviorTuning(
                    group, persistence.personaStore, persistence.galleryStore,
                ).let { restored ->
                    val decisions = restored.members.flatMap { member ->
                        persistence.diaryStore.decisionsForExperience(
                            chatRelationshipSubjectKey(member.galleryId, member.personaId), restored.context,
                        )
                    }
                    restored.copy(
                        context = reconcileDiaryContext(restored.context, decisions),
                        members = restored.members.map { member ->
                            member.copy(chatState = persistence.diaryStore.reconcileState(
                                chatRelationshipSubjectKey(member.galleryId, member.personaId), member.chatState, restored.context,
                            ))
                        },
                    )
                }
        LocalChatSessionRestore(
            chat = LocalChatState(
                personaId = controls.personaId,
                galleryId = controls.galleryId,
                galleryStoryId = controls.galleryStoryId,
                gallerySaveSuppressedThrough = controls.gallerySaveSuppressedThrough,
                chatPersona = behavior.persona,
                chatState = reconciledState,
                chatContext = context,
                replySuggestions = controls.replySuggestions,
                chatBranches = if (usageMode == LocalUsageMode.CHAT && !group.enabled) {
                    restoreMaterializedChatBranchState(
                        current = controls.chatBranches,
                        activeMessages = messages,
                        chatState = reconciledState,
                        replySuggestions = controls.replySuggestions,
                    )
                } else LocalChatBranchState(),
                groupChat = reconciledGroup,
            ),
            needsPersist = behavior.changed || context != controls.chatContext ||
                reconciledState != behavior.chatState || reconciledGroup != controls.groupChat,
        )
    }

    internal fun repairPostTurnProjections(log: LocalSessionEventLog) =
        recoverChatPostTurnProjections(log, persistence.diaryStore)

    internal fun restoreGalleryProjection(chat: LocalChatState) {
        projectGroupGalleryState(chat.groupChat, persistence.galleryStore).failures.forEach { failure ->
            AppLog.warn(
                "LocalChatSessionRestorer",
                "群聊人物库投影恢复失败 galleryId=${failure.galleryId} detail=${failure.detail}",
            )
        }
    }

    internal fun systemPrompt(group: Boolean): String =
        if (group) groupChatSystemPrompt() else chatSystemPrompt()
}
