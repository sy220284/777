package com.labteto.dshmobile.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInputQueueTest {
    @Test
    fun preservesOrderAndEnforcesCapacity() {
        val queue = AgentInputQueue(capacity = 2)

        assertTrue(queue.offer(QueuedAgentInput("一", id = "q1")))
        assertTrue(queue.offer(QueuedAgentInput("二", id = "q2")))
        assertFalse(queue.offer(QueuedAgentInput("三", id = "q3")))
        assertEquals(listOf("一", "二"), queue.drain().map { it.content })
        assertEquals(0, queue.size())
    }

    @Test
    fun pollAndClearAreDeterministic() {
        val queue = AgentInputQueue(capacity = 4)
        queue.offer(QueuedAgentInput("一", id = "q1"))
        queue.offer(QueuedAgentInput("二", id = "q2"))

        assertEquals("一", queue.poll()?.content)
        assertEquals(1, queue.clear())
        assertEquals(null, queue.poll())
    }

    @Test
    fun snapshotAndRestorePreserveDurableOrder() {
        val queue = AgentInputQueue(capacity = 4)
        queue.offer(QueuedAgentInput("一", id = "q1"))
        queue.offer(QueuedAgentInput("二", id = "q2"))

        val snapshot = queue.snapshot()
        queue.clear()
        queue.restore(snapshot)

        assertEquals(listOf("q1", "q2"), queue.drain().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun restoreRejectsDuplicateDurableIds() {
        AgentInputQueue(capacity = 4).restore(
            listOf(
                QueuedAgentInput("一", id = "same"),
                QueuedAgentInput("二", id = "same"),
            ),
        )
    }
}
