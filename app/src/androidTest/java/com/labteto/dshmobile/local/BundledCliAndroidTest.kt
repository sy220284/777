package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.runtime.BundledGitRuntime
import com.labteto.dshmobile.local.runtime.BundledNodeRuntime
import com.labteto.dshmobile.local.runtime.BundledPythonRuntime
import com.labteto.dshmobile.local.runtime.LocalBundledRuntimeManager
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android executable, linker, interpreter and shell smoke for the five CLI tools. */
@RunWith(AndroidJUnit4::class)
class BundledCliAndroidTest {
    @Test
    fun allFiveCommandsExecuteFromProcessAndWorkspaceShell() = runBlocking {
        withTimeout(180_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = LocalBundledRuntimeManager(
                BundledNodeRuntime(context),
                BundledPythonRuntime(context),
                BundledGitRuntime(context),
            )
            manager.prepare()
            val process = AndroidProcessRuntime(
                dynamicSearchPaths = manager::searchPaths,
                baseEnvironment = manager::environment,
                commandAliases = manager::commandAliases,
            )
            for (name in listOf("rg", "curl", "jq", "npm", "pip", "pip3")) {
                assertTrue("$name missing from installed runtime", process.isCommandAvailable(name))
                val result = process.execute(ProcessRequest(listOf(name, "--version"), timeoutMillis = 25_000))
                assertEquals("$name: ${result.stderr}\n${result.stdout}", 0, result.exitCode)
                assertTrue("$name returned no version", result.stdout.isNotBlank() || result.stderr.isNotBlank())
            }

            val root = File(context.cacheDir, "cli-smoke").apply { mkdirs() }
            val workspace = LocalWorkspace(
                root = root,
                extraSearchPaths = manager::searchPaths,
                environmentProvider = manager::environment,
            )
            val result = workspace.shellResult(
                "set -e; rg --version >/dev/null; curl --version >/dev/null; " +
                    "jq -n '1+1' | grep -qx 2; npm --version; pip --version",
                timeoutSeconds = 90,
            )
            assertTrue("shell invocation failed: ${result.content}", !result.isError)
        }
    }
}
