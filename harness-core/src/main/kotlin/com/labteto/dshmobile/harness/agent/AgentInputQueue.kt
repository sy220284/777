package com.labteto.dshmobile.harness.agent

import kotlinx.serialization.json.JsonObject

/** Execution origin is part of the durable queue contract, not encoded in ID prefixes. */
enum class QueuedAgentInputKind { USER, WORK_CONTINUATION, AGENT_MESSAGE }

/** Stable resume coordinates are persisted with the queue, not inferred from display IDs. */
data class AgentContinuationRef(
    val sourceRunId: String,
    val checkpointSequence: Long,
    val failureCode: String,
    val index: Int,
    val sourceMessageId: String?,
    /** Present only for Goal progress continuation: binds a resume to one immutable goal contract. */
    val goalId: String? = null,
    val goalFingerprint: String? = null,
    /** Current Goal rounds use a durable revision; fingerprint is retained for old queue records. */
    val goalRevision: Long? = null,
) {
    init {
        require(sourceRunId.isNotBlank() && checkpointSequence >= 0 && index > 0)
    }
}

data class QueuedAgentInput(
    val content: String,
    val memoryInput: String = content,
    val modelMessage: JsonObject? = null,
    val id: String = "",
    val kind: QueuedAgentInputKind = QueuedAgentInputKind.USER,
    val continuation: AgentContinuationRef? = null,
    val agentSource: JsonObject? = null,
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
        if (input.kind == QueuedAgentInputKind.USER && items.any { it.kind == QueuedAgentInputKind.AGENT_MESSAGE }) {
            val insertion = items.indexOfFirst { it.kind == QueuedAgentInputKind.AGENT_MESSAGE }
            items.add(insertion, input)
        } else items.addLast(input)
        try {
            commit()
            true
        } catch (error: Throwable) {
            items.remove(input)
            throw error
        }
    }

    /**
     * Internal autonomous follow-ups are opportunistic. A user input or any other
     * already-admitted command always wins: never append a background continuation
     * behind a newer request. Admission and durability share the same queue lock.
     */
    fun offerIfEmpty(input: QueuedAgentInput, commit: () -> Unit = {}): Boolean =
        synchronized(lock) {
            if (items.isNotEmpty()) false else offer(input, commit)
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
