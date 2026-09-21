package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import java.io.File
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
    fun extraSearchPathCommandCanActuallyExecute() = runTest {
        val dir = createTempDir(prefix = "runtime-path-")
        try {
            val command = File(dir, "runtime-hello").apply {
                writeText("#!/bin/sh\nprintf bundled")
                assertTrue(setExecutable(true))
            }
            val runtime = AndroidProcessRuntime(extraSearchPaths = listOf(dir))

            assertTrue(runtime.isCommandAvailable(command.name))
            val result = runtime.execute(
                ProcessRequest(command = listOf(command.name), timeoutMillis = 5_000),
            )

            assertEquals(0, result.exitCode)
            assertEquals("bundled", result.stdout)
        } finally {
            dir.deleteRecursively()
        }
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
