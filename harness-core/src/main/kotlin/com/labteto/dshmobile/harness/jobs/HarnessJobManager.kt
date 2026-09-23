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

/** Process-agnostic shared job controller used by the Android runtime adapter. */
class HarnessJobManager(
    private val scope: CoroutineScope,
    private val onChanged: (List<JobInfo>) -> Unit,
    private val idFactory: () -> String = {
        "job-" + UUID.randomUUID().toString().replace("-", "").take(16)
    },
    private val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    private val maxRetainedJobs: Int = DEFAULT_MAX_RETAINED_JOBS,
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
    )

    private val lock = Any()
    private val records = linkedMapOf<String, Record>()

    fun start(label: String, block: suspend (String, (String) -> Unit) -> String): String {
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
            Record(id, label.take(MAX_LABEL)).also { records[id] = it }
        }
        record.job = scope.launch {
            try {
                val report: (String) -> Unit = { output ->
                    synchronized(lock) { record.output = output.takeLast(MAX_OUTPUT) }
                    publish()
                }
                val result = block(record.id, report).takeLast(MAX_OUTPUT)
                synchronized(lock) {
                    record.output = result
                    record.status = "completed"
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    record.status = "cancelled"
                    record.output = "任务已取消"
                }
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    record.status = "failed"
                    record.output = "任务失败：${error.message ?: error::class.java.simpleName}"
                }
            } finally {
                publish()
            }
        }
        publish()
        return "后台任务已启动：${record.id}"
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
            record.job
        }
        job?.cancel()
        publish()
        return "已停止后台任务：$id"
    }

    fun send(id: String, message: String): String {
        val clean = message.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        synchronized(lock) {
            val record = records[id] ?: return "后台代理不存在：$id"
            if (record.status != "running" || !record.label.startsWith(AGENT_PREFIX)) {
                return "目标不是正在运行的后台代理：$id"
            }
            record.inbox += clean.take(MAX_INBOX_MESSAGE)
            while (record.inbox.size > MAX_INBOX_MESSAGES) {
                record.inbox.removeAt(0)
            }
        }
        return "消息已发送给后台代理：$id"
    }

    fun drainMessages(id: String): List<String> = synchronized(lock) {
        val record = records[id] ?: return@synchronized emptyList()
        record.inbox.toList().also { record.inbox.clear() }
    }

    fun stopAll() {
        val jobs = markRunningJobsCancelled()
        jobs.forEach { it.cancel() }
        publish()
    }

    suspend fun stopAllAndJoin() {
        val jobs = markRunningJobsCancelled()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        publish()
    }

    private fun markRunningJobsCancelled(): List<Job> = synchronized(lock) {
        records.values.filter { it.status == "running" }.onEach {
            it.status = "cancelled"
            it.output = "任务已取消"
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

    private fun publish() = onChanged(snapshotRecords())

    private companion object {
        const val AGENT_PREFIX = "子代理："
        const val MAX_LABEL = 160
        const val MAX_OUTPUT = 65_536
        const val MAX_INBOX_MESSAGE = 4_000
        const val MAX_INBOX_MESSAGES = 32
        const val DEFAULT_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_MAX_RETAINED_JOBS = 64
    }
}
