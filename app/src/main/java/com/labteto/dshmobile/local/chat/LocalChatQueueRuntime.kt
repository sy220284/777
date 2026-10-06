package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.model.LocalForegroundModelHistoryRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Chat-owned foreground queue continuation.
 *
 * One owner clears the completed visible Job, commits the next queued user model message, then
 * starts the next Chat turn. The Provider breaks the queue -> starter -> dispatcher -> executor
 * construction cycle without giving Engine another callback.
 */
@Singleton
internal class LocalChatQueueRuntime @Inject constructor(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val modelHistory: LocalForegroundModelHistoryRuntime,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val starter: Provider<LocalChatTurnStarter>,
) {
    internal fun finishTurnAndStartNext(completedJob: Job?) {
        val next = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            val handle = runtimeStateStore.foregroundRunHandle
            if (handle.job === completedJob) handle.job = null
            prepareNextLocked()
        }
        next?.start()
    }

    internal fun startNextIfIdle(): Job? =
        synchronized(runtimeStateStore.foregroundRunHandle.lock) {
            prepareNextLocked()
        }

    private fun prepareNextLocked(): Job? {
        val handle = runtimeStateStore.foregroundRunHandle
        val state = runtimeStateStore.state.value
        if (
            state.usageMode != LocalUsageMode.CHAT ||
            runtimeStateStore.sessionTransitioning ||
            handle.hasLiveJob()
        ) return null

        val queued = handle.pendingInputs.pollCommitted { input, remaining ->
            val message = input.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", input.content)
            }
            sessionStorage.eventLogs.get(state.sessionId).append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "resumed",
                    pending = remaining,
                    affected = listOf(input),
                    modelMessages = listOf(message),
                ),
            )
        } ?: return null
        val durableMessage = queued.modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", queued.content)
        }
        modelHistory.history.append(durableMessage)
        modelHistory.refreshMetrics(state.sessionId)
        runtimeStateStore.projection.setForegroundQueuedInputCount(
            state.sessionId,
            handle.pendingInputs.size(),
        )
        runtimeStateStore.performVisibleOperation("Chat 排队续跑快照保存失败") {
            check(sessionStorage.enqueueCurrentSnapshot(state.sessionId)) {
                "Chat 排队续跑前台会话已切换"
            }
        }

        return starter.get().start(
            content = queued.content,
            memoryInput = queued.memoryInput,
            sourceMessageId = queued.id,
        ).also { handle.job = it }
    }
}
