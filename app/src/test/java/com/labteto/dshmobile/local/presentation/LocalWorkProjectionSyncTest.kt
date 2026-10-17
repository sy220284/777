package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.work.LocalWorkState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkProjectionSyncTest {
    @Test
    fun projectionFailureIsVisibleButDoesNotChangeTheModelsExecutionResult() {
        val failedProjection = LocalHarnessState(
            sessionId = "session-1",
            work = LocalWorkState(workProjectionOutOfSync = true),
            error = null,
        )
        assertTrue(failedProjection.toWorkUiState().workProjectionOutOfSync)
        assertNull(failedProjection.error)

        val resynced = failedProjection.copy(
            work = failedProjection.work.copy(workProjectionOutOfSync = false),
        )
        assertFalse(resynced.toWorkUiState().workProjectionOutOfSync)
        assertNull(resynced.error)
    }
}
