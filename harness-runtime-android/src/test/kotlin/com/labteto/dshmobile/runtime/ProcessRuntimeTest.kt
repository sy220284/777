package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRuntimeTest {
    @Test
    fun executesProcessAndCapturesStreams() = runTest {
        val runtime = AndroidProcessRuntime()
        val result = runtime.execute(
            ProcessRequest(
                command = listOf("sh", "-c", "printf out; printf err >&2; exit 7"),
                timeoutMillis = 5_000,
            ),
        )
        assertEquals(7, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertFalse(result.timedOut)
    }

    @Test
    fun timeoutIsReportedWithoutFabricatingSuccess() = runTest {
        val runtime = AndroidProcessRuntime()
        val result = runtime.execute(
            ProcessRequest(
                command = listOf("sh", "-c", "sleep 2"),
                timeoutMillis = 50,
            ),
        )
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }
}
