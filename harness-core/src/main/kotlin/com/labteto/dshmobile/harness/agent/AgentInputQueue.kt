package com.labteto.dshmobile.harness.agent

import kotlinx.serialization.json.JsonObject

data class QueuedAgentInput(
    val content: String,
    val memoryInput: String = content,
    val modelMessage: JsonObject? = null,
    val id: String = "",
)

class AgentInputQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity in 1..128) { "输入队列容量必须在 1..128 之间" }
    }

    private val lock = Any()
    private val items = ArrayDeque<QueuedAgentInput>()

    /** Keep a newly queued input invisible to consumers until its durable admission commits. */
    fun offer(input: QueuedAgentInput, commit: () -> Unit = {}): Boolean = synchronized(lock) {
        if (items.size >= capacity) return@synchronized false
        if (input.id.isNotBlank() && items.any { it.id == input.id }) {
            error("输入队列存在重复编号：${input.id}")
        }
        items.addLast(input)
        try {
            commit()
            true
        } catch (error: Throwable) {
            items.remove(input)
            throw error
        }
    }

    fun drain(): List<QueuedAgentInput> = synchronized(lock) {
        if (items.isEmpty()) return@synchronized emptyList()
        buildList(items.size) {
            while (items.isNotEmpty()) add(items.removeFirst())
        }
    }

    /** Batch consumption commits atomically; a failed authority write keeps the original queue. */
    fun drainCommitted(commit: (List<QueuedAgentInput>) -> Unit): List<QueuedAgentInput> =
        synchronized(lock) {
            val drained = items.toList()
            if (drained.isEmpty()) return@synchronized emptyList()
            commit(drained)
            items.clear()
            drained
        }

    /** Move the existing inbox to a newly created, unpublished run owner. */
    fun transferTo(target: AgentInputQueue) {
        require(target !== this) { "输入队列不能移交给自身" }
        drainCommitted { pending ->
            check(target.size() == 0) { "输入队列只能移交给空的运行句柄" }
            target.restore(pending)
        }
    }

    fun poll(): QueuedAgentInput? = synchronized(lock) {
        if (items.isEmpty()) null else items.removeFirst()
    }

    /** Commit consumption while the queue is locked; failed writes preserve the original order. */
    fun pollCommitted(commit: (QueuedAgentInput, List<QueuedAgentInput>) -> Unit): QueuedAgentInput? =
        synchronized(lock) {
            if (items.isEmpty()) return@synchronized null
            val input = items.removeFirst()
            try {
                commit(input, items.toList())
                input
            } catch (error: Throwable) {
                items.addFirst(input)
                throw error
            }
        }

    fun clear(): Int = synchronized(lock) {
        val count = items.size
        items.clear()
        count
    }

    fun snapshot(): List<QueuedAgentInput> = synchronized(lock) { items.toList() }

    fun restore(restored: List<QueuedAgentInput>) = synchronized(lock) {
        require(restored.size <= capacity) {
            "恢复的输入队列超过容量：${restored.size}/$capacity"
        }
        val ids = restored.map(QueuedAgentInput::id).filter(String::isNotBlank)
        require(ids.distinct().size == ids.size) { "恢复的输入队列包含重复编号" }
        items.clear()
        items.addAll(restored)
    }

    fun size(): Int = synchronized(lock) { items.size }

    companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
