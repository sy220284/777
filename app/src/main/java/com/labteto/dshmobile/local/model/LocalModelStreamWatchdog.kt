package com.labteto.dshmobile.local.model

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A blocked SSE read must not outrun its useful-progress deadline. No request replay. */
internal class LocalModelStreamWatchdog(
    private val monitor: LocalModelStreamProgressMonitor,
    private val cancelCall: () -> Unit,
    intervalMillis: Long = 1_000L,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val scheduled: ScheduledFuture<*>

    init {
        require(intervalMillis > 0)
        scheduled = scheduler.scheduleAtFixedRate(
            { evaluate() }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS,
        )
    }

    internal fun evaluate(): Boolean {
        if (closed.get() || !monitor.isStalled() || !expired.compareAndSet(false, true)) return false
        if (closed.get()) return false
        cancelCall()
        return true
    }

    fun timedOut(): Boolean = expired.get()

    override fun close() {
        closed.set(true)
        scheduled.cancel(false)
    }

    private companion object {
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "model-stream-watchdog").apply { isDaemon = true }
        }
    }
}
