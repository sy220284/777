package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Work-owned first-turn binding and foreground inbox resumption.
 *
 * Shared Runtime remains the authority for foreground history/cursor and Session leases. Work owns
 * creating its run binding, projecting the initiating user turn and starting the Work Agent turn.
 */
internal class LocalWorkTurnStarter(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runTurn: suspend (
        input: String,
        memoryInput: String,
        sourceMessageId: String,
        binding: LocalWorkRunBinding,
        preownedLease: LocalSessionRuntimeLease,
    ) -> Unit,
) : LocalWorkTurnPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startPrepared(
        prepared: com.labteto.dshmobile.local.send.LocalPreparedSend,
        sessionLease: LocalSessionRuntimeLease,
    ): Job = startFresh(
        content = prepared.content,
        memoryInput = prepared.memoryInput,
        modelMessage = prepared.modelMessage,
        sessionLease = sessionLease,
        visibleContent = prepared.visibleContent,
        visibleBlocks = prepared.blocks,
    )

    internal fun startFresh(
        content: String,
        memoryInput: String,
        modelMessage: JsonObject?,
        sessionLease: LocalSessionRuntimeLease,
        visibleContent: String = content,
        visibleBlocks: List<LocalMessageBlock> = emptyList(),
    ): Job {
        val sessionId = requireWorkSession()
        val durableMessage = modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", content)
        }
        val transcriptMessage = LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            content = visibleContent,
            createdAt = System.currentTimeMillis(),
            blocks = visibleBlocks,
        )
        val eventLog = sessionStorage.eventLogs.get(sessionId)
        val event = eventLog.append("user/message", buildJsonObject {
            put("content", visibleContent)
            put("model_message", durableMessage)
            put("queued", false)
            put("transcript", encodeTranscriptMessages(listOf(transcriptMessage)))
        })
        runtimeStateStore.projection.appendForegroundTranscript(
            sessionId = sessionId,
            messages = listOf(transcriptMessage),
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                event.sequence,
            )
        appendForegroundHistory(sessionId, durableMessage)
        check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
            "Work 首轮持久化时前台会话已切换"
        }
        return startBound(
            content = content,
            memoryInput = memoryInput,
            sourceMessageId = transcriptMessage.id,
            sessionLease = sessionLease,
        )
    }

    internal fun startNextResumed(sessionLease: LocalSessionRuntimeLease): Job? {
        val sessionId = requireWorkSession()
        check(sessionStorage.currentSnapshot(sessionId) != null) {
            "Work 排队续跑前台会话已切换"
        }
        val pending = runtimeStateStore.foregroundRunHandle.pendingInputs
        val input = pending.pollCommitted { next, remaining ->
            val message = next.modelMessage ?: buildJsonObject {
                put("role", "user")
                put("content", next.content)
            }
            sessionStorage.eventLogs.get(sessionId).append(
                LOCAL_AGENT_INBOX_EVENT_TYPE,
                encodeLocalAgentInboxEvent(
                    action = "resumed",
                    pending = remaining,
                    affected = listOf(next),
                    modelMessages = listOf(message),
                ),
            )
        } ?: return null
        val durableMessage = input.modelMessage ?: buildJsonObject {
            put("role", "user")
            put("content", input.content)
        }
        appendForegroundHistory(sessionId, durableMessage)
        runtimeStateStore.projection.setForegroundQueuedInputCount(sessionId, pending.size())
        // The event is authoritative. Snapshot materialization must not prevent an admitted turn.
        runtimeStateStore.performVisibleOperation("Work 排队续跑快照保存失败") {
            check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
                "Work 排队续跑持久化时前台会话已切换"
            }
        }
        return startBound(input.content, input.memoryInput, input.id, sessionLease)
    }

    internal fun startExisting(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
        sessionLease: LocalSessionRuntimeLease,
    ): Job {
        requireWorkSession()
        return startBound(
            content = content,
            memoryInput = memoryInput,
            sourceMessageId = sourceMessageId,
            sessionLease = sessionLease,
        )
    }

    private fun appendForegroundHistory(
        sessionId: String,
        message: JsonObject,
    ) {
        val history = runtimeStateStore.foregroundRunHandle.modelHistory
        history.append(message)
        val resources = runtimeStateStore.resourceSnapshot()
        runtimeStateStore.projection.updateContextMetrics(
            sessionId = sessionId,
            contextChars = history.encodedChars,
            contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(
                runtimeStateStore.state.value,
                resources,
            ),
        )
    }

    private fun startBound(
        content: String,
        memoryInput: String,
        sourceMessageId: String,
        sessionLease: LocalSessionRuntimeLease,
    ): Job {
        val sessionId = requireWorkSession()
        val sessionBase = sessionStorage.currentSnapshot(sessionId)
            ?: error("Work 绑定创建时前台会话已切换")
        val foreground = runtimeStateStore.state.value
        val runHandle = runtimeStateStore.foregroundRunHandle
        val binding = LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = foreground
                .copy(kernel = foreground.kernel.copy(running = true), error = null)
                .toLocalWorkRunState(),
            sessionBase = sessionBase,
            runHandle = LocalAgentRunHandle(
                initialSessionId = sessionId,
                initialHistory = runHandle.modelHistory.snapshot(),
                initialTranscriptProjectionCursor = runHandle.transcriptProjectionCursor,
                maxPendingInputs = MAX_PENDING_INPUTS,
            ),
            eventLog = sessionStorage.eventLogs.get(sessionId),
        )
        // The recovered foreground inbox becomes this Work run's single executable inbox.
        runHandle.pendingInputs.transferTo(binding.runHandle.pendingInputs)
        try {
            workRunRegistry.attach(binding)
            binding.runHandle.projectionJob = scope.launch {
                binding.state.collect {
                    workRunRegistry.mirrorVisible(binding)
                }
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runTurn(
                    content,
                    memoryInput,
                    sourceMessageId,
                    binding,
                    sessionLease,
                )
            }
            job.invokeOnCompletion {
                try { workRunRegistry.releaseCompletedTurn(binding, job) }
                finally { sessionLease.close() }
            }
            binding.runHandle.job = job
            return job
        } catch (error: Throwable) {
            binding.runHandle.projectionJob?.cancel()
            workRunRegistry.detach(binding)
            binding.runHandle.pendingInputs.transferTo(runHandle.pendingInputs)
            throw error
        }
    }

    private fun requireWorkSession(): String {
        val state = runtimeStateStore.state.value
        check(state.usageMode == LocalUsageMode.WORK) { "Work turn starter 只能处理 Work 会话" }
        check(state.sessionId == runtimeStateStore.currentSessionId) { "Work 前台会话身份不一致" }
        return state.sessionId
    }
}
