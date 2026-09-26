package com.labteto.dshmobile.harness.jobs

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class JobInfo(
    val id: String,
    val label: String,
    val status: String,
)

data class JobSnapshot(
    val id: String,
    val label: String,
    val status: String,
    val output: String = "",
    val resumeKind: String? = null,
    val resumePayload: String? = null,
    val inbox: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
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
        val inbox: MutableList<String> = mutableListOf(),
        val resumeKind: String? = null,
        val resumePayload: String? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Any()
    private val records = linkedMapOf<String, Record>()

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
                            if (previous.isBlank()) "进程中断，等待安全恢复"
                            else "${previous}\n进程中断，等待安全恢复".takeLast(MAX_OUTPUT)
                        }
                    } else {
                        snapshot.output.takeLast(MAX_OUTPUT)
                    },
                    inbox = snapshot.inbox
                        .takeLast(MAX_INBOX_MESSAGES)
                        .map { it.take(MAX_INBOX_MESSAGE) }
                        .toMutableList(),
                    resumeKind = snapshot.resumeKind,
                    resumePayload = snapshot.resumePayload,
                    updatedAt = snapshot.updatedAt,
                )
            }
        }
        publish()
    }

    fun start(label: String, block: suspend (String, (String) -> Unit) -> String): String =
        startInternal(label, resumeKind = null, resumePayload = null, block = block)

    fun startPersistent(
        label: String,
        resumeKind: String,
        resumePayload: String,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        require(resumeKind.isNotBlank()) { "持久任务恢复类型不能为空" }
        return startInternal(
            label = label,
            resumeKind = resumeKind.take(MAX_RESUME_KIND),
            resumePayload = resumePayload.take(MAX_RESUME_PAYLOAD),
            block = block,
        )
    }

    fun resumePersistent(
        id: String,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        var previousOutput = ""
        var previousUpdatedAt = 0L
        val record = synchronized(lock) {
            val found = records[id] ?: return "后台任务不存在：$id"
            if (found.resumeKind.isNullOrBlank()) return "后台任务不可恢复：$id"
            if (found.status != "interrupted") return "后台任务无需恢复：$id [${found.status}]"
            val running = records.values.count { it.status == "running" }
            if (running >= maxConcurrentJobs) {
                return "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务"
            }
            previousOutput = found.output
            previousUpdatedAt = found.updatedAt
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
        (maxConcurrentJobs - records.values.count { it.status == "running" }).coerceAtLeast(0)
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

    private fun startInternal(
        label: String,
        resumeKind: String?,
        resumePayload: String?,
        block: suspend (String, (String) -> Unit) -> String,
    ): String {
        val record = synchronized(lock) {
            pruneRetainedLocked()
            val running = records.values.count { it.status == "running" }
            if (running >= maxConcurrentJobs) {
                return "后台任务并发已满：最多同时运行 $maxConcurrentJobs 个任务"
            }
            var id: String
            do {
                id = idFactory()
            } while (records.containsKey(id))
            Record(
                id = id,
                label = label.take(MAX_LABEL),
                resumeKind = resumeKind,
                resumePayload = resumePayload,
            ).also { records[id] = it }
        }
        if (!resumeKind.isNullOrBlank()) {
            try {
                persistCurrentSnapshots()
            } catch (error: Exception) {
                synchronized(lock) { records.remove(record.id) }
                notifyChanged()
                throw IllegalStateException("持久任务元数据写入失败，任务未启动", error)
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
        record.job = scope.launch {
            try {
                val report: (String) -> Unit = { output ->
                    synchronized(lock) {
                        record.output = output.takeLast(MAX_OUTPUT)
                        record.updatedAt = System.currentTimeMillis()
                    }
                    publish()
                }
                val result = block(record.id, report).takeLast(MAX_OUTPUT)
                synchronized(lock) {
                    record.output = result
                    record.status = "completed"
                    record.updatedAt = System.currentTimeMillis()
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    record.status = "cancelled"
                    record.output = "任务已取消"
                    record.updatedAt = System.currentTimeMillis()
                }
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    record.status = "failed"
                    record.output = "任务失败：${error.message ?: error::class.java.simpleName}"
                    record.updatedAt = System.currentTimeMillis()
                }
            } finally {
                publish()
            }
        }
    }

    fun list(): String = snapshotRecords().let { snapshot ->
        if (snapshot.isEmpty()) "没有后台任务"
        else snapshot.joinToString("\n") { "${it.id} [${it.status}] ${it.label}" }
    }

    fun listAgents(): String = synchronized(lock) {
        records.values.filter { it.label.startsWith(AGENT_PREFIX) }.map {
            "${it.id} [${it.status}] ${it.label.removePrefix(AGENT_PREFIX)}"
        }
    }.let { if (it.isEmpty()) "没有后台代理" else it.joinToString("\n") }

    fun output(id: String): String {
        return synchronized(lock) {
            val record = records[id] ?: return "后台任务不存在：$id"
            "${record.id} [${record.status}] ${record.label}\n${record.output.ifBlank { "暂无输出" }}"
        }
    }

    fun kill(id: String): String {
        val job = synchronized(lock) {
            val record = records[id] ?: return "后台任务不存在：$id"
            if (record.status != "running") return "后台任务已结束：$id [${record.status}]"
            record.status = "cancelled"
            record.output = "任务已取消"
            record.updatedAt = System.currentTimeMillis()
            record.job
        }
        job?.cancel()
        publish()
        return "已停止后台任务：$id"
    }

    fun send(id: String, message: String): String {
        val clean = message.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        var coldResume = false
        synchronized(lock) {
            val record = records[id] ?: return "后台代理不存在：$id"
            if (!record.label.startsWith(AGENT_PREFIX)) {
                return "目标不是后台代理：$id"
            }
            if (record.status != "running") {
                if (record.resumeKind != CONTINUABLE_SUBAGENT_KIND) {
                    return "后台代理不可续接：$id [${record.status}]"
                }
                coldResume = true
                record.status = "interrupted"
                record.output = record.output.takeLast(MAX_OUTPUT)
            }
            record.inbox += clean.take(MAX_INBOX_MESSAGE)
            while (record.inbox.size > MAX_INBOX_MESSAGES) {
                record.inbox.removeAt(0)
            }
            record.updatedAt = System.currentTimeMillis()
        }
        // A continuation message is execution authority. Persist it before reporting success so a
        // process death after send_message cannot lose a message the parent was told had been sent.
        persistCurrentSnapshots()
        notifyChanged()
        return if (coldResume) {
            "消息已发送，后台代理将从持久检查点冷恢复：$id"
        } else {
            "消息已发送给后台代理：$id"
        }
    }

    fun drainMessages(id: String): List<String> {
        val drained = synchronized(lock) {
            val record = records[id] ?: return emptyList()
            record.inbox.toList().also {
                if (it.isNotEmpty()) {
                    record.inbox.clear()
                    record.updatedAt = System.currentTimeMillis()
                }
            }
        }
        if (drained.isNotEmpty()) publish()
        return drained
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

    private fun markRunningJobsCancelled(predicate: (Record) -> Boolean = { true }): List<Job> = synchronized(lock) {
        records.values.filter { it.status == "running" && predicate(it) }.onEach {
            it.status = "cancelled"
            it.output = "任务已取消"
            it.updatedAt = System.currentTimeMillis()
        }.mapNotNull { it.job }
    }

    private fun pruneRetainedLocked() {
        if (records.size < maxRetainedJobs) return
        val removable = records.values
            .filter { it.status != "running" }
            .map { it.id }
        for (id in removable) {
            if (records.size < maxRetainedJobs) break
            records.remove(id)
        }
    }

    private fun snapshotRecords(): List<JobInfo> = synchronized(lock) {
        records.values.map { JobInfo(it.id, it.label, it.status) }
    }

    private fun snapshot(record: Record): JobSnapshot = JobSnapshot(
        id = record.id,
        label = record.label,
        status = record.status,
        output = record.output,
        resumeKind = record.resumeKind,
        resumePayload = record.resumePayload,
        inbox = record.inbox.toList(),
        updatedAt = record.updatedAt,
    )

    private fun publish() {
        val infos: List<JobInfo>
        val snapshots: List<JobSnapshot>
        synchronized(lock) {
            infos = records.values.map { JobInfo(it.id, it.label, it.status) }
            snapshots = records.values.map(::snapshot)
        }
        onChanged(infos)
        // A transient persistence failure must not rewrite the task's execution result. The next
        // state publication retries the full snapshot. Persistent start/resume use the strict
        // preflight path below so they never launch before recovery metadata is durable.
        runCatching { onSnapshotsChanged(snapshots) }
    }

    private fun notifyChanged() {
        onChanged(snapshotRecords())
    }

    private fun persistCurrentSnapshots() {
        onSnapshotsChanged(synchronized(lock) { records.values.map(::snapshot) })
    }

    private companion object {
        const val AGENT_PREFIX = "子代理："
        const val CONTINUABLE_SUBAGENT_KIND = "subagent_readonly"
        const val MAX_LABEL = 160
        const val MAX_OUTPUT = 65_536
        const val MAX_INBOX_MESSAGE = 4_000
        const val MAX_INBOX_MESSAGES = 32
        const val MAX_RESUME_KIND = 64
        const val MAX_RESUME_PAYLOAD = 64_000
        const val DEFAULT_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_MAX_RETAINED_JOBS = 64
    }
}
