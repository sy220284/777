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

    @Test
    fun failedDurableAdmissionRemovesOnlyTheNewInput() {
        val queue = AgentInputQueue(capacity = 2)
        queue.offer(QueuedAgentInput("accepted", id = "old"))
        val failure = IllegalStateException("disk failure")
        val result = runCatching {
            queue.offer(QueuedAgentInput("rejected", id = "new")) {
                assertEquals(listOf("old", "new"), queue.snapshot().map { it.id })
                throw failure
            }
        }
        assertTrue(result.exceptionOrNull() === failure)
        assertEquals(listOf("old"), queue.snapshot().map { it.id })
        assertTrue(queue.offer(QueuedAgentInput("retry", id = "new")))
    }

    @Test
    fun concurrentConsumerCannotClaimInputBeforeItsDurableCommit() {
        val queue = AgentInputQueue(capacity = 2)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val consumerStarted = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val producer = executor.submit<Boolean> {
                queue.offer(QueuedAgentInput("new", id = "new")) {
                    started.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val consumer = executor.submit<List<QueuedAgentInput>> {
                consumerStarted.countDown()
                queue.drain()
            }
            assertTrue(consumerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(consumer.isDone)
            release.countDown()
            assertTrue(producer.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("new"), consumer.get(5, java.util.concurrent.TimeUnit.SECONDS).map { it.id })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedConsumptionPreservesOrderAndDoesNotExposeRemoval() {
        val queue = AgentInputQueue(4)
        queue.offer(QueuedAgentInput("一", id = "q1"))
        queue.offer(QueuedAgentInput("二", id = "q2"))
        val failure = IllegalStateException("disk failure")
        val result = runCatching {
            queue.pollCommitted { input, remaining ->
                assertEquals("q1", input.id)
                assertEquals(listOf("q2"), remaining.map { it.id })
                throw failure
            }
        }
        assertTrue(result.exceptionOrNull() === failure)
        assertEquals(listOf("q1", "q2"), queue.snapshot().map { it.id })
        assertEquals("q1", queue.pollCommitted { _, _ -> }?.id)
        assertEquals(listOf("q2"), queue.snapshot().map { it.id })
    }

    @Test
    fun batchCommitFailureKeepsEveryInputAndOrder() {
        val queue = AgentInputQueue(4)
        queue.offer(QueuedAgentInput("one", id = "1"))
        queue.offer(QueuedAgentInput("two", id = "2"))
        assertTrue(runCatching {
            queue.drainCommitted { batch ->
                assertEquals(listOf("1", "2"), batch.map { it.id })
                throw java.io.IOException("disk full")
            }
        }.isFailure)
        assertEquals(listOf("1", "2"), queue.snapshot().map { it.id })
        queue.drainCommitted { }
        assertEquals(0, queue.size())
    }

    @Test
    fun transferPreservesRemainingInputsAndRejectsInsufficientCapacity() {
        val source = AgentInputQueue(4)
        source.restore(listOf(QueuedAgentInput("one", id = "1"), QueuedAgentInput("two", id = "2")))
        val small = AgentInputQueue(1)
        assertTrue(runCatching { source.transferTo(small) }.isFailure)
        assertEquals(listOf("1", "2"), source.snapshot().map { it.id })
        assertEquals(0, small.size())
        val target = AgentInputQueue(4)
        source.pollCommitted { _, _ -> }
        source.transferTo(target)
        assertEquals(0, source.size())
        assertEquals(listOf("2"), target.snapshot().map { it.id })
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
