package com.labteto.dshmobile.harness.jobs

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class JobContinuationPersistenceException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

data class JobInfo(
    val id: String,
    val label: String,
    val status: String,
    val ownerId: String? = null,
    val isAgent: Boolean = false,
    val canMessage: Boolean = false,
    val continuable: Boolean = false,
    val pendingMessageCount: Int = 0,
    val updatedAt: Long = 0L,
)

data class JobStartResult(
    val accepted: Boolean,
    val id: String?,
    val message: String,
)

data class JobMessageAdmission(
    val accepted: Boolean,
    val duplicate: Boolean,
    val requiresResume: Boolean,
    val message: String,
)

data class JobSnapshot(
    val id: String,
    val label: String,
    val status: String,
    val output: String = "",
    val resumeKind: String? = null,
    val resumePayload: String? = null,
    val ownerId: String? = null,
    val startedAt: Long = 0L,
    val deadlineAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val inbox: List<QueuedAgentInput> = emptyList(),
    val continuable: Boolean = false,
)

/** Process-agnostic shared job controller used by the Android runtime adapter. */
class HarnessJobManager(
    private val scope: CoroutineScope,
    private val onChanged: (List<JobInfo>) -> Unit,
    private val idFactory: () -> String = {
        "job-" + UUID.randomUUID().toString().replace("-", "").take(16)
    },
    private val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    private val maxRetainedJobs: Int = DEFAULT_MAX_RETAINED_JOBS,
    initialSnapshots: List<JobSnapshot> = emptyList(),
    private val onSnapshotsChanged: (List<JobSnapshot>) -> Unit = { },
) {
    init {
        require(maxConcurrentJobs in 1..32) { "后台任务并发上限必须在 1..32 之间" }
        require(maxRetainedJobs in maxConcurrentJobs..512) {
            "后台任务保留上限必须不少于并发上限，且不超过 512"
        }
    }

    private data class Record(
        val id: String,
        val label: String,
        var status: String = "running",
        var output: String = "",
        var job: Job? = null,
        val inbox: MutableList<QueuedAgentInput> = mutableListOf(),
        val resumeKind: String? = null,
        val resumePayload: String? = null,
        val continuable: Boolean = false,
        var ownerId: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val deadlineAt: Long = 0L,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    // Capture and commit in one order. Otherwise an older callback/write can arrive after a
    // terminal snapshot and restore a running UI or durable record.
    private val publicationLock = Any()
    private val records = linkedMapOf<String, Record>()
    private val removingOwners = mutableSetOf<String>()

    private fun Record.occupiesSlot(): Boolean = status == "running" || job?.isCompleted == false

    private fun Record.isContinuableAgent(): Boolean =
        continuable &&
            label.startsWith(AGENT_PREFIX) &&
            !resumeKind.isNullOrBlank()

    private fun Record.isIdleContinuableAgent(): Boolean =
        isContinuableAgent() && (status == "dormant" || status == "completed")

    private fun Record.isResumable(): Boolean =
        job?.isCompleted != false &&
            (
                status == "interrupted" ||
                    (isIdleContinuableAgent() && inbox.isNotEmpty())
            )

    init {
        synchronized(lock) {
            initialSnapshots.takeLast(maxRetainedJobs).forEach { snapshot ->
                val interrupted = snapshot.status == "running"
                val restoredContinuable =
                    snapshot.continuable ||
                        (
                            interrupted &&
                                snapshot.resumeKind == CONTINUABLE_AGENT_RESUME_KIND
                            )
                val restoredStatus = when {
                    interrupted -> "interrupted"
                    snapshot.status == "completed" &&
                        restoredContinuable &&
                        snapshot.label.startsWith(AGENT_PREFIX) -> "dormant"
                    else -> snapshot.status
                }
                records[snapshot.id] = Record(
                    id = snapshot.id,
                    label = snapshot.label.take(MAX_LABEL),
                    status = restoredStatus,
                    output = if (interrupted) {
                        snapshot.output.takeLast(MAX_OUTPUT).let { previous ->
                            val detail = if (snapshot.resumeKind.isNullOrBlank()) {
                                interruptedProcessDetail(snapshot)
                            } else "进程中断，等待安全恢复"
                            if (previous.isBlank()) detail
                            else "${previous}\n$detail".takeLast(MAX_OUTPUT)
                        }
                    } else {
                        snapshot.output.takeLast(MAX_OUTPUT)
                    },
                    resumeKind = snapshot.resumeKind,
                    resumePayload = snapshot.resumePayload,
                    continuable = restoredContinuable,
                    ownerId = snapshot.ownerId,
                    startedAt = snapshot.startedAt,
                    deadlineAt = snapshot.deadlineAt,
                    updatedAt = snapshot.updatedAt,
                    inbox = snapshot.inbox
                        .takeLast(MAX_INBOX_MESSAGES)
                        .map { message ->
                            QueuedAgentInput(
                                id = message.id.take(MAX_INBOX_ID),
                                content = message.content.take(MAX_INBOX_MESSAGE),
                                memoryInput = message.memoryInput.take(MAX_INBOX_MESSAGE),
                                modelMessage = message.modelMessage,
                            )
                        }
                        .filter { it.id.isNotBlank() && it.content.isNotBlank() }
                        .toMutableList(),
                )
            }
        }
        publish()
    }

    fun start(
        label: String,
        expectedDurationMillis: Long? = null,
        ownerId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = startInternal(
        label = label,
        resumeKind = null,
        resumePayload = null,
        ownerId = ownerId,
        expectedDurationMillis = expectedDurationMillis,
        block = block,
    ).message

    fun startPersistent(
        label: String,
        resumeKind: String,
        resumePayload: String,
        ownerId: String? = null,
        continuable: Boolean = false,
        requestedId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String = startPersistentResult(
        label = label,
        resumeKind = resumeKind,
        resumePayload = resumePayload,
        ownerId = ownerId,
        continuable = continuable,
        requestedId = requestedId,
        block = block,
    ).message

    fun startPersistentResult(
        label: String,
        resumeKind: String,
        resumePayload: String,
        ownerId: String? = null,
        continuable: Boolean = false,
        requestedId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): JobStartResult {
        require(resumeKind.isNotBlank()) { "持久任务恢复类型不能为空" }
        require(resumePayload.length <= MAX_RESUME_PAYLOAD) {
            "持久任务恢复元数据过大：最多允许 $MAX_RESUME_PAYLOAD 个字符"
        }
        return startInternal(
            label = label,
            resumeKind = resumeKind.take(MAX_RESUME_KIND),
            resumePayload = resumePayload,
            ownerId = ownerId,
            continuable = continuable,
            requestedId = requestedId,
            expectedDurationMillis = null,
            block = block,
        )
    }

    fun resumePersistent(
        id: String,
        ownerId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        var previousStatus = ""
        var previousOutput = ""
        var previousUpdatedAt = 0L
        var previousOwnerId: String? = null
        var previousJob: Job? = null
        val record = synchronized(lock) {
            val found = records[id] ?: return "后台任务不存在：$id"
            if ((ownerId ?: found.ownerId) in removingOwners) return "会话正在移除，无法恢复后台任务"
            if (found.resumeKind.isNullOrBlank()) return "后台任务不可恢复：$id"
            if (!found.isResumable()) {
                return "后台任务无需恢复：$id [${found.status}]"
            }
            val running = records.values.count { it.occupiesSlot() }
            if (running >= maxConcurrentJobs) {
                return "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务"
            }
            previousStatus = found.status
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
            previousOwnerId = found.ownerId
            previousJob = found.job
            if (found.isIdleContinuableAgent() && found.job?.isCompleted != false) {
                found.job = null
            }
            if (!ownerId.isNullOrBlank()) found.ownerId = ownerId
            found.status = "running"
            found.output = "正在从安全检查点恢复…"
            found.updatedAt = System.currentTimeMillis()
            found
        }
        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                record.status = previousStatus
                record.output = previousOutput
                record.ownerId = previousOwnerId
                record.job = previousJob
                record.updatedAt = previousUpdatedAt
            }
            notifyChanged()
            throw IllegalStateException("持久任务恢复元数据写入失败，任务未启动", error)
        }
        launchRecord(record, block)
        notifyChanged()
        return "后台任务已恢复：${record.id}"
    }

    fun interruptedSnapshots(): List<JobSnapshot> = synchronized(lock) {
        records.values
            .filter { it.status == "interrupted" && !it.resumeKind.isNullOrBlank() }
            .map(::snapshot)
    }

    fun resumableSnapshots(): List<JobSnapshot> = synchronized(lock) {
        records.values
            .filter { record ->
                !record.resumeKind.isNullOrBlank() && record.isResumable()
            }
            .map(::snapshot)
    }

    fun pendingContinuableAgentMessageSnapshots(): List<JobSnapshot> = synchronized(lock) {
        records.values
            .filter { record ->
                record.isContinuableAgent() &&
                    record.inbox.isNotEmpty() &&
                    record.status in setOf("running", "dormant", "completed", "interrupted")
            }
            .map(::snapshot)
    }

    fun snapshots(): List<JobSnapshot> = synchronized(lock) { records.values.map(::snapshot) }

    /** Current user-facing projection; keeps live callbacks and replay snapshots on one mapping. */
    fun infos(): List<JobInfo> = snapshotRecords()

    fun availableSlots(): Int = synchronized(lock) {
        (maxConcurrentJobs - records.values.count { it.occupiesSlot() }).coerceAtLeast(0)
    }

    fun completeInterrupted(
        id: String,
        output: String,
        ownerId: String? = null,
    ): String {
        var previousOutput = ""
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return "后台任务不存在：$id"
            if (found.status != "interrupted") {
                return "后台任务无需结算：$id [${found.status}]"
            }
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
            found.status = if (found.isContinuableAgent()) "dormant" else "completed"
            found.output = output.takeLast(MAX_OUTPUT)
            found.updatedAt = System.currentTimeMillis()
            found
        }
        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                record.status = "interrupted"
                record.output = previousOutput
                record.updatedAt = previousUpdatedAt
            }
            notifyChanged()
            throw IllegalStateException("持久任务完成状态写入失败，仍保留为可恢复状态", error)
        }
        notifyChanged()
        return "后台任务已从持久检查点结算：$id"
    }

    fun settleResumableAgentIfInboxClaimed(
        id: String,
        output: String,
        claimedMessageIds: Set<String>,
        ownerId: String? = null,
    ): Boolean = synchronized(publicationLock) {
        var infos: List<JobInfo>? = null
        val settled = synchronized(lock) {
            val found = records[id]
            val resumable =
                found?.status == "interrupted" ||
                    found?.isIdleContinuableAgent() == true
            if (
                found == null ||
                (ownerId != null && found.ownerId != ownerId) ||
                !resumable ||
                found.inbox.any { message -> message.id !in claimedMessageIds }
            ) {
                false
            } else {
                val previousStatus = found.status
                val previousOutput = found.output
                val previousInbox = found.inbox.toList()
                val previousUpdatedAt = found.updatedAt
                found.inbox.removeAll { message -> message.id in claimedMessageIds }
                found.status = if (found.isContinuableAgent()) "dormant" else "completed"
                found.output = output.takeLast(MAX_OUTPUT)
                found.updatedAt = System.currentTimeMillis()
                try {
                    onSnapshotsChanged(records.values.map(::snapshot))
                } catch (error: Exception) {
                    found.status = previousStatus
                    found.output = previousOutput
                    found.inbox.clear()
                    found.inbox.addAll(previousInbox)
                    found.updatedAt = previousUpdatedAt
                    throw IllegalStateException(
                        "持久代理终态结算写入失败，仍保留为可恢复状态",
                        error,
                    )
                }
                infos = records.values.map { it.toJobInfo() }
                true
            }
        }
        infos?.let(onChanged)
        settled
    }

    fun failInterrupted(id: String, detail: String): String {
        synchronized(lock) {
            val record = records[id] ?: return "后台任务不存在：$id"
            if (record.status != "interrupted") return "后台任务无需标记失败：$id [${record.status}]"
            record.status = "failed"
            record.output = detail.takeLast(MAX_OUTPUT)
            record.updatedAt = System.currentTimeMillis()
        }
        publish()
        return "后台任务恢复失败：$id"
    }

    fun failResumable(id: String, detail: String): String {
        var previousStatus = ""
        var previousOutput = ""
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id] ?: return "后台任务不存在：$id"
            if (!found.isResumable()) {
                return "后台任务无需标记恢复失败：$id [${found.status}]"
            }
            previousStatus = found.status
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
            found.status = "failed"
            found.output = detail.takeLast(MAX_OUTPUT)
            found.updatedAt = System.currentTimeMillis()
            found
        }
        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                record.status = previousStatus
                record.output = previousOutput
                record.updatedAt = previousUpdatedAt
            }
            notifyChanged()
            throw IllegalStateException("后台任务恢复失败状态写入失败，仍保留为可恢复状态", error)
        }
        notifyChanged()
        return "后台任务恢复失败：$id"
    }

    private fun startInternal(
        label: String,
        resumeKind: String?,
        resumePayload: String?,
        ownerId: String?,
        continuable: Boolean = false,
        requestedId: String? = null,
        expectedDurationMillis: Long? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): JobStartResult {
        val record = synchronized(lock) {
            if (ownerId in removingOwners) {
                return JobStartResult(false, null, "会话正在移除，无法启动后台任务")
            }
            pruneRetainedLocked()
            val running = records.values.count { it.occupiesSlot() }
            if (running >= maxConcurrentJobs) {
                return JobStartResult(
                    false,
                    null,
                    "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务",
                )
            }
            if (records.size >= maxRetainedJobs) {
                return JobStartResult(
                    false,
                    null,
                    "后台任务保留上限已满：请先终止不再需要的持久代理或清理已结束任务",
                )
            }
            val id = requestedId?.let { raw ->
                val candidate = raw.trim()
                require(candidate.isNotEmpty() && candidate.length <= MAX_JOB_ID) {
                    "指定后台任务编号长度必须在 1..$MAX_JOB_ID"
                }
                require(candidate.matches(Regex("[A-Za-z0-9_-]+"))) {
                    "指定后台任务编号只允许字母、数字、下划线和短横线"
                }
                if (records.containsKey(candidate)) {
                    return JobStartResult(false, null, "后台任务编号已存在：$candidate")
                }
                candidate
            } ?: allocateUniqueIdLocked()
            val startedAt = System.currentTimeMillis()
            val deadlineAt = expectedDurationMillis
                ?.takeIf { it > 0L }
                ?.let { duration -> startedAt + duration.coerceAtMost(MAX_EXPECTED_DURATION_MILLIS) }
                ?: 0L
            Record(
                id = id,
                label = label.take(MAX_LABEL),
                resumeKind = resumeKind,
                resumePayload = resumePayload,
                continuable = continuable,
                ownerId = ownerId,
                startedAt = startedAt,
                deadlineAt = deadlineAt,
                updatedAt = startedAt,
            ).also { records[id] = it }
        }
        val requiresDurableStart = !resumeKind.isNullOrBlank() || expectedDurationMillis != null
        if (requiresDurableStart) {
            try {
                persistCurrentSnapshots()
            } catch (error: Exception) {
                synchronized(lock) { records.remove(record.id) }
                notifyChanged()
                throw IllegalStateException("后台任务元数据写入失败，任务未启动", error)
            }
            launchRecord(record, block)
            notifyChanged()
        } else {
            launchRecord(record, block)
            publish()
        }
        return JobStartResult(
            accepted = true,
            id = record.id,
            message = "后台任务已启动：${record.id}",
        )
    }

    private fun launchRecord(
        record: Record,
        block: suspend (String, (String) -> Unit) -> String,
    ) {
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val heartbeat = launch {
                while (isActive) {
                    delay(RUNNING_HEARTBEAT_MILLIS)
                    synchronized(lock) {
                        if (record.status != "running") return@launch
                        record.updatedAt = System.currentTimeMillis()
                    }
                    publish()
                }
            }
            try {
                val report: (String) -> Unit = { output ->
                    synchronized(lock) {
                        record.output = output.takeLast(MAX_OUTPUT)
                        record.updatedAt = System.currentTimeMillis()
                    }
                    // Progress is a volatile UI signal. Heartbeats persist a bounded running
                    // snapshot; writing the whole durable store for every output chunk amplifies IO.
                    notifyChanged()
                }
                val result = block(record.id, report).takeLast(MAX_OUTPUT)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (record.status != "running") throw CancellationException("后台任务已停止")
                    record.output = result
                    record.status = if (record.isContinuableAgent()) "dormant" else "completed"
                    record.updatedAt = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (record.status == "running") {
                        record.status = "cancelled"
                        record.output = "任务已取消"
                        record.updatedAt = System.currentTimeMillis()
                    }
                }
                throw cancelled
            } catch (error: JobContinuationPersistenceException) {
                synchronized(lock) {
                    if (record.status != "running") return@synchronized
                    record.status = "interrupted"
                    record.output = "续跑检查点暂时无法持久化，已保留上一份安全状态等待恢复"
                    record.updatedAt = System.currentTimeMillis()
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (record.status != "running") return@synchronized
                    record.status = "failed"
                    record.output = "任务失败：${error.message ?: error::class.java.simpleName}"
                    record.updatedAt = System.currentTimeMillis()
                }
            } finally {
                heartbeat.cancel()
                persistTerminalState(record)
                notifyChanged()
            }
        }
        val shouldStart = synchronized(lock) {
            if (record.status == "running" && record.job == null) {
                record.job = launched
                true
            } else {
                false
            }
        }
        launched.invokeOnCompletion { notifyChanged() }
        if (shouldStart) {
            launched.start()
        } else {
            launched.cancel()
        }
    }

    fun list(ownerId: String? = null): String = snapshotRecords(ownerId).let { snapshot ->
        if (snapshot.isEmpty()) "没有后台任务"
        else snapshot.joinToString("\n") { "${it.id} [${it.status}] ${publicLabel(it.label)}" }
    }

    fun listAgents(ownerId: String? = null): String = synchronized(lock) {
        records.values
            .filter { ownerId == null || it.ownerId == ownerId }
            .filter { it.label.startsWith(AGENT_PREFIX) }
            .map { "${it.id} [${it.status}] ${it.label.removePrefix(AGENT_PREFIX)}" }
    }.let { if (it.isEmpty()) "没有后台代理" else it.joinToString("\n") }

    fun output(id: String, ownerId: String? = null): String {
        return synchronized(lock) {
            val record = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return "后台任务不存在：$id"
            "${record.id} [${record.status}] ${publicLabel(record.label)}\n${record.output.ifBlank { "暂无输出" }}"
        }
    }

    /**
     * Interrupt only the live Activation of one durable continuable Agent.
     *
     * Identity, durable Inbox and resume metadata remain intact. A later message may cold-resume
     * the same Agent. Permanent cancellation continues to use [kill].
     */
    fun interruptContinuableAgent(
        id: String,
        ownerId: String? = null,
    ): String = synchronized(publicationLock) {
        var previousStatus = ""
        var previousOutput = ""
        var previousUpdatedAt = 0L
        var liveJob: Job? = null
        val record = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return@synchronized null
            if (!found.isContinuableAgent()) {
                return@synchronized found
            }
            if (found.status == "dormant" || found.status == "interrupted") {
                return@synchronized found
            }
            if (found.status != "running") {
                return@synchronized found
            }
            previousStatus = found.status
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
            liveJob = found.job
            found.status = "interrupted"
            found.output = "当前执行轮次已中断；Agent 身份、恢复状态与待处理消息已保留"
            found.updatedAt = System.currentTimeMillis()
            found
        } ?: return@synchronized "后台代理不存在：$id"

        if (!record.isContinuableAgent()) {
            return@synchronized "目标不是可继续后台代理：$id"
        }
        if (record.status == "dormant") {
            return@synchronized "后台代理当前没有运行中的执行轮次：$id [dormant]"
        }
        if (record.status == "interrupted" && previousStatus.isBlank()) {
            return@synchronized "后台代理当前没有运行中的执行轮次：$id [interrupted]"
        }
        if (previousStatus.isBlank()) {
            return@synchronized "后台代理当前不可中断：$id [${record.status}]"
        }

        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                record.status = previousStatus
                record.output = previousOutput
                record.updatedAt = previousUpdatedAt
            }
            notifyChanged()
            throw IllegalStateException("持久代理中断状态写入失败，当前执行轮次未中断", error)
        }

        liveJob?.cancel(CancellationException("当前 Agent Activation 已被中断"))
        notifyChanged()
        "已中断后台代理当前执行轮次：$id；身份与待处理消息已保留"
    }

    /**
     * Interrupt multiple durable continuable Agents with one persistence commit.
     *
     * Identity, durable Inbox and resume metadata remain intact. Persistence is committed once
     * before any coroutine cancellation starts; a persistence failure rolls every status back.
     */
    fun interruptContinuableAgents(
        ids: Set<String>,
        ownerId: String? = null,
    ): String = synchronized(publicationLock) {
        val requested = ids.map(String::trim).filter(String::isNotBlank).toSet()
        if (requested.isEmpty()) return@synchronized "没有需要中断的后台代理"

        data class Previous(
            val record: Record,
            val status: String,
            val output: String,
            val updatedAt: Long,
            val job: Job?,
        )

        val previous = mutableListOf<Previous>()
        synchronized(lock) {
            val selected = requested.map { id ->
                val found = records[id]
                    ?.takeIf { ownerId == null || it.ownerId == ownerId }
                    ?: error("后台代理不存在：" + id)
                require(found.isContinuableAgent()) {
                    "目标不是可继续后台代理：" + id
                }
                found
            }
            selected.forEach { found ->
                if (found.status == "running") {
                    previous += Previous(
                        record = found,
                        status = found.status,
                        output = found.output,
                        updatedAt = found.updatedAt,
                        job = found.job,
                    )
                    found.status = "interrupted"
                    found.output = "当前执行轮次已中断；Agent 身份、恢复状态与待处理消息已保留"
                    found.updatedAt = System.currentTimeMillis()
                }
            }
        }
        if (previous.isEmpty()) {
            return@synchronized "所选后台代理当前均没有运行中的执行轮次"
        }

        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                previous.forEach { snapshot ->
                    snapshot.record.status = snapshot.status
                    snapshot.record.output = snapshot.output
                    snapshot.record.updatedAt = snapshot.updatedAt
                }
            }
            notifyChanged()
            throw IllegalStateException("批量中断状态写入失败，所有 Agent 保持原状态", error)
        }

        previous.forEach { snapshot ->
            snapshot.job?.cancel(CancellationException("当前 Agent Activation 已被批量中断"))
        }
        notifyChanged()
        "已中断 " + previous.size + " 个后台代理当前执行轮次；身份与待处理消息已保留"
    }

    fun kill(id: String, ownerId: String? = null): String {
        var durableDormant = false
        var previousStatus = ""
        var previousOutput = ""
        var previousUpdatedAt = 0L
        var previousInbox: List<QueuedAgentInput> = emptyList()
        val recordAndJob = synchronized(lock) {
            val record = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return "后台任务不存在：$id"
            val dormantPersistent =
                !record.resumeKind.isNullOrBlank() &&
                    (
                        record.status == "interrupted" ||
                            record.isIdleContinuableAgent()
                        )
            if (record.status != "running" && !dormantPersistent) {
                return "后台任务已结束：$id [${record.status}]"
            }
            durableDormant = dormantPersistent
            previousStatus = record.status
            previousOutput = record.output
            previousUpdatedAt = record.updatedAt
            previousInbox = record.inbox.toList()
            record.status = "cancelled"
            record.output = "任务已取消"
            record.inbox.clear()
            record.updatedAt = System.currentTimeMillis()
            record to record.job
        }
        val (record, job) = recordAndJob
        if (durableDormant) {
            try {
                persistCurrentSnapshots()
            } catch (error: Exception) {
                synchronized(lock) {
                    record.status = previousStatus
                    record.output = previousOutput
                    record.inbox.clear()
                    record.inbox.addAll(previousInbox)
                    record.updatedAt = previousUpdatedAt
                }
                notifyChanged()
                throw IllegalStateException("持久代理取消状态写入失败，仍保留为可恢复状态", error)
            }
            notifyChanged()
        } else {
            job?.cancel()
            publish()
        }
        return "已请求停止后台任务：$id"
    }

    fun send(id: String, message: String, ownerId: String? = null): String {
        val clean = message.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        return sendInput(
            id = id,
            input = QueuedAgentInput(
                id = "msg-" + UUID.randomUUID().toString().replace("-", "").take(16),
                content = clean.take(MAX_INBOX_MESSAGE),
                memoryInput = clean.take(MAX_INBOX_MESSAGE),
            ),
            ownerId = ownerId,
        ).message
    }

    fun sendInput(
        id: String,
        input: QueuedAgentInput,
        ownerId: String? = null,
    ): JobMessageAdmission = synchronized(publicationLock) {
        val messageId = input.id.trim()
        require(messageId.isNotEmpty()) { "消息编号不能为空" }
        require(messageId.length <= MAX_INBOX_ID) {
            "消息编号过长：最多允许 $MAX_INBOX_ID 个字符"
        }
        val content = input.content.trim()
        require(content.isNotEmpty()) { "消息不能为空" }
        val normalized = input.copy(
            id = messageId,
            content = content.take(MAX_INBOX_MESSAGE),
            memoryInput = input.memoryInput.take(MAX_INBOX_MESSAGE),
        )
        var infos: List<JobInfo>? = null
        val admission = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = false,
                    message = "后台代理不存在：$id",
                )
            if (!found.label.startsWith(AGENT_PREFIX)) {
                return JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = false,
                    message = "目标不是后台代理：$id",
                )
            }
            val persistent = !found.resumeKind.isNullOrBlank()
            val requiresResume =
                persistent &&
                    (
                        found.status == "interrupted" ||
                            found.isIdleContinuableAgent()
                        )
            if (found.status != "running" && !requiresResume) {
                return JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = false,
                    message = "目标后台代理当前不可接收消息：$id [${found.status}]",
                )
            }

            val existing = found.inbox.firstOrNull { it.id == messageId }
            if (existing != null) {
                if (existing != normalized) {
                    return JobMessageAdmission(
                        accepted = false,
                        duplicate = true,
                        requiresResume = requiresResume,
                        message = "后台代理消息编号冲突：$messageId",
                    )
                }
                return JobMessageAdmission(
                    accepted = true,
                    duplicate = true,
                    requiresResume = requiresResume,
                    message = "消息已存在于后台代理队列：$id",
                )
            }
            if (found.inbox.size >= MAX_INBOX_MESSAGES) {
                return JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = requiresResume,
                    message = "后台代理消息队列已满：最多保留 $MAX_INBOX_MESSAGES 条待处理消息",
                )
            }

            val previousInbox = found.inbox.toList()
            val previousUpdatedAt = found.updatedAt
            found.inbox += normalized
            found.updatedAt = System.currentTimeMillis()

            if (persistent) {
                try {
                    onSnapshotsChanged(records.values.map(::snapshot))
                } catch (error: Exception) {
                    found.inbox.clear()
                    found.inbox.addAll(previousInbox)
                    found.updatedAt = previousUpdatedAt
                    throw IllegalStateException("后台代理消息持久化失败，消息未发送", error)
                }
                infos = records.values.map { it.toJobInfo() }
            }

            JobMessageAdmission(
                accepted = true,
                duplicate = false,
                requiresResume = requiresResume,
                message = if (requiresResume) {
                    "消息已持久排队，后台代理将在恢复后接收：$id"
                } else {
                    "消息已发送给后台代理：$id"
                },
            )
        }

        infos?.let(onChanged)
        if (infos == null && admission.accepted && !admission.duplicate) {
            publish()
        }
        admission
    }
    fun peekMessages(id: String): List<QueuedAgentInput> = synchronized(lock) {
        records[id]?.inbox?.toList().orEmpty()
    }

    fun acknowledgeMessages(
        id: String,
        messageIds: Set<String>,
        ownerId: String? = null,
    ) {
        if (messageIds.isEmpty()) return
        synchronized(publicationLock) {
            var persistent = false
            var infos: List<JobInfo>? = null
            synchronized(lock) {
                val found = records[id]
                    ?.takeIf { ownerId == null || it.ownerId == ownerId }
                    ?: error("后台代理不存在：$id")
                persistent = !found.resumeKind.isNullOrBlank()

                val previousInbox = found.inbox.toList()
                val previousUpdatedAt = found.updatedAt
                found.inbox.removeAll { it.id in messageIds }
                found.updatedAt = System.currentTimeMillis()

                if (persistent) {
                    try {
                        onSnapshotsChanged(records.values.map(::snapshot))
                    } catch (error: Exception) {
                        found.inbox.clear()
                        found.inbox.addAll(previousInbox)
                        found.updatedAt = previousUpdatedAt
                        throw IllegalStateException("后台代理消息确认持久化失败", error)
                    }
                    infos = records.values.map { it.toJobInfo() }
                }
            }
            if (persistent) {
                infos?.let(onChanged)
            } else {
                publish()
            }
        }
    }

    fun drainMessages(id: String): List<String> {
        val messages = peekMessages(id)
        if (messages.isEmpty()) return emptyList()
        acknowledgeMessages(id, messages.mapTo(linkedSetOf()) { it.id })
        return messages.map { it.content }
    }


    fun stopAll() {
        val jobs = markRunningJobsCancelled()
        jobs.forEach { it.cancel() }
        publish()
    }

    suspend fun stopAllAndJoin() {
        val jobs = markRunningJobsCancelled { true }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        publish()
    }

    suspend fun stopNonPersistentAndJoin() {
        val jobs = markRunningJobsCancelled { it.resumeKind.isNullOrBlank() }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        publish()
    }

    suspend fun stopOwnedNonPersistentAndJoin(ownerIds: Set<String>) {
        if (ownerIds.isEmpty()) return
        val jobs = markRunningJobsCancelled {
            it.ownerId != null && it.ownerId in ownerIds && it.resumeKind.isNullOrBlank()
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        publish()
    }

    /**
     * Cancel and forget every job owned by the supplied conversations without touching jobs from
     * other sessions. Session deletion uses this instead of the old global non-persistent stop.
     */
    suspend fun removeOwnedAndJoin(ownerIds: Set<String>) {
        if (ownerIds.isEmpty()) return
        val jobs = synchronized(lock) {
            check(ownerIds.none { it in removingOwners }) { "会话任务正在移除" }
            removingOwners.addAll(ownerIds)
            markRunningJobsCancelled { it.ownerId in ownerIds }
        }
        try {
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            synchronized(lock) {
                records.entries.removeAll { it.value.ownerId in ownerIds }
            }
            publish()
        } finally {
            synchronized(lock) { removingOwners.removeAll(ownerIds) }
        }
    }

    private fun markRunningJobsCancelled(predicate: (Record) -> Boolean = { true }): List<Job> = synchronized(lock) {
        records.values.filter { it.occupiesSlot() && predicate(it) }.onEach {
            if (it.status == "running") {
                it.status = "cancelled"
                it.output = "任务已取消"
                it.updatedAt = System.currentTimeMillis()
            }
        }.mapNotNull { it.job }
    }

    private fun allocateUniqueIdLocked(): String {
        repeat(MAX_ID_FACTORY_ATTEMPTS) {
            val candidate = idFactory()
            if (candidate.isNotBlank() && !records.containsKey(candidate)) return candidate
        }
        repeat(MAX_FALLBACK_ID_ATTEMPTS) {
            val candidate = "job-" + UUID.randomUUID().toString().replace("-", "").take(16)
            if (!records.containsKey(candidate)) return candidate
        }
        error("无法分配唯一后台任务编号")
    }

    private fun pruneRetainedLocked() {
        if (records.size < maxRetainedJobs) return
        val removable = records.values
            .filter { record ->
                !record.occupiesSlot() &&
                    !record.mustRetainForContinuation()
            }
            .map { it.id }
        for (id in removable) {
            if (records.size < maxRetainedJobs) break
            records.remove(id)
        }
    }

    private fun Record.mustRetainForContinuation(): Boolean =
        (status == "interrupted" && !resumeKind.isNullOrBlank()) ||
            isIdleContinuableAgent()

    private fun Record.toJobInfo(): JobInfo {
        val agent = label.startsWith(AGENT_PREFIX)
        val messageable =
            agent &&
                (
                    status == "running" ||
                        status == "interrupted" ||
                        isIdleContinuableAgent()
                    )
        return JobInfo(
            id = id,
            label = label,
            status = status,
            ownerId = ownerId,
            isAgent = agent,
            canMessage = messageable,
            continuable = continuable,
            pendingMessageCount = inbox.size,
            updatedAt = updatedAt,
        )
    }

    private fun snapshotRecords(ownerId: String? = null): List<JobInfo> = synchronized(lock) {
        records.values
            .filter { ownerId == null || it.ownerId == ownerId }
            .map { it.toJobInfo() }
    }

    // Old snapshots may still contain a full shell command, including credentials.
    private fun publicLabel(label: String): String =
        if (label.startsWith(AGENT_PREFIX)) label.removePrefix(AGENT_PREFIX) else "后台命令"

    private fun snapshot(record: Record): JobSnapshot = JobSnapshot(
        id = record.id,
        label = record.label,
        status = record.status,
        output = record.output,
        resumeKind = record.resumeKind,
        resumePayload = record.resumePayload,
        continuable = record.continuable,
        ownerId = record.ownerId,
        startedAt = record.startedAt,
        deadlineAt = record.deadlineAt,
        updatedAt = record.updatedAt,
        inbox = record.inbox.toList(),
    )

    private suspend fun persistTerminalState(record: Record) = withContext(NonCancellable) {
        var lastFailure: Exception? = null
        repeat(TERMINAL_PERSIST_ATTEMPTS) { attempt ->
            try {
                persistCurrentSnapshots()
                return@withContext
            } catch (error: Exception) {
                lastFailure = error
                if (attempt < TERMINAL_PERSIST_ATTEMPTS - 1) {
                    delay(TERMINAL_PERSIST_RETRY_MILLIS * (attempt + 1L))
                }
            }
        }
        synchronized(lock) {
            record.status = "failed"
            record.output = (
                record.output.takeLast(MAX_OUTPUT / 2) +
                    "\n任务终态持久化失败：" +
                    (lastFailure?.message ?: lastFailure?.javaClass?.simpleName ?: "unknown")
                ).takeLast(MAX_OUTPUT)
            record.updatedAt = System.currentTimeMillis()
        }
        notifyChanged()
        throw IllegalStateException("后台任务终态持久化失败", lastFailure)
    }

    private fun publish() = synchronized(publicationLock) {
        val infos: List<JobInfo>
        val snapshots: List<JobSnapshot>
        synchronized(lock) {
            infos = records.values.map { it.toJobInfo() }
            snapshots = records.values.map(::snapshot)
        }
        onChanged(infos)
        // A transient persistence failure must not rewrite the task's execution result. The next
        // state publication retries the full snapshot. Persistent start/resume use the strict
        // preflight path below so they never launch before recovery metadata is durable.
        runCatching { onSnapshotsChanged(snapshots) }
    }

    private fun notifyChanged() = synchronized(publicationLock) {
        onChanged(snapshotRecords())
    }

    private fun persistCurrentSnapshots() = synchronized(publicationLock) {
        onSnapshotsChanged(synchronized(lock) { records.values.map(::snapshot) })
    }

    private fun interruptedProcessDetail(snapshot: JobSnapshot): String {
        val startedAt = snapshot.startedAt
        val deadlineAt = snapshot.deadlineAt
        if (startedAt <= 0L || deadlineAt <= startedAt) {
            return "应用进程中断，后台命令未完成；请检查设备进程退出记录"
        }
        val observedAt = snapshot.updatedAt.coerceAtLeast(startedAt)
        val elapsedSeconds = ((observedAt - startedAt) / 1_000L).coerceAtLeast(0L)
        val requestedSeconds = ((deadlineAt - startedAt) / 1_000L).coerceAtLeast(1L)
        val timing = if (observedAt < deadlineAt) {
            "在请求期限前"
        } else {
            "达到或超过请求期限后"
        }
        return "应用进程${timing}中断，后台命令未完成（已运行约 ${elapsedSeconds} 秒；请求上限 ${requestedSeconds} 秒）；请检查设备进程退出记录"
    }

    private companion object {
        const val AGENT_PREFIX = "子代理："
        const val CONTINUABLE_AGENT_RESUME_KIND = "subagent_readonly"
        const val MAX_JOB_ID = 96
        const val MAX_LABEL = 160
        const val MAX_OUTPUT = 65_536
        const val MAX_INBOX_ID = 64
        const val MAX_INBOX_MESSAGE = 4_000
        const val MAX_INBOX_MESSAGES = AgentInputQueue.DEFAULT_CAPACITY
        const val MAX_RESUME_KIND = 64
        const val MAX_RESUME_PAYLOAD = 64_000
        const val DEFAULT_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_MAX_RETAINED_JOBS = 64
        const val MAX_EXPECTED_DURATION_MILLIS = 24L * 60L * 60L * 1000L
        const val RUNNING_HEARTBEAT_MILLIS = 30_000L
        const val TERMINAL_PERSIST_ATTEMPTS = 3
        const val TERMINAL_PERSIST_RETRY_MILLIS = 100L
        const val MAX_ID_FACTORY_ATTEMPTS = 16
        const val MAX_FALLBACK_ID_ATTEMPTS = 16
    }
}
