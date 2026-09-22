package com.labteto.dshmobile.core.wire

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow

/** One reconnect request per overflowing connection generation. */
class RecoveringEventBuffer<T>(capacity: Int = 256, private val reconnect: () -> Unit) {
    val frames = MutableSharedFlow<T>(extraBufferCapacity = capacity)
    private val recovering = AtomicBoolean(false)

    fun offer(frame: T) {
        if (!frames.tryEmit(frame) && recovering.compareAndSet(false, true)) reconnect()
    }

    fun connected() { recovering.set(false) }
}
