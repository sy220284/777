package com.labteto.dshmobile.local

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** In-process background jobs mirroring the official shared job controller. */
class LocalJobManager(
    private val scope: CoroutineScope,
    private val onChanged: (List<LocalJobInfo>) -> Unit,
) {
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
        val record = Record(UUID.randomUUID().toString().take(8), label.take(160))
        synchronized(lock) { records[record.id] = record }
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

    fun list(): String {
        val snapshot = synchronized(lock) { records.values.toList() }
        return if (snapshot.isEmpty()) {
            "没有后台任务"
        } else {
            snapshot.joinToString("\n") { "${it.id} [${it.status}] ${it.label}" }
        }
    }

    fun listAgents(): String {
        val snapshot = synchronized(lock) { records.values.filter { it.label.startsWith("子代理：") } }
        return if (snapshot.isEmpty()) {
            "没有后台代理"
        } else {
            snapshot.joinToString("\n") { "${it.id} [${it.status}] ${it.label.removePrefix("子代理：")}" }
        }
    }

    fun output(id: String): String {
        val record = synchronized(lock) { records[id] } ?: return "后台任务不存在：$id"
        return "${record.id} [${record.status}] ${record.label}\n${record.output.ifBlank { "暂无输出" }}"
    }

    fun kill(id: String): String {
        val record = synchronized(lock) { records[id] } ?: return "后台任务不存在：$id"
        if (record.status != "running") return "后台任务已结束：$id [${record.status}]"
        record.job?.cancel()
        return "已请求停止后台任务：$id"
    }

    fun send(id: String, message: String): String {
        val clean = message.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        synchronized(lock) {
            val record = records[id] ?: return "后台代理不存在：$id"
            if (record.status != "running" || !record.label.startsWith("子代理：")) {
                return "目标不是正在运行的后台代理：$id"
            }
            record.inbox += clean.take(4_000)
        }
        return "消息已发送给后台代理：$id"
    }

    fun drainMessages(id: String): List<String> = synchronized(lock) {
        val record = records[id] ?: return@synchronized emptyList()
        record.inbox.toList().also { record.inbox.clear() }
    }

    fun stopAll() {
        synchronized(lock) { records.values.mapNotNull { it.job } }.forEach { it.cancel() }
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            records.values.map { LocalJobInfo(it.id, it.label, it.status) }
        }
        onChanged(snapshot)
    }

    private companion object {
        const val MAX_OUTPUT = 65_536
    }
}
