package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelMutationGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelMutationGateTest {
    @Test
    fun secondMutationCannotEnterBeforeFirstLeaves() = runTest {
        val gate = LocalModelMutationGate
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = async {
            gate.run {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = async {
            gate.run {
                secondEntered = true
            }
        }
        runCurrent()

        assertFalse(secondEntered)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(true, secondEntered)
    }

    @Test
    fun cancelledWaiterDoesNotPoisonFollowingMutation() = runTest {
        val gate = LocalModelMutationGate
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var cancelledWaiterEntered = false
        var followingEntered = false

        val first = async {
            gate.run {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val cancelled = async {
            gate.run {
                cancelledWaiterEntered = true
            }
        }
        runCurrent()
        cancelled.cancel()
        cancelled.join()

        val following = async {
            gate.run {
                followingEntered = true
            }
        }
        runCurrent()
        assertFalse(followingEntered)

        releaseFirst.complete(Unit)
        joinAll(first, following)

        assertFalse(cancelledWaiterEntered)
        assertTrue(followingEntered)
    }

    @Test
    fun mutationStormNeverOverlapsCriticalSection() = runTest {
        val gate = LocalModelMutationGate
        var active = 0
        var maxActive = 0
        var completed = 0

        val jobs = (0 until 100).map {
            async {
                gate.run {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    delay(1)
                    completed += 1
                    active -= 1
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(100, completed)
        assertEquals(1, maxActive)
        assertEquals(0, active)
    }
}
