package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalSessionCoordinator
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the explicit user-save transaction for a group announcement.
 *
 * Runtime state is updated optimistically so the UI stays responsive, but success is returned only
 * after the complete session snapshot is durable. A failed durable write restores the previous
 * announcement when no newer edit has replaced it.
 */
internal suspend fun saveGroupChatAnnouncement(
    state: MutableStateFlow<LocalHarnessState>,
    text: String,
    sessionId: String,
    transcriptProjectedThroughSequence: Long?,
    sessionCoordinator: LocalSessionCoordinator,
    eventLog: LocalSessionEventLog,
): Result<Unit> {
    val before = state.value
    if (
        before.loading ||
        before.kernel.running ||
        before.sessionId != sessionId ||
        before.usageMode != LocalUsageMode.CHAT ||
        !before.chat.groupChat.enabled
    ) {
        return Result.failure(IllegalStateException("当前状态暂时无法保存群公告"))
    }

    val announcement = text.trim().take(2_000)
    if (announcement == before.chat.groupChat.announcement) return Result.success(Unit)

    state.update { current ->
        if (current.sessionId == sessionId && current.chat.groupChat.enabled) {
            current.copy(chat = current.chat.copy(groupChat = current.chat.groupChat.copy(announcement = announcement)))
        } else {
            current
        }
    }
    // Match the common persistence boundary: capture the durable cursor before mutable state.
    val controlProjectedThroughSequence = eventLog.latestSequence()
    val updated = state.value
    if (updated.sessionId != sessionId || updated.chat.groupChat.announcement != announcement) {
        return Result.failure(IllegalStateException("会话状态已变化，请重新保存群公告"))
    }

    return try {
        sessionCoordinator.writeNow(
            sessionCoordinator.snapshot(
                sessionId = sessionId,
                state = updated,
                controlProjectedThroughSequence = controlProjectedThroughSequence,
                transcriptProjectedThroughSequence = transcriptProjectedThroughSequence,
            ),
        )
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        rollbackGroupAnnouncement(state, before, announcement)
        throw cancelled
    } catch (error: Throwable) {
        rollbackGroupAnnouncement(state, before, announcement)
        Result.failure(error)
    }
}

private fun rollbackGroupAnnouncement(
    state: MutableStateFlow<LocalHarnessState>,
    before: LocalHarnessState,
    failedAnnouncement: String,
) {
    state.update { current ->
        if (
            current.sessionId == before.sessionId &&
            current.chat.groupChat.announcement == failedAnnouncement
        ) {
            current.copy(
                chat = current.chat.copy(
                    groupChat = current.chat.groupChat.copy(
                        announcement = before.chat.groupChat.announcement,
                    ),
                ),
            )
        } else {
            current
        }
    }
}
