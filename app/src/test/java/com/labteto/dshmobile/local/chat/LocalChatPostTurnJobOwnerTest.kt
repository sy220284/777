package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatPostTurnJobOwnerTest {
    @Test
    fun runningConsolidationContinuesAndCoalescesNewSignals() = runBlocking {
        LocalChatPostTurnJobOwner.cancelAndJoin()
        val unblock = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val activity = mutableListOf<Int>()
        val old = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            entered.complete(Unit)
            unblock.await()
            synchronized(activity) { activity += 1 }
        }
        val second = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            synchronized(activity) { activity += 2 }
        }
        val newest = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            synchronized(activity) { activity += 3 }
        }
        try {
            LocalChatPostTurnJobOwner.submit(old)
            old.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(old) }
            withTimeout(2_000) { entered.await() }
            LocalChatPostTurnJobOwner.submit(second)
            second.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(second) }
            LocalChatPostTurnJobOwner.submit(newest)
            newest.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(newest) }
            assertFalse(old.isCancelled)
            assertTrue(second.isCancelled)
            assertFalse(newest.isActive)
            unblock.complete(Unit)
            withTimeout(2_000) { newest.join() }
            assertEquals(listOf(1, 3), synchronized(activity) { activity.toList() })
        } finally {
            unblock.complete(Unit)
            LocalChatPostTurnJobOwner.cancelAndJoin()
        }
    }

    @Test
    fun explicitPreemptionCancelsRunningAndWaitingJobs() = runBlocking {
        LocalChatPostTurnJobOwner.cancelAndJoin()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            entered.complete(Unit)
            gate.await()
        }
        val queued = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { gate.await() }
        try {
            LocalChatPostTurnJobOwner.submit(first)
            first.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(first) }
            withTimeout(2_000) { entered.await() }
            LocalChatPostTurnJobOwner.submit(queued)
            queued.invokeOnCompletion { LocalChatPostTurnJobOwner.clearIf(queued) }
            assertTrue(LocalChatPostTurnJobOwner.cancel())
            withTimeout(2_000) { first.join(); queued.join() }
            assertTrue(first.isCancelled)
            assertTrue(queued.isCancelled)
        } finally {
            gate.complete(Unit)
            LocalChatPostTurnJobOwner.cancelAndJoin()
        }
    }
}
