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
import com.labteto.dshmobile.runtime.ProcessIsolationContext
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

            // Work tools run behind Landlock/seccomp. Verify interpreter resources
            // remain readable without granting extra writes or network permissions.
            val sandboxRoot = File(context.cacheDir, "cli-isolated").apply { mkdirs() }
            val isolated = AndroidProcessRuntime(
                defaultWorkingDirectory = sandboxRoot,
                dynamicSearchPaths = manager::searchPaths,
                baseEnvironment = manager::environment,
                commandAliases = manager::commandAliases,
            )
            // The Android emulator can block syscall 444 (Landlock) with ENOSYS
            // even though Linux desktop hosts support the same sandbox. The
            // only permitted device outcomes are a real isolated execution
            // OR a verified fail-closed refusal before the CLI ever starts.
            // Never substitute an unisolated child for a rejected sandbox.
            val isolatedOutcomes = mutableSetOf<Boolean>()
            val isolationFailures = mutableListOf<String>()
            withContext(ProcessIsolationContext()) {
                // Record every interpreter independently before failing the gate. In
                // particular, node --version distinguishes the native runtime from
                // npm's JavaScript entry point after Android installs its seccomp policy.
                for (name in listOf("rg", "curl", "jq", "pip", "node", "npm")) {
                    val result = isolated.execute(
                        ProcessRequest(listOf(name, "--version"), timeoutMillis = 25_000),
                    )
                    if (result.exitCode == 0) {
                        if (result.stdout.isBlank() && result.stderr.isBlank()) {
                            isolationFailures += "$name returned no output in sandbox"
                        }
                        isolatedOutcomes += true
                    } else if (result.exitCode == 125) {
                        if (!result.stderr.contains("PROCESS_ISOLATION_UNAVAILABLE:") ||
                            !(result.stderr.contains("Landlock ABI unavailable") ||
                                result.stderr.contains("Landlock ABI >= 3 required")) ||
                            result.stdout.isNotBlank()
                        ) {
                            isolationFailures += "$name refused isolation without verified kernel cause: ${result.stderr} / ${result.stdout}"
                        }
                        isolatedOutcomes += false
                    } else {
                        isolationFailures += "$name crashed/failed under isolation (exit ${result.exitCode}): ${result.stderr} / ${result.stdout}"
                    }
                }
            }
            assertTrue("Isolated runtime diagnostics: ${isolationFailures.joinToString("; ")}",
                isolationFailures.isEmpty())
            assertEquals("All commands must see the same immutable kernel sandbox capability",
                1, isolatedOutcomes.size)

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
