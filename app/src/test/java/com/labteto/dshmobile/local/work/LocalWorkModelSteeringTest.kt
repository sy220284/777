package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CompletableDeferred
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkModelSteeringTest {
    @Test
    fun committedCorrectionCancelsOnlySamplingAndWaitsForTermination() = runTest {
        val revision = MutableStateFlow(0L)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val sampling = async {
            sampleUntilWorkSteered(revision, 0L) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        started.await()
        revision.value = 1L
        assertEquals(LocalWorkSamplingOutcome.Steered, sampling.await())
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun staleUnstartedToolIsSkippedWithoutAnyClaimedSideEffect() {
        val call = LocalToolCall("call-old", "write", JsonObject(emptyMap()), "{}")
        val result = workSteeredToolResult(call)
        assertTrue(result.isError)
        assertEquals("WORK_TOOL_SUPERSEDED", result.errorCode)
        assertEquals(AgentToolSideEffect.NONE, result.sideEffect)
        assertEquals(false, result.retryable)
    }

    @Test
    fun normalReplyStillWinsWhenNoCorrectionIsCommitted() = runTest {
        val revision = MutableStateFlow(2L)
        val result = sampleUntilWorkSteered(revision, 2L) { "verified reply" }
        assertEquals(LocalWorkSamplingOutcome.Completed("verified reply"), result)
    }

    @Test
    fun correctionBeforeProviderStartAvoidsStartingStaleRequest() = runTest {
        val revision = MutableStateFlow(6L)
        var started = false
        val result = sampleUntilWorkSteered(revision, 5L) {
            started = true
            "outdated"
        }
        assertEquals(LocalWorkSamplingOutcome.Steered, result)
        assertEquals(false, started)
    }
}
