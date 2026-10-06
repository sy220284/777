package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.send.LocalSendResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Chat-owned product execution entrypoint and target-Session lifecycle owner. */
@Singleton
internal class LocalChatExecutionCoordinator @Inject constructor(
    private val sendCoordinator: LocalChatSendCoordinator,
    private val timeline: LocalChatTimelineCoordinator,
    private val detachedExecution: LocalChatAutomationExecutionCoordinator,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
) : LocalChatExecutionPort {
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detachedRuns =
        ConcurrentHashMap<String, Deferred<LocalChatAutomationResult>>()

    override fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult = sendCoordinator.send(text, attachments)

    override fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalChatUserEditResult = timeline.editAndResendUserMessage(messageId, replacement)

    override fun regenerateReply(messageId: String): Boolean =
        timeline.regenerateReply(messageId)

    override suspend fun execute(request: LocalChatExecutionRequest): LocalChatExecutionResult {
        val instruction = request.instruction.trim()
        val sessionId = request.targetSessionId.trim()
        if (instruction.isEmpty()) {
            return LocalChatExecutionResult(
                sessionId = sessionId.ifBlank { null },
                output = "聊天执行意图不能为空",
                status = LocalChatExecutionStatus.FAILED,
                detail = "聊天执行意图不能为空",
            )
        }
        if (sessionId.isEmpty()) {
            return LocalChatExecutionResult(
                sessionId = null,
                output = "聊天执行必须指定目标会话",
                status = LocalChatExecutionStatus.FAILED,
                detail = "聊天执行必须指定目标会话",
            )
        }

        val deferred = detachedScope.async(start = CoroutineStart.LAZY) {
            detachedExecution.run(
                instruction = instruction,
                targetSessionId = sessionId,
                timeoutMillis = request.timeoutMillis,
                recoverInterrupted = request.recoverInterrupted,
                recoveryStartedAt = request.recoveryStartedAt,
                policy = request.automationPolicy,
            )
        }
        while (true) {
            val current = detachedRuns.putIfAbsent(sessionId, deferred)
            if (current == null) break
            if (current.isCompleted && detachedRuns.remove(sessionId, current)) continue
            deferred.cancel()
            val detail = "目标 Chat 会话已有执行正在运行"
            return LocalChatExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalChatExecutionStatus.BLOCKED,
                detail = detail,
            )
        }
        deferred.start()
        return try {
            val result = deferred.await()
            LocalChatExecutionResult(
                sessionId = result.sessionId,
                output = result.output,
                status = result.status,
                detail = result.detail,
                nextRunAtHint = result.nextRunAtHint,
                waitingForUserReply = result.waitingForUserReply,
            )
        } catch (timeout: TimeoutCancellationException) {
            LocalChatExecutionResult(
                sessionId = sessionId,
                output = "Chat 后台执行超时",
                status = LocalChatExecutionStatus.FAILED,
                detail = "Chat 后台执行超时",
            )
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                deferred.cancel()
                throw cancelled
            }
            val detail = cancelled.message ?: "聊天执行已取消"
            LocalChatExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalChatExecutionStatus.CANCELLED,
                detail = detail,
            )
        } catch (error: Throwable) {
            val detail = error.message ?: error::class.java.simpleName
            LocalChatExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalChatExecutionStatus.FAILED,
                detail = detail,
            )
        } finally {
            detachedRuns.remove(sessionId, deferred)
        }
    }

    override fun cancel(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        var requested = false
        detachedRuns[sessionId]?.takeIf { !it.isCompleted }?.let { run ->
            run.cancel()
            requested = true
        }
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId == sessionId && snapshot.usageMode == LocalUsageMode.CHAT) {
            requested = LocalChatPostTurnJobOwner.cancel() || requested
            if (runtimeStateStore.foregroundRunHandle.hasLiveJob()) {
                runtimeStateStore.cancelForegroundRun(sessionStorage.eventLogs.get(sessionId))
                requested = true
            }
        }
        return requested
    }

    override suspend fun cancelAndJoin(sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        var requested = false
        detachedRuns[sessionId]?.let { run ->
            if (!run.isCompleted) {
                run.cancel()
                requested = true
            }
            run.join()
        }
        val snapshot = runtimeStateStore.state.value
        if (snapshot.sessionId == sessionId && snapshot.usageMode == LocalUsageMode.CHAT) {
            requested = LocalChatPostTurnJobOwner.cancelAndJoin() || requested
            if (runtimeStateStore.foregroundRunHandle.hasLiveJob()) {
                runtimeStateStore.cancelForegroundRunAndJoin(sessionStorage.eventLogs.get(sessionId))
                requested = true
            }
        }
        return requested
    }
}
