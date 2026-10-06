package com.labteto.dshmobile.ui.screens.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolsOperationGateTest {
    @Test
    fun operationsNeverOverlap() = runTest {
        val gate = ToolsOperationGate()
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
        assertTrue(secondEntered)
    }
}
