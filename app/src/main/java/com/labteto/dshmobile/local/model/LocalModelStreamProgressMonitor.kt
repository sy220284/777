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
) {
    init { require(timeoutNanos > 0) }
    private var lastProgressNanos = nanoTime()

    fun recordProgress() {
        lastProgressNanos = nanoTime()
    }

    fun isStalled(): Boolean = nanoTime() - lastProgressNanos >= timeoutNanos
}
