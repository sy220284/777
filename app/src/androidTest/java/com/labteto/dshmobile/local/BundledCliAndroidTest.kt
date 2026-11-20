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
import com.labteto.dshmobile.runtime.secureIsolatedProcessBuilder
import com.labteto.dshmobile.runtime.executeManagedProcess
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
            // Debug-only differential diagnosis: if npm crashes, run the same
            // hardcoded --version probe with exactly one policy layer removed.
            // This is instrumentation code, never a production execution fallback.
            val detailedCause = if (isolationFailures.any { it.startsWith("npm crashed/failed") }) {
                diagnoseNpmSandboxLayers(manager, sandboxRoot)
            } else ""
            assertTrue("Isolated runtime diagnostics: ${isolationFailures.joinToString("; ")}\n$detailedCause",
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

    private suspend fun diagnoseNpmSandboxLayers(
        manager: LocalBundledRuntimeManager,
        workspace: File,
    ): String {
        val environment = manager.environment()
        val original = withContext(ProcessIsolationContext()) {
            secureIsolatedProcessBuilder(
                ProcessBuilder(manager.commandAliases().getValue("npm") + "--version")
                    .directory(workspace).apply { environment().putAll(environment) },
                manager.searchPaths(), environment,
            )
        }
        val originalArgs = original.command()
        val originalScript = originalArgs[5]
        val anchor = "    filesystem(policy)\n    syscalls()"
        check(originalScript.contains(anchor)) { "隔离诊断脚本缺少已知的保护安装点" }
        val basePolicy = kotlinx.serialization.json.Json.parseToJsonElement(originalArgs[6])
            as kotlinx.serialization.json.JsonObject
        fun policyWithExtraRead(vararg roots: String): String =
            kotlinx.serialization.json.JsonObject(basePolicy + (
                "read" to kotlinx.serialization.json.JsonArray(
                    roots.map { kotlinx.serialization.json.JsonPrimitive(it) } +
                        (basePolicy.getValue("read") as kotlinx.serialization.json.JsonArray),
                )
            )).toString()
        val allReadPolicy = policyWithExtraRead("/")
        val landlockOnly = originalScript.replace(anchor,
            "    filesystem(policy)\n    # test-only: omit seccomp")
        val seccompOnly = originalScript.replace(anchor,
            "    # test-only: omit Landlock\n    syscalls()")
        val permissiveIoctl = seccompOnly
            .replace("ioctl_nr = 16", "ioctl_nr = -1")
            .replace("ioctl_nr = 29", "ioctl_nr = -1")
        val fionbioIoctl = seccompOnly.replace(
            "0x5414, 0x541B]", "0x5414, 0x541B, 0x5421]")
        val standardIoctl = seccompOnly.replace(
            "0x5414, 0x541B]", "0x5414, 0x541B, 0x5421, 0x5450, 0x5451]")
        val allowSocketpair = seccompOnly
            .replace("49, 50, 53, 54, 55, 62", "49, 50, 54, 55, 62")
            .replace("198, 199, 200, 201", "198, 200, 201")
        val landlockNoDeviceIoctl = landlockOnly.replace(
            "handled = (1 << (16 if abi >= 5 else 15)) - 1",
            "handled = ((1 << (16 if abi >= 5 else 15)) - 1) & ~(1 << 15)")
        // Targeted test matrix. This never runs caller-provided code and lives
        // exclusively in androidTest; production always installs both policies.
        val modes = listOf(
            Triple("仅 Landlock", landlockOnly, originalArgs[6]),
            Triple("仅 Seccomp", seccompOnly, originalArgs[6]),
            Triple("Landlock 允许全量只读", landlockOnly, allReadPolicy),
            Triple("Landlock 读取 data", landlockOnly, policyWithExtraRead("/data")),
            Triple("Landlock 读取 dev", landlockOnly, policyWithExtraRead("/dev")),
            Triple("Landlock 读取 random", landlockOnly, policyWithExtraRead("/dev/random")),
            Triple("Landlock 读取 zero", landlockOnly, policyWithExtraRead("/dev/zero")),
            Triple("Landlock 读取 random+zero", landlockOnly,
                policyWithExtraRead("/dev/random", "/dev/zero")),
            Triple("Landlock 读取 ptmx", landlockOnly, policyWithExtraRead("/dev/ptmx")),
            Triple("Landlock 读取 proc", landlockOnly, policyWithExtraRead("/proc")),
            Triple("Landlock 读取 sys", landlockOnly, policyWithExtraRead("/sys")),
            Triple("Landlock 读取 mnt", landlockOnly, policyWithExtraRead("/mnt")),
            Triple("Landlock 读取 odm", landlockOnly, policyWithExtraRead("/odm")),
            Triple("Landlock 不管控设备 ioctl", landlockNoDeviceIoctl, originalArgs[6]),
            Triple("Seccomp 允许 ioctl", permissiveIoctl, originalArgs[6]),
            Triple("Seccomp 允许 FIONBIO", fionbioIoctl, originalArgs[6]),
            Triple("Seccomp 允许 FIONBIO 与 FIOCLEX", standardIoctl, originalArgs[6]),
            Triple("Seccomp 允许 socketpair", allowSocketpair, originalArgs[6]),
        )
        return modes.map { (name, script, policy) ->
            val diagnostic = originalArgs.toMutableList().apply {
                this[5] = script
                this[6] = policy
            }
            val builder = ProcessBuilder(diagnostic).directory(workspace).apply {
                environment().clear()
                environment().putAll(original.environment())
            }
            val result = executeManagedProcess(builder, timeoutMillis = 15_000L)
            "$name: exit=${result.exitCode}, timeout=${result.timedOut}, stdout=${result.stdout.take(1000)}, stderr=${result.stderr.take(1800)}"
        }.joinToString("\n")
    }
}
