package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.runtime.LocalSessionStorageRuntime
import com.labteto.dshmobile.local.runtime.MAX_PENDING_INPUTS
import com.labteto.dshmobile.local.send.LocalPreparedSend
import com.labteto.dshmobile.local.send.LocalSendFeedbackState
import com.labteto.dshmobile.local.send.LocalSendRejectReason
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.local.send.LocalSendDisposition
import com.labteto.dshmobile.local.session.LocalUserMessageEditResult
import com.labteto.dshmobile.local.send.prepareLocalSend
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.session.coordinateOwnedLocalSend
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal val LOCAL_AGENT_TEAM_DIRECTIVE = """
    【本轮执行方式：Agent 集群】
    用户已明确选择 Agent 集群。你是 Lead，必须使用 Agent Team 能力组织本轮任务。
    先读取当前 Team 与任务板；能复用现有成员时优先复用，避免重复创建身份。
    复杂任务先建立共享任务与依赖，再按职责创建/启动成员；team_spawn 可用于一步创建并启动。
    由 Lead 使用 team_task_update 认领和分配任务，成员专注执行并回报，不要求没有任务板权限的子代理自行认领。成员产出通过 team_wait_for_message / team_messages 回收，不依赖猜测后台状态。
    等待前先调用 team_messages 获取 next_cursor；之后始终把该值作为 after_sequence 传给 team_wait_for_message，并用每次返回的新 next_cursor 继续等待，避免重复或漏掉提前到达的结果。team_messages 返回 has_more=true 时继续分页，不把扫描预算耗尽当作没有结果。
    收到结果后核验任务要求；缺少输入、失败或部分结果不能结算。核验通过后由 Lead 调用 team_task_update action=complete，附上 result_id 与当前 expected_revision。工具若暂未显示，先使用 capability_search 恢复所需工具并继续，不能以最终回复代替任务板结算。
    成员失败、停用或解雇后，其未完成任务会自动释放回任务板，由 Lead 重新分配。
    临时停止用 team_interrupt；保留成员但停止使用可 team_disable_member；永久移除用 team_dismiss_member；整体暂停用 team_stop_all。
    关键任务未完成、仍有成员运行或结果尚未回收时禁止宣称整体完成。
    每位成员使用稳定英文内部 name 和清晰中文 display_name，界面只展示中文名；授权额外 MCP、LSP、GitHub 能力前由 Lead 核实任务用途与连接条件，再通过 allowed_extensions 显式授予。
    最终回复面向用户说明结果，不把 tool 名、task id、revision、mailbox 等内部实现术语当成答复主体。
""".trimIndent()

internal fun prepareLocalAgentTeamSend(
    text: String,
    attachments: List<LocalImportedAttachment>,
): LocalPreparedSend? {
    val prepared = prepareLocalSend(text, attachments) ?: return null
    return prepared.copy(
        modelMessage = JsonObject(prepared.modelMessage + (LOCAL_WORK_EXECUTION_MODE_KEY to JsonPrimitive("agent_team"))),
    )
}

/** Work-owned product execution entry. */
@Singleton
internal class LocalWorkExecutionCoordinator internal constructor(
    private val workRunRegistry: LocalWorkRunRegistry,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val enqueueSnapshot: (String) -> Boolean,
    private val startPreparedTurn: (LocalPreparedSend, com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease) -> Job,
    private val startRegeneration: (String) -> Job,
    private val prepareEditedTurn: (String, String) -> LocalWorkMessageEditPreparation,
    private val prepareDetachedSession: suspend (String, String?) -> String = { _, _ ->
        error("Work 后台执行入口尚未装配")
    },
    private val runDetached: suspend (String, String?, Long, Boolean) -> LocalWorkAutomationResult =
        { _, _, _, _ -> error("Work 后台执行入口尚未装配") },
) : LocalWorkExecutionPort {
    @Inject
    internal constructor(
        workRunRegistry: LocalWorkRunRegistry,
        runtimeStateStore: LocalRuntimeStateStore,
        sessionStorage: LocalSessionStorageRuntime,
        turn: LocalWorkTurnPort,
        regenerator: LocalWorkReplyRegenerator,
        automationExecution: LocalWorkAutomationExecutionCoordinator,
        userMessageEditor: LocalWorkUserMessageEditor,
    ) : this(
        workRunRegistry = workRunRegistry,
        runtimeStateStore = runtimeStateStore,
        eventLogFor = sessionStorage.eventLogs::get,
        enqueueSnapshot = sessionStorage::enqueueCurrentSnapshot,
        startPreparedTurn = turn::startPrepared,
        startRegeneration = regenerator::start,
        prepareEditedTurn = userMessageEditor::rewrite,
        prepareDetachedSession = automationExecution::prepareSession,
        runDetached = automationExecution::run,
    )

    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detachedRuns =
        ConcurrentHashMap<String, Deferred<LocalWorkAutomationResult>>()

    override suspend fun prepareSession(
        text: String,
        preferredSessionId: String?,
    ): String = prepareDetachedSession(text, preferredSessionId)

    override suspend fun execute(request: LocalWorkExecutionRequest): LocalWorkExecutionResult {
        val prompt = request.text.trim()
        if (prompt.isEmpty()) {
            return LocalWorkExecutionResult(
                sessionId = request.targetSessionId,
                output = "Work 执行输入不能为空",
                status = LocalWorkExecutionStatus.FAILED,
                detail = "Work 执行输入不能为空",
            )
        }
        val sessionId = try {
            prepareDetachedSession(prompt, request.targetSessionId)
        } catch (timeout: TimeoutCancellationException) {
            return LocalWorkExecutionResult(
                sessionId = request.targetSessionId,
                output = "Work 执行会话准备超时",
                status = LocalWorkExecutionStatus.FAILED,
                detail = "Work 执行会话准备超时",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.message ?: error::class.java.simpleName
            return LocalWorkExecutionResult(
                sessionId = request.targetSessionId,
                output = detail,
                status = LocalWorkExecutionStatus.FAILED,
                detail = detail,
            )
        }

        val deferred = detachedScope.async(start = CoroutineStart.LAZY) {
            runDetached(
                prompt,
                sessionId,
                request.timeoutMillis,
                request.recoverInterrupted,
            )
        }
        while (true) {
            val current = detachedRuns.putIfAbsent(sessionId, deferred)
            if (current == null) break
            if (current.isCompleted && detachedRuns.remove(sessionId, current)) continue
            deferred.cancel()
            val detail = "目标 Work 会话已有执行正在运行"
            return LocalWorkExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkExecutionStatus.BLOCKED,
                detail = detail,
            )
        }
        deferred.start()
        return try {
            val result = deferred.await()
            LocalWorkExecutionResult(
                sessionId = result.sessionId,
                output = result.output,
                status = result.status,
                detail = result.detail,
            )
        } catch (timeout: TimeoutCancellationException) {
            LocalWorkExecutionResult(
                sessionId = sessionId,
                output = "Work 后台执行超时",
                status = LocalWorkExecutionStatus.FAILED,
                detail = "Work 后台执行超时",
            )
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                deferred.cancel()
                throw cancelled
            }
            val detail = cancelled.message ?: "任务已取消"
            LocalWorkExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkExecutionStatus.CANCELLED,
                detail = detail,
            )
        } catch (error: Throwable) {
            val detail = error.message ?: error::class.java.simpleName
            LocalWorkExecutionResult(
                sessionId = sessionId,
                output = detail,
                status = LocalWorkExecutionStatus.FAILED,
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
        if (workRunRegistry.requestCancel(sessionId)) requested = true

        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.sessionId == sessionId &&
            snapshot.usageMode == LocalUsageMode.WORK &&
            runtimeStateStore.foregroundRunHandle.hasLiveJob()
        ) {
            requested = runtimeStateStore.cancelForegroundRun(eventLogFor(sessionId)) || requested
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
        workRunRegistry[sessionId]?.let { binding ->
            try {
                binding.cancelAndJoin()
                requested = true
            } finally {
                workRunRegistry.detach(binding)
            }
        }

        val snapshot = runtimeStateStore.state.value
        if (
            snapshot.sessionId == sessionId &&
            snapshot.usageMode == LocalUsageMode.WORK &&
            runtimeStateStore.foregroundRunHandle.hasLiveJob()
        ) {
            runtimeStateStore.cancelForegroundRunAndJoin(eventLogFor(sessionId))
            requested = true
        }
        return requested
    }

    override fun send(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult {
        val prepared = prepareLocalSend(text, attachments) ?: return LocalSendResult.Empty
        return sendPrepared(prepared)
    }

    override fun sendWithTeam(
        text: String,
        attachments: List<LocalImportedAttachment>,
    ): LocalSendResult {
        val prepared = prepareLocalAgentTeamSend(text, attachments) ?: return LocalSendResult.Empty
        return sendPrepared(prepared)
    }

    private fun sendPrepared(
        prepared: LocalPreparedSend,
        reservedLease: com.labteto.dshmobile.local.runtime.LocalSessionRuntimeLease? = null,
    ): LocalSendResult {
        var started: Job? = null
        try {
            if (reservedLease == null) workRunRegistry.enqueueIntoLiveRun(prepared)?.let { return it }
            val result = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
                if (reservedLease == null) workRunRegistry.enqueueIntoLiveRun(prepared)?.let { return@synchronized it }

            val snapshot = runtimeStateStore.state.value
            if ((runtimeStateStore.foregroundRunHandle.cancellationRequested && runtimeStateStore.foregroundRunHandle.hasLiveJob()) || snapshot.usageMode != LocalUsageMode.WORK) {
                return@synchronized reject(
                    snapshot.sessionId,
                    LocalSendResult.rejected(LocalSendRejectReason.SESSION_TRANSITION),
                )
            }

            val sessionId = snapshot.sessionId
            val pending = runtimeStateStore.foregroundRunHandle.pendingInputs
            val queuedInput = QueuedAgentInput(
                prepared.content,
                prepared.memoryInput,
                prepared.modelMessage,
                UUID.randomUUID().toString(),
            )
            coordinateOwnedLocalSend(
                usageMode = LocalUsageMode.WORK,
                sessionId = sessionId,
                workBindingActive = false,
                visibleJobActive = runtimeStateStore.foregroundRunHandle.hasLiveJob(),
                configured = snapshot.modelState.configured,
                loading = snapshot.loading,
                sessionTransitioning = runtimeStateStore.sessionTransitioning,
                pendingCount = pending.size(),
                pendingLimit = MAX_PENDING_INPUTS,
                onRejected = { rejected -> reject(sessionId, rejected) },
                preownedLease = reservedLease,
                onAccepted = runtimeStateStore::clearSendFeedback,
                enqueue = {
                    pending.offer(queuedInput) {
                        val transcript = LocalHarnessMessage(
                            id = queuedInput.id,
                            role = "user",
                            content = prepared.visibleContent,
                            createdAt = System.currentTimeMillis(),
                            blocks = prepared.blocks,
                        )
                        val event = eventLogFor(sessionId).append(
                            LOCAL_AGENT_INBOX_EVENT_TYPE,
                            encodeLocalAgentInboxEvent(
                                action = "queued",
                                pending = pending.snapshot(),
                                affected = listOf(queuedInput),
                                transcript = listOf(transcript),
                            ),
                        )
                        runtimeStateStore.projection.appendForegroundTranscript(
                            sessionId = sessionId,
                            messages = listOf(transcript),
                        )
                        runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor =
                            maxOf(
                                runtimeStateStore.foregroundRunHandle.transcriptProjectionCursor ?: -1L,
                                event.sequence,
                            )
                    }
                },
                onQueued = {
                    runtimeStateStore.projection.setForegroundQueuedInputCount(
                        sessionId = sessionId,
                        count = pending.size(),
                    )
                    check(enqueueSnapshot(sessionId)) {
                        "Work 排队保存时前台会话已切换"
                    }
                },
                onStart = { reservedLease ->
                    if (runtimeStateStore.foregroundRunHandle.cancellationRequested) {
                        runtimeStateStore.prepareForegroundRestart(eventLogFor(sessionId))
                    }
                    started = startPreparedTurn(
                        prepared,
                        requireNotNull(reservedLease) {
                            "Work 首轮启动前必须持有前台 Session 租约"
                        },
                    )
                },
            )
        }
            started?.start()
            return result
        } finally {
            // A failed admission must not strand the editing transaction's ownership.
            if (started == null) reservedLease?.close()
        }
    }

    override fun editAndResendUserMessage(
        messageId: String,
        replacement: String,
    ): LocalUserMessageEditResult = synchronized(runtimeStateStore.foregroundRunHandle.lock) {
        var committed = false
        try {
            when (val edit = prepareEditedTurn(messageId, replacement)) {
                is LocalWorkMessageEditPreparation.Rejected -> edit.reason
                is LocalWorkMessageEditPreparation.Ready -> {
                    committed = true
                    val sent = sendPrepared(edit.send, edit.reservedLease)
                    if (sent.disposition == LocalSendDisposition.STARTED) LocalUserMessageEditResult.SENT
                    else {
                        runtimeStateStore.projection.publishError(
                            "历史修改已保存，但新任务未接单（${sent.rejectReason ?: sent.disposition}）。请重新打开会话检查当前历史，避免重复编辑", 
                        )
                        LocalUserMessageEditResult.COMMITTED_NOT_STARTED
                    }
                }
            }
        } catch (error: Exception) {
            runtimeStateStore.projection.publishError(
                (if (committed) "历史修改已保存，但新任务启动失败" else "Work 历史编辑失败") +
                    "：${error.message ?: error::class.java.simpleName}",
            )
            if (committed) LocalUserMessageEditResult.COMMITTED_NOT_STARTED
            else LocalUserMessageEditResult.FAILED
        }
    }

    override fun regenerateReply(messageId: String): Boolean {
        fun rejectRegeneration(reason: String): Boolean {
            runtimeStateStore.projection.publishError(reason)
            return false
        }
        var started: Job? = null
        val handle = runtimeStateStore.foregroundRunHandle
        val accepted = synchronized(handle.lock) {
            val state = runtimeStateStore.state.value
            if (
                state.usageMode != LocalUsageMode.WORK ||
                !state.modelState.configured ||
                state.loading ||
                state.chat.groupChat.enabled ||
                state.kernel.running ||
                runtimeStateStore.sessionTransitioning ||
                handle.hasLiveJob() ||
                handle.pendingInputs.size() != 0
            ) return@synchronized rejectRegeneration(
                if (!state.modelState.configured) "请先配置可用模型，再重新生成最终回复"
                else "工作会话正忙或正在切换，请等待执行结束后重试",
            )

            val last = state.messages.lastOrNull()
                ?: return@synchronized rejectRegeneration("当前会话没有可重新生成的最终回复")
            if (last.id != messageId || last.role != "assistant") {
                return@synchronized rejectRegeneration("只能重新生成当前会话的最后一条助手回复")
            }
            if (state.messages.dropLast(1).none { it.role == "user" }) {
                return@synchronized rejectRegeneration("无法定位最终回复所对应的原始任务")
            }
            if (
                handle.modelHistory.lastOrNull()
                    ?.get("role")
                    ?.jsonPrimitive
                    ?.contentOrNull != "assistant"
            ) return@synchronized rejectRegeneration("模型历史尚未恢复完整，不能安全重新生成最终回复")

            started = startRegeneration(messageId).also { handle.job = it }
            true
        }
        started?.start()
        return accepted
    }

    private fun reject(
        sessionId: String,
        result: LocalSendResult,
    ): LocalSendResult {
        runtimeStateStore.publishSendFeedback(
            LocalSendFeedbackState(
                sessionId = sessionId,
                rejectReason = result.rejectReason,
                rejectLimit = result.rejectLimit,
            ),
        )
        return result
    }
}
