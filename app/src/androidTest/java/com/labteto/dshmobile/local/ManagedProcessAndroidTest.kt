package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.capability.ProcessRequest
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

        val result = workspace.shell(
            command = "(sleep 1; printf leftover > '${marker.absolutePath}') & wait",
            timeoutSeconds = 1,
        )

        assertTrue("workspace did not return timeout: $result", result.contains("[TOOL_TIMEOUT]"))
        delay(1_200)
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
