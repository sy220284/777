package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoveringEventBufferTest {
    @Test fun overflowRequestsOneReconnectUntilNewGenerationIsConnected() = runTest {
        var reconnects = 0
        val buffer = RecoveringEventBuffer<Int>(2) { reconnects++ }
        val hold = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch { buffer.frames.collect { hold.await() } }
        runCurrent()
        buffer.offer(1)
        runCurrent()
        repeat(20) { buffer.offer(it) }
        assertEquals(1, reconnects)
        buffer.connected()
        repeat(20) { buffer.offer(it) }
        assertEquals(2, reconnects)
        collector.cancel()
    }
}
