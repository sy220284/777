package com.labteto.dshmobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentTerminalGenerationTest {
    @Test
    fun epochBearingIdFromAnotherRuntimeIsExplicitlyStale() {
        val oldEpoch = "abcdef1234"
        val newEpoch = "123456789a"
        val previous = "term-${oldEpoch}-0a1b2c3d4e5f"
        assertTrue(isPriorTerminalRuntimeSession(previous, newEpoch))
        assertFalse(isPriorTerminalRuntimeSession(previous, oldEpoch))
    }

    @Test
    fun unknownAndLegacyTerminalIdsAreNotMisclassifiedAsConfirmedRuntimeResets() {
        val now = "123456789a"
        assertFalse(isPriorTerminalRuntimeSession("term-legacy123456789", now))
        assertFalse(isPriorTerminalRuntimeSession("someone-else", now))
        assertFalse(isPriorTerminalRuntimeSession("term-abcdef1234-invalid", now))
        assertFalse(isPriorTerminalRuntimeSession("", now))
    }

    @Test
    fun aNewProviderRecognizesPreviousProviderRuntimeIdentity() {
        val first = PersistentPipeTerminalProvider()
        val second = PersistentPipeTerminalProvider()
        // No process needs to be launched: runtime identity belongs to the provider lifetime.
        val priorId = "term-${first.runtimeGeneration()}-0123456789ab"
        assertFalse(first.isFromPreviousRuntime(priorId))
        assertTrue(second.isFromPreviousRuntime(priorId))
    }
}
