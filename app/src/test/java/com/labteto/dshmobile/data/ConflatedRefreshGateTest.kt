package com.labteto.dshmobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflatedRefreshGateTest {
    @Test
    fun requestDuringActiveRefreshProducesOneTrailingPass() = runTest {
        val gate = ConflatedRefreshGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0

        val first = async {
            gate.request {
                runs += 1
                if (runs == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }

        entered.await()
        repeat(5) {
            gate.request { runs += 1 }
        }
        release.complete(Unit)
        first.await()

        assertEquals(2, runs)
    }

    @Test
    fun separateIdleRequestsEachRun() = runTest {
        val gate = ConflatedRefreshGate()
        var runs = 0

        gate.request { runs += 1 }
        gate.request { runs += 1 }

        assertEquals(2, runs)
    }
}
