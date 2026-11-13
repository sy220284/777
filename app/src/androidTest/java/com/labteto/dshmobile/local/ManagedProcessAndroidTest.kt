package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import com.labteto.dshmobile.runtime.PersistentPipeTerminalProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedProcessAndroidTest {
    @Test
    fun systemProcessGroupWorksOnAndroid() = runBlocking {
        val runtime = AndroidProcessRuntime()
        val normal = runtime.execute(ProcessRequest(listOf("/system/bin/sh", "-c", "printf ready")))
        assertEquals(0, normal.exitCode)
        assertEquals("ready", normal.stdout)

        val timeout = runtime.execute(
            ProcessRequest(listOf("/system/bin/sh", "-c", "sleep 30"), timeoutMillis = 100),
        )
        assertTrue(timeout.timedOut)
    }

    @Test
    fun repeatedTimeoutsReportTypedTimeoutEvenWhenAndroidClosesPipesDuringTeardown() = runBlocking {
        val runtime = AndroidProcessRuntime()
        repeat(4) { attempt ->
            val result = runtime.execute(
                ProcessRequest(
                    command = listOf("/system/bin/sh", "-c", "printf started; sleep 20"),
                    timeoutMillis = 200,
                ),
            )
            assertTrue("timeout attempt $attempt should return a result", result.timedOut)
            assertEquals(-1, result.exitCode)
            assertTrue("output read before cancellation must be retained", result.stdout.contains("started"))
        }
    }

    @Test
    fun runtimeTimeoutKillsInheritedDescendantsWithoutIoFailure() = runBlocking {
        val root = reproRoot("runtime")
        val marker = File(root, "unexpected-output")
        val runtime = AndroidProcessRuntime(defaultWorkingDirectory = root)

        val result = runtime.execute(
            ProcessRequest(
                command = listOf(
                    "/system/bin/sh",
                    "-c",
                    "(sleep 1; printf leftover > \"\$1\") & wait",
                    "check",
                    marker.absolutePath,
                ),
                timeoutMillis = 100,
            ),
        )

        assertTrue(result.timedOut)
        delay(1_200)
        assertFalse("runtime descendant survived timeout", marker.exists())
    }

    @Test
    fun localWorkspaceTimeoutKillsInheritedDescendantsWithoutIoFailure() = runBlocking {
        val root = reproRoot("workspace")
        val marker = File(root, "unexpected-output")
        val workspace = LocalWorkspace(root)

        val result = workspace.shellResult(
            command = "(sleep 2; printf leftover > '${marker.absolutePath}') & wait",
            timeoutSeconds = 1,
        )

        // The typed ToolResult is the authoritative outcome. Human-readable shell
        // output intentionally no longer embeds legacy "[TOOL_TIMEOUT]" markers.
        assertTrue("workspace timeout must be an error: $result", result.isError)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
        delay(2_200)
        assertFalse("workspace descendant survived timeout", marker.exists())
    }

    @Test
    fun persistentTerminalCloseKillsInheritedDescendants() = runBlocking {
        val root = reproRoot("terminal")
        val marker = File(root, "unexpected-output")
        val terminal = PersistentPipeTerminalProvider(defaultWorkingDirectory = root)

        val sessionId = terminal.open(
            command = listOf(
                "/system/bin/sh",
                "-c",
                "(sleep 1; printf leftover > \"\$1\") & wait",
                "check",
                marker.absolutePath,
            ),
            workingDirectory = null,
        )

        delay(100)
        terminal.close(sessionId)
        delay(1_200)
        assertFalse("terminal descendant survived close", marker.exists())
    }

    private fun reproRoot(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.cacheDir, "managed-process-$name").apply {
            deleteRecursively()
            mkdirs()
        }
    }
}
