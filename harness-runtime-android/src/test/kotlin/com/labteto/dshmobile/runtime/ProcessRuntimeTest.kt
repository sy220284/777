package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
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

    @Test
    fun sharedResolverAndEnvironmentExposeBundledRuntimeToOtherProcessClients() {
        val dir = createTempDir(prefix = "runtime-shared-")
        try {
            val command = File(dir, "bundled-tool").apply {
                writeText("#!/bin/sh\nprintf shared")
                assertTrue(setExecutable(true))
            }
            val runtime = AndroidProcessRuntime(
                dynamicSearchPaths = { listOf(dir) },
                baseEnvironment = { mapOf("DSH_RUNTIME_FLAG" to "ready") },
            )

            assertEquals(command.absolutePath, runtime.resolveCommand(listOf("bundled-tool")).first())
            val environment = runtime.processEnvironment(mapOf("EXTRA_FLAG" to "ok"))
            assertTrue(environment["PATH"].orEmpty().split(File.pathSeparator).contains(dir.path))
            assertEquals("ready", environment["DSH_RUNTIME_FLAG"])
            assertEquals("ok", environment["EXTRA_FLAG"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 5000)
    fun cancellationCompletesWhileProcessIsStillRunning() = kotlinx.coroutines.runBlocking {
        val running = async(kotlinx.coroutines.Dispatchers.IO) {
            AndroidProcessRuntime().execute(ProcessRequest(listOf("sh", "-c", "sleep 30")))
        }
        kotlinx.coroutines.delay(100)
        running.cancel()
        running.join()
        assertTrue(running.isCancelled)
    }

    @Test(timeout = 5000)
    fun timeoutAlsoCoversBlockedStandardInput() = kotlinx.coroutines.runBlocking {
        val result = AndroidProcessRuntime().execute(ProcessRequest(
            command = listOf("sh", "-c", "sleep 30"),
            stdin = "x".repeat(1_000_000),
            timeoutMillis = 100,
        ))
        assertTrue(result.timedOut)
    }

    @Test(timeout = 5000)
    fun timeoutStopsInheritedDescendants() = kotlinx.coroutines.runBlocking {
        val directory = createTempDir(prefix = "process-group-test-")
        try {
            val marker = File(directory, "unexpected-output")
            val result = AndroidProcessRuntime().execute(ProcessRequest(
                command = listOf("sh", "-c", "(sleep 1; printf leftover > \"\$1\") & wait", "check", marker.path),
                timeoutMillis = 100,
            ))
            assertTrue(result.timedOut)
            kotlinx.coroutines.delay(1200)
            assertFalse("descendant survived timeout", marker.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

}
