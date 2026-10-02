package com.labteto.dshmobile.harness.workflow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessWorkflowTerminalErrorTest {
    @Test
    fun nonRetryableExecutorErrorEscapesWithoutReassignment() = runTest {
        var attempts = 0
        val error = runCatching {
            HarnessWorkflowRunner().run(
                tasks = listOf("任务"),
                mode = HarnessWorkflowMode.PARALLEL,
                maxAttempts = 2,
                shouldRetryError = { false },
            ) { _, _, _ ->
                attempts++
                error("terminal")
            }
        }.exceptionOrNull()

        assertEquals("terminal", error?.message)
        assertEquals(1, attempts)
    }
}
