package com.labteto.dshmobile.ui.screens.pair

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayClaimPersistenceTest {
    @Test
    fun cancellationAfterRemoteClaimCannotInterruptLocalCommit() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completed = false

        val job = launch {
            completeRelayClaimPersistence {
                entered.complete(Unit)
                release.await()
                completed = true
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(completed)
    }
}
