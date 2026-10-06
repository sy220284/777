package com.labteto.dshmobile.local.chat

import android.content.Context
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import kotlinx.coroutines.CancellationException

/**
 * Group-chat service lifetime inside the dispatcher's already acquired session ownership.
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
) {
    if (currentSessionId() != sessionId) {
        throw CancellationException("会话已切换")
    }
    LocalExecutionService.withTurn(
        context = context,
        sessionId = sessionId,
        error = currentError,
    ) {
        executor.run(input, sourceMessageId)
    }
}
