package com.labteto.dshmobile.observability

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Single scheduled drain, batched writes, no producer-thread disk I/O.
 * An in-flight copy stays visible to export until the writer has completed.
 */
internal class DiagnosticWriteQueue<T>(
    private val executor: Executor,
    private val writer: (List<T>) -> Unit,
    private val important: (T) -> Boolean,
    private val capacity: Int = 4_096,
    private val batchSize: Int = 128,
) {
    init {
        require(capacity > 0 && batchSize > 0)
    }

    internal data class Snapshot<T>(
        val outstanding: List<T>,
        val overflow: Long,
        val writeError: String?,
    )

    private val gate = Any()
    private val pending = ArrayDeque<T>()
    private var inFlight: List<T> = emptyList()
    private var draining = false
    private var overflow = 0L
    private var ordinaryCount = 0
    private var writeError: String? = null

    fun offer(entry: T) {
        val priority = important(entry)
        val start = synchronized(gate) {
            if (pending.size >= capacity) {
                if (ordinaryCount > 0) {
                    val expendable = pending.iterator()
                    while (expendable.hasNext()) {
                        if (!important(expendable.next())) {
                            expendable.remove()
                            ordinaryCount--
                            break
                        }
                    }
                    pending.addLast(entry)
                    if (!priority) ordinaryCount++
                } else if (priority) {
                    pending.removeFirst()
                    pending.addLast(entry)
                }
                // When full of failures, an incoming ordinary message is discarded.
                overflow++
            } else {
                pending.addLast(entry)
                if (!priority) ordinaryCount++
            }
            if (draining) false else {
                draining = true
                true
            }
        }
        if (start) {
            try {
                executor.execute { drain() }
            } catch (error: Exception) {
                synchronized(gate) {
                    draining = false
                    writeError = error.javaClass.simpleName
                }
            }
        }
    }

    fun snapshot(): Snapshot<T> = synchronized(gate) {
        Snapshot(inFlight + pending.toList(), overflow, writeError)
    }

    /** Only used by the test/maintenance clear path. The disk lock must be held by the caller. */
    fun clearPending() = synchronized(gate) {
        pending.clear()
        ordinaryCount = 0
        overflow = 0L
        writeError = null
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(gate) {
                if (pending.isEmpty()) {
                    draining = false
                    return
                }
                buildList {
                    repeat(minOf(batchSize, pending.size)) {
                        val item = pending.removeFirst()
                        if (!important(item)) ordinaryCount--
                        add(item)
                    }
                }.also { inFlight = it }
            }
            try {
                writer(batch)
            } catch (error: Exception) {
                synchronized(gate) {
                    batch.asReversed().forEach { item ->
                        pending.addFirst(item)
                        if (!important(item)) ordinaryCount++
                    }
                    inFlight = emptyList()
                    writeError = error.javaClass.simpleName
                    draining = false
                }
                return
            }
            synchronized(gate) {
                inFlight = emptyList()
                writeError = null
            }
        }
    }
}
