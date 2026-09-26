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

    fun offer(input: QueuedAgentInput): Boolean = synchronized(lock) {
        if (items.size >= capacity) return@synchronized false
        if (input.id.isNotBlank() && items.any { it.id == input.id }) {
            error("输入队列存在重复编号：${input.id}")
        }
        items.addLast(input)
        true
    }

    fun drain(): List<QueuedAgentInput> = synchronized(lock) {
        if (items.isEmpty()) return@synchronized emptyList()
        buildList(items.size) {
            while (items.isNotEmpty()) add(items.removeFirst())
        }
    }

    fun poll(): QueuedAgentInput? = synchronized(lock) {
        if (items.isEmpty()) null else items.removeFirst()
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
