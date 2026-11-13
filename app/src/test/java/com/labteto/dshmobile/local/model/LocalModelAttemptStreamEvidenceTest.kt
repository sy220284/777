package com.labteto.dshmobile.local.model

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelAttemptStreamEvidenceTest {
    @Test
    fun attemptResetIsSpecificToTheActualProviderAttempt() {
        val evidence = LocalModelAttemptStreamEvidence(maxTailChars = 8)
        evidence.append("abcdef")
        evidence.append("ghijkl")
        assertEquals(12, evidence.emittedChars)
        assertTrue(evidence.truncated)
        assertFalse(evidence.retryable(IOException("body may already be sent")))
        evidence.beginAttempt()
        assertEquals(0, evidence.emittedChars)
        assertEquals("", evidence.tail)
        assertFalse(evidence.truncated)
    }

    @Test
    fun reasoningOnlyStreamCannotBeReplayedAsAnEmptyProviderAttempt() {
        val evidence = LocalModelAttemptStreamEvidence()
        val rejected = com.labteto.dshmobile.local.LocalModelException(
            code = "MODEL_HTTP_503",
            message = "transient rejection",
            retryable = true,
            status = 503,
            admissionState = LocalModelAdmissionState.REJECTED,
        )
        assertTrue(evidence.retryable(rejected))
        evidence.observe(LocalModelDelta(reasoning = "private reasoning tokens"))
        assertEquals(0, evidence.emittedChars)
        assertEquals("", evidence.tail) // diagnostic evidence cannot leak reasoning text
        assertTrue(evidence.reasoningObserved)
        assertFalse(evidence.retryable(rejected))
        evidence.beginAttempt()
        assertFalse(evidence.reasoningObserved)
        assertTrue(evidence.retryable(rejected))
    }

    @Test
    fun mixedReasoningAndBodyRemainSeparateFromDiagnosticTail() {
        val evidence = LocalModelAttemptStreamEvidence(maxTailChars = 32)
        evidence.observe(LocalModelDelta(content = "visible", reasoning = "hidden-secret"))
        assertEquals("visible", evidence.tail)
        assertEquals(7, evidence.emittedChars)
        assertTrue(evidence.reasoningObserved)
        assertFalse(evidence.tail.contains("hidden-secret"))
    }

    @Test
    fun preserveSurrogatePairsInDiagnosticTail() {
        val evidence = LocalModelAttemptStreamEvidence(maxTailChars = 6)
        evidence.append("hello🙂world")
        assertTrue(evidence.tail.length <= 6)
        assertFalse(evidence.tail.startsWith("\uDE42"))
        assertFalse(evidence.tail.endsWith("\uD83D"))
    }
}
