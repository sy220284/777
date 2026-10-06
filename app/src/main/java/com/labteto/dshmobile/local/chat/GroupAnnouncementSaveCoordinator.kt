package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.CancellationException

/** Chat owns the durable announcement; Session snapshots are derived caches. */
internal suspend fun saveGroupChatAnnouncement(
    state: LocalChatStatePort,
    text: String,
    sessionId: String,
    commitDomainState: (LocalChatProjectionState) -> Unit,
    persistNow: suspend (String) -> Boolean,
): Result<Unit> {
    val lease = LocalSessionRuntimeRegistry.tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE)
        ?: return Result.failure(IllegalStateException("当前会话忙，请稍后保存群公告"))
    try {
        val before = state.value
        if (
            before.loading || before.kernel.running || before.sessionId != sessionId ||
            before.usageMode != LocalUsageMode.CHAT || !before.chat.groupChat.enabled
        ) return Result.failure(IllegalStateException("当前状态暂时无法保存群公告"))
        val announcement = text.trim().take(2_000)
        if (announcement == before.chat.groupChat.announcement) return Result.success(Unit)
        val target = before.copy(
            chat = before.chat.copy(groupChat = before.chat.groupChat.copy(announcement = announcement)),
        )
        // Failed authority writes leave the projection untouched.
        commitDomainState(target)
        state.update { current ->
            if (current.sessionId == sessionId) current.copy(chat = target.chat) else current
        }
        try {
            check(persistNow(sessionId)) { "会话已切换，群公告将在恢复时重新物化" }
        } catch (cancelled: CancellationException) {
            // The fact is already committed. Cancellation cannot roll it back.
            throw cancelled
        } catch (error: Throwable) {
            state.update { current ->
                if (current.sessionId == sessionId) current.copy(
                    error = "群公告已保存，快照更新失败：${error.message}",
                ) else current
            }
        }
        return Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        return Result.failure(error)
    } finally {
        lease.close()
    }
}
