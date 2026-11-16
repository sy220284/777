package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LocalTeamRefreshQueueTest {
    @Test
    fun longRebuildInOneSessionDoesNotBlockAnotherSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        try {
            val queue = LocalTeamRefreshQueue(scope) { id, _ ->
                if (id == "long") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else if (id == "short") {
                    secondDone.complete(Unit)
                }
            }
            queue.schedule("long", reconcile = true)
            withTimeout(5_000) { firstStarted.await() }
            queue.schedule("short", reconcile = true)
            withTimeout(5_000) { secondDone.await() }
        } finally {
            releaseFirst.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun refreshForTheSameSessionNeverOverlapsItsPreviousRun() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val calls = AtomicInteger()
        try {
            val queue = LocalTeamRefreshQueue(scope) { id, _ ->
                if (id != "same") return@LocalTeamRefreshQueue
                assertEquals(1, active.incrementAndGet())
                try {
                    when (calls.incrementAndGet()) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        2 -> secondDone.complete(Unit)
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
            queue.schedule("same", reconcile = true)
            withTimeout(5_000) { firstStarted.await() }
            queue.schedule("same", reconcile = true)
            releaseFirst.complete(Unit)
            withTimeout(5_000) { secondDone.await() }
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun queuedSessionRefreshMergesReconcileInsteadOfRebuildingTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val observed = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        try {
            val queue = LocalTeamRefreshQueue(scope) { id, reconcile ->
                if (id != "coalesce") return@LocalTeamRefreshQueue
                observed.add(reconcile)
                if (observed.size == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondDone.complete(Unit)
                }
            }
            queue.schedule("coalesce", reconcile = false)
            withTimeout(5_000) { firstStarted.await() }
            queue.schedule("coalesce", reconcile = false)
            queue.schedule("coalesce", reconcile = true)
            releaseFirst.complete(Unit)
            withTimeout(5_000) { secondDone.await() }
            assertEquals(listOf(false, true), observed.toList())
        } finally {
            releaseFirst.complete(Unit)
            scope.cancel()
        }
    }


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun normalNonReconcileRefreshStartsWithoutAnExtraTimerTick() = runTest {
        val completed = mutableListOf<String>()
        val queue = LocalTeamRefreshQueue(backgroundScope) { id, reconcile ->
            completed += "$id:$reconcile"
        }
        queue.schedule("instant", reconcile = false)
        runCurrent()
        assertEquals(listOf("instant:false"), completed)
    }

}
