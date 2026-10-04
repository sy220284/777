package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.GroupAnnouncementService
import com.labteto.dshmobile.local.chat.PersonaAutoFillService
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.presentation.LocalUiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns UI-triggered model helper transactions that are independent from the main chat turn. */
internal class LocalChatModelAssistController(
    private val runtime: LocalUiRuntime,
    private val personaAutoFillService: PersonaAutoFillService,
    private val groupAnnouncementService: GroupAnnouncementService,
) {
    private val state = runtime.session.state

    suspend fun generateGroupAnnouncement(direction: String): Result<String> =
        try {
            val snapshot = state.value
            check(!snapshot.loading && !snapshot.running && snapshot.chat.groupChat.enabled) {
                "请在群聊空闲时生成公告"
            }
            check(snapshot.modelState.configured) { "请先配置聊天模型" }
            check(snapshot.chat.groupChat.members.size >= 2) { "请先添加至少两位群聊人物" }
            Result.success(
                groupAnnouncementService.generate(
                    model = snapshot.modelState.model,
                    baseUrl = snapshot.modelState.baseUrl,
                    profileId = snapshot.modelState.modelSelection.activeProfileId,
                    members = snapshot.chat.groupChat.members,
                    direction = direction,
                    current = snapshot.chat.groupChat.announcement,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    suspend fun autoFillCurrentPersona(description: String): Result<PersonaProfile> {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.running ||
            snapshot.usageMode != LocalUsageMode.CHAT ||
            snapshot.chat.groupChat.enabled
        ) {
            return Result.failure(IllegalStateException("persona_autofill_busy"))
        }
        if (!snapshot.modelState.configured) {
            return Result.failure(IllegalStateException("persona_autofill_unconfigured"))
        }
        return try {
            val recentMessages = withContext(Dispatchers.IO) {
                runtime.session.transcriptTailForUi(snapshot.sessionId, PERSONA_AUTOFILL_RECENT_MESSAGES)
            }
            val generated = personaAutoFillService.generate(
                model = snapshot.modelState.model,
                baseUrl = snapshot.modelState.baseUrl,
                profileId = snapshot.modelState.modelSelection.activeProfileId,
                current = snapshot.chat.chatPersona,
                recentMessages = recentMessages,
                description = description,
            )
            val current = state.value
            check(
                current.sessionId == snapshot.sessionId &&
                    current.modelState.modelSelection.activeProfileId == snapshot.modelState.modelSelection.activeProfileId &&
                    current.chat.chatPersona == snapshot.chat.chatPersona &&
                    !current.loading &&
                    !current.running &&
                    current.usageMode == LocalUsageMode.CHAT &&
                    !current.chat.groupChat.enabled
            ) {
                "persona_autofill_stale"
            }
            Result.success(runtime.chat.syncDefaultChatPersona(generated))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private companion object {
        const val PERSONA_AUTOFILL_RECENT_MESSAGES = 12
    }
}
