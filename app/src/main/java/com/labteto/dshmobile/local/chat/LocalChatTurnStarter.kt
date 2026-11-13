package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.observability.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Chat-owned lazy foreground turn starter with no cross-Feature execution callback. */
@Singleton
internal class LocalChatTurnStarter @Inject constructor(
    private val dispatcher: LocalChatTurnDispatcher,
    private val chatState: LocalChatStatePort,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val queue: LocalChatQueueRuntime,
) : LocalChatTurnPort {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                AppLog.error("LocalChatTurnStarter", "chat turn failure", throwable)

            },
    )

    override fun start(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
        sessionLease: LocalSessionRuntimeLease?,
    ): Job {
        val sessionId = runtimeStateStore.currentSessionId
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                LocalSessionRuntimeRegistry.withOwner(
                    sessionId, LocalSessionRuntimeKind.FOREGROUND, sessionLease,
                ) {
                    dispatcher.run(sessionId, content, memoryInput, sourceMessageId)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                chatState.update { current ->
                    if (current.sessionId == sessionId) current.copy(
                        error = error.message ?: "聊天回合失败",
                    ) else current
                }
                throw error
            } finally {
                // The next turn can reserve ownership only after this turn's lease is released.
                queue.finishTurnAndStartNext(currentCoroutineContext()[Job])
            }
        }
        job.invokeOnCompletion { sessionLease?.close() }
        return job
    }
}
