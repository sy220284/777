package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalSessionControlProjection
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
        controls: LocalSessionControlProjection,
        messages: List<LocalHarnessMessage>,
        log: LocalSessionEventLog,
    ): LocalChatSessionRestore = withContext(Dispatchers.IO) {
        val behavior = reconcileCharacterBehaviorTuning(
            persistence.personaStore, persistence.galleryStore,
            controls.personaId, controls.galleryId, controls.chatState,
        )
        val group = if (usageMode == LocalUsageMode.CHAT) {
            controls.groupChat.copy(context = controls.groupChat.context.boundDurablePending(log, "group"))
        } else LocalGroupChatState()
        LocalChatSessionRestore(
            chat = LocalChatState(
                personaId = controls.personaId,
                galleryId = controls.galleryId,
                galleryStoryId = controls.galleryStoryId,
                gallerySaveSuppressedThrough = controls.gallerySaveSuppressedThrough,
                chatPersona = behavior.persona,
                chatState = behavior.chatState,
                chatContext = controls.chatContext.boundDurablePending(log),
                replySuggestions = controls.replySuggestions,
                chatBranches = if (usageMode == LocalUsageMode.CHAT && !group.enabled) {
                    restoreMaterializedChatBranchState(
                        current = controls.chatBranches,
                        activeMessages = messages,
                        chatState = behavior.chatState,
                        replySuggestions = controls.replySuggestions,
                    )
                } else LocalChatBranchState(),
                groupChat = reconcileGroupCharacterBehaviorTuning(
                    group, persistence.personaStore, persistence.galleryStore,
                ),
            ),
            needsPersist = behavior.changed,
        )
    }

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
