package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.CancellationException

/**
 * Chat-owned foreground ownership wrapper for one group-chat turn.
 *
 * Session ownership and foreground-service lifetime remain Shared Runtime capabilities; Chat owns
 * when the multi-character executor is admitted and rejects stale visible-session work.
 */
internal suspend fun runOwnedGroupChatTurn(
    context: Context,
    sessionId: String,
    currentSessionId: () -> String,
    currentError: () -> String?,
    executor: LocalGroupChatTurnExecutor,
    input: String,
    sourceMessageId: String? = null,
) = LocalSessionRuntimeRegistry.withOwner(
    sessionId,
    LocalSessionRuntimeKind.FOREGROUND,
) { ownedSessionId ->
    if (currentSessionId() != ownedSessionId || sessionId != ownedSessionId) {
        throw CancellationException("会话已切换")
    }
    LocalExecutionService.withTurn(
        context = context,
        sessionId = ownedSessionId,
        error = currentError,
    ) {
        executor.run(input, sourceMessageId)
    }
}
