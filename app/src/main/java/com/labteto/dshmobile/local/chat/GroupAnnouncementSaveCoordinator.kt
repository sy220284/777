package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.CancellationException

internal const val MAX_GROUP_CHAT_ANNOUNCEMENT_CHARS = 2_000

/** The sole announcement fact writer. Callers freeze foreground binding before entry. */
internal fun commitGroupChatAnnouncement(
    state: LocalChatStatePort,
    text: String,
    sessionId: String,
    commitDomainState: (LocalChatProjectionState) -> Unit,
): Result<Boolean> {
    val lease = LocalSessionRuntimeRegistry.tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE)
        ?: return Result.failure(IllegalStateException("当前会话忙，请稍后保存群公告"))
    return try {
        val before = state.value
        if (before.loading || before.kernel.running || before.sessionId != sessionId ||
            before.usageMode != LocalUsageMode.CHAT || !before.chat.groupChat.enabled)
            return Result.failure(IllegalStateException("当前状态暂时无法保存群公告"))
        val announcement = text.trim().take(MAX_GROUP_CHAT_ANNOUNCEMENT_CHARS)
        if (announcement == before.chat.groupChat.announcement) return Result.success(false)
        val target = before.copy(chat = before.chat.copy(groupChat = before.chat.groupChat.copy(announcement = announcement)))
        commitDomainState(target)
        state.update { current -> if (current.sessionId == sessionId) current.copy(chat = target.chat) else current }
        Result.success(true)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { Result.failure(failure) }
    finally { lease.close() }
}

/** Compatibility path: snapshot failure cannot undo an already committed announcement fact. */
internal suspend fun saveGroupChatAnnouncement(
    state: LocalChatStatePort,
    text: String,
    sessionId: String,
    commitDomainState: (LocalChatProjectionState) -> Unit,
    persistNow: suspend (String) -> Boolean,
): Result<Unit> {
    val committed = commitGroupChatAnnouncement(state, text, sessionId, commitDomainState)
    if (committed.isFailure) return Result.failure(requireNotNull(committed.exceptionOrNull()))
    if (committed.getOrThrow()) {
        try { check(persistNow(sessionId)) { "会话已切换，群公告将在恢复时重新物化" } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            state.update { current -> if (current.sessionId == sessionId)
                current.copy(error = "群公告已保存，快照更新失败：${failure.message}") else current }
        }
    }
    return Result.success(Unit)
}
