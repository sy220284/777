package com.labteto.dshmobile.local.model

import java.util.concurrent.TimeUnit

/**
 * Tracks model-visible progress, not HTTP keepalive frames.
 * A provider can keep a socket alive while making zero useful forward progress.
 * Successful text, reasoning or tool-call frames reset the inactivity deadline.
 */
internal class LocalModelStreamProgressMonitor(
    private val timeoutNanos: Long = TimeUnit.MINUTES.toNanos(8),
    private val nanoTime: () -> Long = System::nanoTime,
    private val startupTimeoutNanos: Long = timeoutNanos,
) {
    init {
        require(timeoutNanos > 0)
        require(startupTimeoutNanos >= timeoutNanos)
    }
    private var lastProgressNanos = nanoTime()
    private var startedGenerating = false

    fun recordProgress() {
        startedGenerating = true
        lastProgressNanos = nanoTime()
    }

    // The provider may legally send keepalive comments for ten minutes before
    // inference begins. Do not reset this deadline on keepalive-only traffic.
    fun isStalled(): Boolean = nanoTime() - lastProgressNanos >=
        (if (startedGenerating) timeoutNanos else startupTimeoutNanos)
}
