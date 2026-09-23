package com.labteto.dshmobile.harness.agent

data class QueuedAgentInput(
    val content: String,
    val memoryInput: String = content,
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

    fun size(): Int = synchronized(lock) { items.size }

    companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
