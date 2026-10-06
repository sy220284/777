package com.labteto.dshmobile.local.work

import android.content.Context
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.local.LocalModelRequestCoordinator
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.durableModelHistorySnapshot
import com.labteto.dshmobile.local.model.withEphemeralContext
import com.labteto.dshmobile.local.model.withoutLastCompletedAssistantReply
import com.labteto.dshmobile.local.recordForeground
import com.labteto.dshmobile.local.runtime.LocalExecutionService
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeKind
import com.labteto.dshmobile.local.runtime.LocalSessionRuntimeRegistry
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.encodeTranscriptMessages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Work-owned final-answer regeneration.
 *
 * This path never replays tools. Shared Model owns transport/retry/overflow, Shared Runtime owns
 * foreground state/history, and Session Event remains the durable transcript authority.
 */
@Singleton
internal class LocalWorkReplyRegenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val sessionStorage: LocalSessionStorageRuntime,
    private val modelRequests: LocalModelRequestCoordinator,
    private val usageTracker: DeepSeekUsageTracker,
) {
    private val checkpointCodec = ModelHistoryCheckpointCodec()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun start(messageId: String): Job {
        val sessionId = runtimeStateStore.currentSessionId
        return scope.launch(start = CoroutineStart.LAZY) {
            LocalSessionRuntimeRegistry.withOwner(
                sessionId,
                LocalSessionRuntimeKind.FOREGROUND,
            ) { ownedSessionId ->
                if (runtimeStateStore.currentSessionId != ownedSessionId) {
                    throw CancellationException("会话已切换")
                }
                LocalExecutionService.withTurn(
                    context,
                    ownedSessionId,
                    { runtimeStateStore.state.value.error },
                ) {
                    regenerate(messageId)
                }
            }
        }
    }

    internal suspend fun regenerate(messageId: String) {
        val sessionId = runtimeStateStore.currentSessionId
        runtimeStateStore.projection.setForegroundRunning(
            sessionId = sessionId,
            running = true,
            error = null,
        )
        try {
            val snapshot = runtimeStateStore.state.value
            check(snapshot.sessionId == sessionId && snapshot.usageMode == com.labteto.dshmobile.local.LocalUsageMode.WORK) {
                "Work 重生成只能处理当前 Work 会话"
            }
            val history = runtimeStateStore.foregroundRunHandle.modelHistory
            val log = sessionStorage.eventLogs.get(sessionId)
            val messages = withEphemeralContext(
                history.snapshot().withoutLastCompletedAssistantReply(),
                "基于本轮已有结果重写最终回复；不要调用工具或声称重新执行。",
            )
            val reply = modelRequests.complete(
                snapshot = snapshot,
                messages = messages,
                step = 1,
                toolsOverride = JsonArray(emptyList()),
                publishPreviewEnabled = false,
                persistOverflowHistory = true,
                requestLog = log,
                previewGuard = {
                    runtimeStateStore.currentSessionId == sessionId &&
                        runtimeStateStore.state.value.sessionId == sessionId
                },
            )
            commitReply(
                sessionId = sessionId,
                messageId = messageId,
                snapshot = snapshot,
                reply = reply,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtimeStateStore.projection.publishError(error.message ?: "重新生成失败")
        } finally {
            runtimeStateStore.projection.setForegroundRunning(
                sessionId = sessionId,
                running = false,
                error = runtimeStateStore.state.value.error,
            )
        }
    }

    private fun commitReply(
        sessionId: String,
        messageId: String,
        snapshot: com.labteto.dshmobile.local.LocalHarnessState,
        reply: LocalModelReply,
    ) {
        val content = reply.content?.takeIf(String::isNotBlank)
            ?: error("模型没有返回可用回复")
        usageTracker.recordForeground(
            snapshot = snapshot,
            reply = reply,
            action = TokenUsageAction.WORK_MAIN,
            turnId = messageId,
            taskLabel = "regenerate",
            step = 1,
        )

        val transcriptMessage = LocalHarnessMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            content = content,
            createdAt = System.currentTimeMillis(),
        )
        val log = sessionStorage.eventLogs.get(sessionId)
        val event = log.append(
            "assistant/message",
            JsonObject(
                reply.message +
                    ("replaces" to JsonPrimitive(messageId)) +
                    ("transcript" to encodeTranscriptMessages(listOf(transcriptMessage))),
            ),
        )

        val history = runtimeStateStore.foregroundRunHandle.modelHistory
        history.reset(history.snapshot().withoutLastCompletedAssistantReply())
        history.append(reply.message)

        val resources = runtimeStateStore.resourceSnapshot()
        runtimeStateStore.projection.updateContextMetrics(
            sessionId = sessionId,
            contextChars = history.encodedChars,
            contextBudgetChars = runtimeStateStore.contextBudgetCharsFor(
                runtimeStateStore.state.value,
                resources,
            ),
        )
        runtimeStateStore.projection.removeForegroundTranscriptMessage(sessionId, messageId)
        runtimeStateStore.projection.appendForegroundTranscript(
            sessionId = sessionId,
            messages = listOf(transcriptMessage),
        )
        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
            maxOf(
                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                event.sequence,
            )

        log.append(
            ModelHistoryCheckpointCodec.EVENT_TYPE,
            checkpointCodec.encode(
                messages = durableModelHistorySnapshot(history.snapshot()),
                reason = "work/regenerated",
                asOfSequence = log.latestSequence(),
            ),
        )
        runtimeStateStore.foregroundRunHandle.turnsSinceModelHistoryCheckpoint = 0
        check(sessionStorage.enqueueCurrentSnapshot(sessionId)) {
            "Work 重生成提交时前台会话已切换"
        }
    }
}
