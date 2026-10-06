package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.observability.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Chat-owned lazy foreground turn starter; no Engine composition callback is required. */
@Singleton
internal class LocalChatTurnStarter @Inject constructor(
    private val dispatcher: LocalChatTurnDispatcher,
    private val chatState: LocalChatStatePort,
) : LocalChatTurnPort {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                AppLog.error("LocalChatTurnStarter", "chat turn failure", throwable)
                chatState.update { current ->
                    current.copy(
                        error = throwable.message?.takeIf(String::isNotBlank)
                            ?: "聊天回合失败：${throwable::class.java.simpleName}",
                    )
                }
            },
    )

    override fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
    ): Job = scope.launch(start = CoroutineStart.LAZY) {
        dispatcher.run(content, memoryInput, sourceMessageId)
    }
}
