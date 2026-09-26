package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.workflow.HarnessWorkflowCheckpoint
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowMode
import com.labteto.dshmobile.harness.workflow.HarnessWorkflowTaskResult
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkflowPersistenceTest {
    @Test
    fun workflowCheckpointRoundTripsWithoutLosingFailureState() {
        val checkpoint = HarnessWorkflowCheckpoint(
            mode = HarnessWorkflowMode.PARALLEL,
            tasks = listOf("a", "b"),
            results = listOf(
                HarnessWorkflowTaskResult(0, "a", output = "A"),
                HarnessWorkflowTaskResult(1, "b", error = "failed"),
            ),
        )

        val decoded = decodeWorkflowCheckpoint(encodeWorkflowCheckpoint("wf-1", checkpoint))

        assertEquals(checkpoint, decoded)
    }
}
