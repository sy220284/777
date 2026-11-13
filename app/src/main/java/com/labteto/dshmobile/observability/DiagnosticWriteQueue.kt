package com.labteto.dshmobile.observability

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * A single background consumer. A batch remains outstanding until its write succeeds.
 * The capacity includes pending AND in-flight entries, including entries waiting for retry.
 * Producers never perform disk I/O or wait for a failed writer.
 */
internal class DiagnosticWriteQueue<T>(
    private val executor: Executor,
    private val writer: (List<T>) -> Unit,
    private val important: (T) -> Boolean,
    private val capacity: Int = 4_096,
    private val batchSize: Int = 128,
    private val scheduleRetry: ((Runnable) -> Unit)? = null,
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
            // Entries being written remain reserved: neither new offers nor retries can
            // expand the outstanding set or evict an in-flight record.
            if (pending.size + inFlight.size >= capacity) {
                if (ordinaryCount > 0) {
                    val it = pending.iterator()
                    while (it.hasNext()) {
                        if (!important(it.next())) {
                            it.remove()
                            ordinaryCount--
                            break
                        }
                    }
                    pending.addLast(entry)
                    if (!priority) ordinaryCount++
                } else if (priority && pending.isNotEmpty()) {
                    // Keep the oldest failure (often the root cause) and newest failures.
                    val it = pending.iterator()
                    if (inFlight.isEmpty() && pending.size > 1) it.next()
                    it.next()
                    it.remove()
                    pending.addLast(entry)
                }
                // The evicted entry OR the rejected new entry is counted as lost.
                overflow++
            } else {
                pending.addLast(entry)
                if (!priority) ordinaryCount++
            }
            if (draining || (pending.isEmpty() && inFlight.isEmpty())) false else {
                draining = true
                true
            }
        }
        if (start) dispatch()
    }

    fun snapshot(): Snapshot<T> = synchronized(gate) {
        Snapshot(inFlight + pending.toList(), overflow, writeError)
    }

    /** The caller must hold the persistent storage lock before clearing durable state. */
    fun clearPending() = synchronized(gate) {
        pending.clear()
        ordinaryCount = 0
        overflow = 0L
        writeError = null
        // An active write is protected until it completes; the caller uses generation
        // invalidation to prevent the old batch from reaching storage after a clear.
    }

    private fun dispatch() {
        try {
            executor.execute { drain() }
        } catch (error: Exception) {
            synchronized(gate) {
                draining = false
                writeError = error.javaClass.simpleName
            }
        }
    }

    private fun drain() {
        while (true) {
            val batch = synchronized(gate) {
                if (inFlight.isNotEmpty()) {
                    inFlight
                } else {
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
            }
            try {
                writer(batch)
            } catch (error: Exception) {
                synchronized(gate) {
                    // Do not reinsert: the batch retained its capacity reservation
                    // throughout the write, so concurrent offers cannot overfill it.
                    writeError = error.javaClass.simpleName
                    draining = scheduleRetry != null
                }
                if (scheduleRetry != null) {
                    try {
                        scheduleRetry.invoke(Runnable { drain() })
                    } catch (scheduleError: Exception) {
                        synchronized(gate) {
                            draining = false
                            writeError = scheduleError.javaClass.simpleName
                        }
                    }
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
