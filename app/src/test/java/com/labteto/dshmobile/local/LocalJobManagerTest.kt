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
}
