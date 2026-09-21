package com.labteto.dshmobile.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConcurrencyTest {
    @Test
    fun oneParallelFailureDoesNotCancelSibling() = runTest {
        val siblingRan = CompletableDeferred<Unit>()
        val results = isolatedParallelMap(listOf("fail", "ok")) { item ->
            if (item == "fail") error("boom")
            siblingRan.complete(Unit)
            "done"
        }

        assertTrue(results[0].isFailure)
        assertEquals("done", results[1].getOrNull())
        assertTrue(siblingRan.isCompleted)
    }

    @Test
    fun childCancellationIsIsolatedWhileParentRemainsActive() = runTest {
        val results = isolatedParallelMap(listOf("cancel", "ok")) { item ->
            if (item == "cancel") throw CancellationException("child-only")
            "survived"
        }

        assertTrue(results[0].isFailure)
        assertEquals("survived", results[1].getOrNull())
    }
}
