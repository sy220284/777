package com.labteto.dshmobile.automation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebhookExecutionTest {
    @Test fun cancellationWhileRunningIsRecordedAndPropagated() = runTest {
        val states = mutableListOf<String>()
        val job = launch { executeWebhookRun(Mutex(), { state, _, _ -> states += state }) { awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("running", "cancelled"), states)
    }
    @Test fun queuedCancellationDoesNotRunPrompt() = runTest {
        val states = mutableListOf<String>()
        val mutex = Mutex(locked = true)
        val job = launch { executeWebhookRun(mutex, { state, _, _ -> states += state }) { error("must not run") } }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("cancelled"), states)
    }
    @Test fun successfulRunStoresResult() = runTest {
        val states = mutableListOf<String>()
        var output: String? = null
        executeWebhookRun(Mutex(), { state, result, _ -> states += state; output = result }) { "done" }
        assertEquals(listOf("running", "completed"), states)
        assertEquals("done", output)
    }
}
