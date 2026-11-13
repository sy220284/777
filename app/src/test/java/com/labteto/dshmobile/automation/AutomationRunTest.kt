package com.labteto.dshmobile.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomationRunTest {
    @Test fun cancellationWaitsForEngineCleanup() = runTest {
        val engineScope = CoroutineScope(coroutineContext + SupervisorJob())
        val cleaned = CompletableDeferred<Unit>()
        val engineJob = engineScope.launch {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(100); cleaned.complete(Unit) }
            }
        }
        val caller = launch { owningAutomationRun(engineJob) { engineJob.join() } }
        runCurrent()
        caller.cancelAndJoin()
        assertTrue(engineJob.isCompleted)
        assertTrue(cleaned.isCompleted)
        engineScope.cancel()
    }
}
