package com.labteto.dshmobile.harness.jobs

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

data class JobInfo(
    val id: String,
    val label: String,
    val status: String,
    val ownerId: String? = null,
)

data class JobInboxMessage(
    val id: String,
    val content: String,
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
    val inbox: List<JobInboxMessage> = emptyList(),
    val continuationState: String? = null,
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
        val inbox: MutableList<JobInboxMessage> = mutableListOf(),
        var continuationState: String? = null,
        val resumeKind: String? = null,
        val resumePayload: String? = null,
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

    init {
        synchronized(lock) {
            initialSnapshots.takeLast(maxRetainedJobs).forEach { snapshot ->
                val interrupted = snapshot.status == "running"
                records[snapshot.id] = Record(
                    id = snapshot.id,
                    label = snapshot.label.take(MAX_LABEL),
                    status = if (interrupted) "interrupted" else snapshot.status,
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
                    ownerId = snapshot.ownerId,
                    startedAt = snapshot.startedAt,
                    deadlineAt = snapshot.deadlineAt,
                    updatedAt = snapshot.updatedAt,
                    inbox = snapshot.inbox
                        .takeLast(MAX_INBOX_MESSAGES)
                        .map { message ->
                            JobInboxMessage(
                                id = message.id.take(MAX_INBOX_ID),
                                content = message.content.take(MAX_INBOX_MESSAGE),
                            )
                        }
                        .filter { it.id.isNotBlank() && it.content.isNotBlank() }
                        .toMutableList(),
                    continuationState = snapshot.continuationState
                        ?.takeIf(String::isNotBlank)
                        ?.take(MAX_CONTINUATION_STATE_CHARS),
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
    )

    fun startPersistent(
        label: String,
        resumeKind: String,
        resumePayload: String,
        ownerId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        require(resumeKind.isNotBlank()) { "持久任务恢复类型不能为空" }
        return startInternal(
            label = label,
            resumeKind = resumeKind.take(MAX_RESUME_KIND),
            resumePayload = resumePayload.take(MAX_RESUME_PAYLOAD),
            ownerId = ownerId,
            expectedDurationMillis = null,
            block = block,
        )
    }

    fun resumePersistent(
        id: String,
        ownerId: String? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        var previousOutput = ""
        var previousUpdatedAt = 0L
        var previousOwnerId: String? = null
        val record = synchronized(lock) {
            val found = records[id] ?: return "后台任务不存在：$id"
            if ((ownerId ?: found.ownerId) in removingOwners) return "会话正在移除，无法恢复后台任务"
            if (found.resumeKind.isNullOrBlank()) return "后台任务不可恢复：$id"
            if (found.status != "interrupted") return "后台任务无需恢复：$id [${found.status}]"
            val running = records.values.count { it.occupiesSlot() }
            if (running >= maxConcurrentJobs) {
                return "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务"
            }
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
            previousOwnerId = found.ownerId
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
                record.status = "interrupted"
                record.output = previousOutput
                record.ownerId = previousOwnerId
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

    fun snapshots(): List<JobSnapshot> = synchronized(lock) { records.values.map(::snapshot) }

    fun availableSlots(): Int = synchronized(lock) {
        (maxConcurrentJobs - records.values.count { it.occupiesSlot() }).coerceAtLeast(0)
    }

    fun failInterrupted(id: String, detail: String): String {
        synchronized(lock) {
            val record = records[id] ?: return "后台任务不存在：$id"
            if (record.status != "interrupted") return "后台任务无需标记失败：$id [${record.status}]"
            record.status = "failed"
            record.output = detail.takeLast(MAX_OUTPUT)
            record.continuationState = null
            record.updatedAt = System.currentTimeMillis()
        }
        publish()
        return "后台任务恢复失败：$id"
    }

    private fun startInternal(
        label: String,
        resumeKind: String?,
        resumePayload: String?,
        ownerId: String?,
        expectedDurationMillis: Long? = null,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        val record = synchronized(lock) {
            if (ownerId in removingOwners) return "会话正在移除，无法启动后台任务"
            pruneRetainedLocked()
            val running = records.values.count { it.occupiesSlot() }
            if (running >= maxConcurrentJobs) {
                return "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务"
            }
            val id = allocateUniqueIdLocked()
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
        return "后台任务已启动：${record.id}"
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
                    record.status = "completed"
                    record.continuationState = null
                    record.updatedAt = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    record.status = "cancelled"
                    record.output = "任务已取消"
                    record.continuationState = null
                    record.updatedAt = System.currentTimeMillis()
                }
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (record.status != "running") return@synchronized
                    record.status = "failed"
                    record.output = "任务失败：${error.message ?: error::class.java.simpleName}"
                    record.continuationState = null
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

    fun kill(id: String, ownerId: String? = null): String {
        val job = synchronized(lock) {
            val record = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return "后台任务不存在：$id"
            if (record.status != "running") return "后台任务已结束：$id [${record.status}]"
            record.status = "cancelled"
            record.output = "任务已取消"
            record.continuationState = null
            record.updatedAt = System.currentTimeMillis()
            record.job
        }
        job?.cancel()
        publish()
        return "已请求停止后台任务：$id"
    }

    fun send(id: String, message: String, ownerId: String? = null): String {
        val clean = message.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        var interrupted = false
        var previousInbox: List<JobInboxMessage> = emptyList()
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: return "后台代理不存在：$id"
            if (!found.label.startsWith(AGENT_PREFIX)) {
                return "目标不是后台代理：$id"
            }
            interrupted =
                found.status == "interrupted" && !found.resumeKind.isNullOrBlank()
            if (found.status != "running" && !interrupted) {
                return "目标后台代理当前不可接收消息：$id [${found.status}]"
            }
            previousInbox = found.inbox.toList()
            previousUpdatedAt = found.updatedAt
            found.inbox += JobInboxMessage(
                id = "msg-" + UUID.randomUUID().toString().replace("-", "").take(16),
                content = clean.take(MAX_INBOX_MESSAGE),
            )
            while (found.inbox.size > MAX_INBOX_MESSAGES) {
                found.inbox.removeAt(0)
            }
            found.updatedAt = System.currentTimeMillis()
            found
        }
        if (!record.resumeKind.isNullOrBlank()) {
            try {
                persistCurrentSnapshots()
            } catch (error: Exception) {
                synchronized(lock) {
                    record.inbox.clear()
                    record.inbox.addAll(previousInbox)
                    record.updatedAt = previousUpdatedAt
                }
                notifyChanged()
                throw IllegalStateException("后台代理消息持久化失败，消息未发送", error)
            }
            notifyChanged()
        } else {
            publish()
        }
        return if (interrupted) {
            "消息已持久排队，后台代理将在恢复后接收：$id"
        } else {
            "消息已发送给后台代理：$id"
        }
    }

    fun peekMessages(id: String): List<JobInboxMessage> = synchronized(lock) {
        records[id]?.inbox?.toList().orEmpty()
    }

    fun updateContinuationState(
        id: String,
        state: String,
        ownerId: String? = null,
    ) {
        require(state.isNotBlank()) { "后台代理续跑状态不能为空" }
        require(state.length <= MAX_CONTINUATION_STATE_CHARS) {
            "后台代理续跑状态超过上限"
        }
        var previousState: String? = null
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: error("后台代理不存在：$id")
            check(!found.resumeKind.isNullOrBlank()) { "后台代理不是持久任务：$id" }
            check(found.status == "running") { "后台代理当前不可更新续跑状态：$id [${found.status}]" }
            previousState = found.continuationState
            previousUpdatedAt = found.updatedAt
            found.continuationState = state
            found.updatedAt = System.currentTimeMillis()
            found
        }
        try {
            persistCurrentSnapshots()
        } catch (error: Exception) {
            synchronized(lock) {
                record.continuationState = previousState
                record.updatedAt = previousUpdatedAt
            }
            notifyChanged()
            throw IllegalStateException("后台代理续跑状态持久化失败", error)
        }
        notifyChanged()
    }

    fun acknowledgeMessages(
        id: String,
        messageIds: Set<String>,
        ownerId: String? = null,
    ) {
        if (messageIds.isEmpty()) return
        var previousInbox: List<JobInboxMessage> = emptyList()
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?: error("后台代理不存在：$id")
            previousInbox = found.inbox.toList()
            previousUpdatedAt = found.updatedAt
            found.inbox.removeAll { it.id in messageIds }
            found.updatedAt = System.currentTimeMillis()
            found
        }
        if (!record.resumeKind.isNullOrBlank()) {
            try {
                persistCurrentSnapshots()
            } catch (error: Exception) {
                synchronized(lock) {
                    record.inbox.clear()
                    record.inbox.addAll(previousInbox)
                    record.updatedAt = previousUpdatedAt
                }
                notifyChanged()
                throw IllegalStateException("后台代理消息确认持久化失败", error)
            }
            notifyChanged()
        } else {
            publish()
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
            .filter { !it.occupiesSlot() }
            .map { it.id }
        for (id in removable) {
            if (records.size < maxRetainedJobs) break
            records.remove(id)
        }
    }

    private fun snapshotRecords(ownerId: String? = null): List<JobInfo> = synchronized(lock) {
        records.values
            .filter { ownerId == null || it.ownerId == ownerId }
            .map { JobInfo(it.id, it.label, it.status, it.ownerId) }
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
        ownerId = record.ownerId,
        startedAt = record.startedAt,
        deadlineAt = record.deadlineAt,
        updatedAt = record.updatedAt,
        inbox = record.inbox.toList(),
        continuationState = record.continuationState,
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
            infos = records.values.map { JobInfo(it.id, it.label, it.status, it.ownerId) }
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
        const val MAX_LABEL = 160
        const val MAX_OUTPUT = 65_536
        const val MAX_INBOX_ID = 64
        const val MAX_INBOX_MESSAGE = 4_000
        const val MAX_INBOX_MESSAGES = 32
        const val MAX_CONTINUATION_STATE_CHARS = 4_000_000
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
