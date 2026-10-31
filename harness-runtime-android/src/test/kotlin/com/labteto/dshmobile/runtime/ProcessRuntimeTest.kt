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
    @Test fun cleanEnvironmentKeepsOnlyRuntimeAndExplicitChildValues() = runTest {
        val runtime = AndroidProcessRuntime(inheritEnvironment = false,
            baseEnvironment = { mapOf("AGENT_RUNTIME_TEST" to "configured") })
        val result = runtime.execute(ProcessRequest(listOf("sh", "-c", "env"),
            environment = mapOf("AGENT_CHILD_TEST" to "requested")))
        val values = result.stdout.lineSequence().filter { '=' in it }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertEquals(0, result.exitCode)
        assertEquals("configured", values["AGENT_RUNTIME_TEST"])
        assertEquals("requested", values["AGENT_CHILD_TEST"])
        assertFalse(values.containsKey("HOME"))
        assertTrue(values.containsKey("PATH"))
    }

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

    @Test(timeout = 15000)
    fun shortLivedFailingCommandsRetainCompleteStderrToEof() = kotlinx.coroutines.runBlocking {
        val runtime = AndroidProcessRuntime()
        repeat(12) { attempt ->
            val output = runtime.execute(ProcessRequest(
                command = listOf(
                    "sh", "-c", "printf start; printf permission-" + attempt + " >&2; exit 126",
                ),
                timeoutMillis = 3_000,
            ))
            assertEquals(126, output.exitCode)
            assertEquals("start", output.stdout)
            assertEquals("permission-$attempt", output.stderr)
            assertFalse(output.timedOut)
        }
    }

    @Test
    fun executableDirectoriesCannotShadowRealCommandsOnLaterSearchPaths() = runTest {
        val root = kotlin.io.path.createTempDirectory("runtime-command-type-").toFile()
        try {
            val first = File(root, "first").apply { assertTrue(mkdir()) }
            val second = File(root, "second").apply { assertTrue(mkdir()) }
            assertTrue(File(first, "my-tool").mkdir())
            val executable = File(second, "my-tool").apply {
                writeText("#!/bin/sh\nprintf real-executable")
                assertTrue(setExecutable(true))
            }
            val runtime = AndroidProcessRuntime(extraSearchPaths = listOf(first, second))
            assertFalse(runtime.isCommandAvailable("./missing-command"))
            assertFalse(runtime.isCommandAvailable(File(first, "my-tool").absolutePath))
            assertTrue(runtime.isCommandAvailable("my-tool"))
            assertEquals(executable.absolutePath, runtime.resolveCommand(listOf("my-tool")).first())
            val result = runtime.execute(ProcessRequest(listOf("my-tool"), timeoutMillis = 3_000))
            assertEquals(0, result.exitCode)
            assertEquals("real-executable", result.stdout)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extraSearchPathCommandCanActuallyExecute() = runTest {
        val dir = kotlin.io.path.createTempDirectory("runtime-path-").toFile()
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
        val dir = kotlin.io.path.createTempDirectory("runtime-shared-").toFile()
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
    fun persistentTerminalRetainsFinalOutputAfterFastExit() = kotlinx.coroutines.runBlocking {
        val terminal = PersistentPipeTerminalProvider()

        val sessionId = terminal.open(
            command = listOf("sh", "-c", "printf terminal-tail"),
            workingDirectory = null,
        )

        // Child exit and pipe EOF are separate events. Poll the terminal's drain
        // receipt rather than racing its output reader immediately after isAlive.
        val output = StringBuilder()
        var receipt: TerminalReadResult
        do {
            receipt = terminal.readResult(sessionId)
            output.append(receipt.output)
            if (!receipt.exited) kotlinx.coroutines.delay(10)
        } while (!receipt.exited)
        assertEquals("terminal-tail", output.toString())
        assertEquals(0, receipt.exitCode)
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
        val directory = kotlin.io.path.createTempDirectory("process-group-test-").toFile()
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
