package com.labteto.dshmobile.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalJobManagerTest {
    @Test
    fun exposesProgressBeforeJobCompletesAndThenPublishesFinalOutput() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }
        val started = manager.start("streaming") { _, report ->
            report("partial output")
            gate.await()
            "final output"
        }
        val id = started.substringAfterLast('：')

        runCurrent()
        assertTrue(manager.output(id).contains("partial output"))
        assertTrue(manager.output(id).contains("[running]"))

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.output(id).contains("final output"))
        assertTrue(manager.output(id).contains("[completed]"))
    }
    @Test
    fun jobIdsAreUniqueAndCancellingOneDoesNotCancelAnother() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val manager = LocalJobManager(this) { }

        val first = manager.start("first") { _, _ ->
            gateA.await()
            "first done"
        }
        val second = manager.start("second") { _, _ ->
            gateB.await()
            "second done"
        }
        val firstId = first.substringAfterLast('：')
        val secondId = second.substringAfterLast('：')

        assertTrue(firstId != secondId)
        assertTrue(firstId.startsWith("job-"))
        assertTrue(secondId.startsWith("job-"))

        manager.kill(firstId)
        runCurrent()
        gateB.complete(Unit)
        advanceUntilIdle()

        assertTrue(manager.output(firstId).contains("[cancelled]"))
        assertTrue(manager.output(secondId).contains("[completed]"))
        assertTrue(manager.output(secondId).contains("second done"))
    }

}
