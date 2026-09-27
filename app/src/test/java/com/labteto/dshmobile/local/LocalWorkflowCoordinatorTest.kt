package com.labteto.dshmobile.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkflowCoordinatorTest {
    @Test fun requiredEvidenceTriggersOneReassignmentAndReportsRealCheck() = runTest {
        var calls = 0
        val coordinator = LocalWorkflowCoordinator(
            execute = { calls++; if (calls == 1) "初稿" else "核对证据已补齐" },
            pruneOutput = { it },
            onProgress = {},
        )

        val result = coordinator.run(listOf("核对资料"), "pipeline", listOf("证据"))

        assertEquals(2, calls)
        assertTrue(result.contains("指定证据已命中"))
        assertTrue(result.contains("尝试 2 次"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedEvidenceCannotSilentlyPass() = runTest {
        LocalWorkflowCoordinator(execute = { "完成" }, pruneOutput = { it }, onProgress = {})
            .run(listOf("一", "二"), "parallel", listOf("一项证据"))
    }
}
