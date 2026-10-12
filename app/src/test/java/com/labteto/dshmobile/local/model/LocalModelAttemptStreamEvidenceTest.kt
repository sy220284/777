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
    fun preserveSurrogatePairsInDiagnosticTail() {
        val evidence = LocalModelAttemptStreamEvidence(maxTailChars = 6)
        evidence.append("hello🙂world")
        assertTrue(evidence.tail.length <= 6)
        assertFalse(evidence.tail.startsWith("\uDE42"))
        assertFalse(evidence.tail.endsWith("\uD83D"))
    }
}
