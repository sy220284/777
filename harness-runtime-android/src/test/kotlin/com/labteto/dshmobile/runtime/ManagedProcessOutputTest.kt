package com.labteto.dshmobile.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessOutputTest {
    @Test fun sandboxProcSelfLinksRemainRelativeToTheChildProcess() {
        assertEquals("/proc/self", isolationRootPath(java.io.File("/proc/self")))
        assertEquals("/proc/thread-self", isolationRootPath(java.io.File("/proc/thread-self")))
        // Ordinary paths still resolve canonically; only kernel-relative links
        // must be deferred until the isolated child installs Landlock.
        assertEquals(java.io.File("/tmp").canonicalPath, isolationRootPath(java.io.File("/tmp")))
    }
    @Test fun isolatedPackageManagersUseOnlyWorkspaceWriteRoots() {
        val workspace = File("/tmp/workspace")
        val environment = isolatedChildRuntimeEnvironment(workspace, mapOf(
            "PIP_TARGET" to "/app/private/python-packages",
            "PIP_CACHE_DIR" to "/app/cache/pip",
            "PYTHONPATH" to "/app/python/site-packages",
            "npm_config_cache" to "/app/cache/npm",
        ))
        val prefix = File(workspace, ".dsh/runtime-packages").path + File.separator
        for (key in listOf("PIP_TARGET", "PIP_CACHE_DIR", "npm_config_cache", "npm_config_prefix")) {
            assertTrue("$key must remain writable under the sandbox root",
                environment.getValue(key).startsWith(prefix))
        }
        assertTrue("sandbox-installed packages must win Python import precedence",
            environment.getValue("PYTHONPATH").startsWith(environment.getValue("PIP_TARGET")))
    }

    @Test fun childSecretsAreStagedInEnvironmentNeverAsProcessArguments() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("isolated-env").toFile()
        try {
            File(root, "python3").apply {
                writeText("#!/bin/sh\nexit 0\n")
                assertTrue(setExecutable(true))
            }
            val builder = ProcessBuilder("npm", "--version").directory(root).apply {
                environment().clear()
                environment()["API_TOKEN"] = "private-token-value"
                environment()["LD_PRELOAD"] = "/user/should-not-load.so"
                environment()["PIP_TARGET"] = "/private/outside"
                environment()["npm_config_cache"] = "/private/cache"
            }
            withContext(ProcessIsolationContext()) {
                secureIsolatedProcessBuilder(builder, listOf(root), emptyMap())
            }
            val argv = builder.command()
            assertTrue("sandbox helper must be the child command", argv.contains("-c"))
            assertFalse("secret values must not enter process argv",
                argv.any { it.contains("private-token-value") || it.contains("/user/should-not-load.so") })
            assertFalse("untrusted preload must be inert until after isolation",
                builder.environment().containsKey("LD_PRELOAD"))
            val childNames = kotlinx.serialization.json.Json.parseToJsonElement(argv.last())
                .let { it as kotlinx.serialization.json.JsonArray }
                .map { it.toString().trim('"') }
            val tokenIndex = childNames.indexOf("API_TOKEN")
            assertTrue(tokenIndex >= 0)
            assertEquals("private-token-value", builder.environment()["DSH_SANDBOX_ENV_$tokenIndex"])
            val preloadIndex = childNames.indexOf("LD_PRELOAD")
            assertEquals("/user/should-not-load.so", builder.environment()["DSH_SANDBOX_ENV_$preloadIndex"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun keepsSupplementaryCodePointIntact() {
        assertEquals("A", truncateProcessOutput("A\uD83D\uDE00B", 2))
        assertEquals("A\uD83D\uDE00", truncateProcessOutput("A\uD83D\uDE00B", 3))
        assertEquals("A\uD83D\uDE00B", truncateProcessOutput("A\uD83D\uDE00B", 9))
        assertEquals("", truncateProcessOutput("A", 0))
    }
    @Test fun systemLauncherDefersAllDynamicLinkerOverridesUntilAfterPidHandshake() {
        val builder = ProcessBuilder("/work/cli", "--version").apply {
            environment()["LD_LIBRARY_PATH"] = "/app/runtime/lib:/app/native"
            environment()["LD_PRELOAD"] = "/app/runtime/preload.so"
            environment()["LD_AUDIT"] = "/app/runtime/audit.so"
            environment()["API_TOKEN"] = "secret stays in environment"
        }
        prepareManagedProcessLauncher(builder, "/system/bin/setsid", "/system/bin/sh")
        val command = builder.command()
        assertEquals(listOf("/system/bin/setsid", "/system/bin/sh", "-c"),
            command.take(3))
        assertEquals(listOf("/work/cli", "--version"), command.takeLast(2))
        val script = command[3]
        assertTrue(script.startsWith("echo \"\$\$\""))
        assertTrue("PID handshake must precede linker restoration",
            script.indexOf("echo ") == 0 &&
                script.indexOf("export LD_LIBRARY_PATH") > script.indexOf("echo "))
        assertTrue(script.endsWith("exec " + '"' + '$' + "@\""))
        for (key in listOf("LD_LIBRARY_PATH", "LD_PRELOAD", "LD_AUDIT")) {
            assertFalse("$key must not affect the platform setsid/sh linker",
                builder.environment().containsKey(key))
            assertTrue(builder.environment().containsKey("DSH_MANAGED_$key"))
            assertTrue(script.contains("unset DSH_MANAGED_$key"))
            assertTrue("必须从暂存变量恢复 $key",
                script.contains("export $key=\"\$DSH_MANAGED_$key\""))
        }
        assertEquals("secret stays in environment", builder.environment()["API_TOKEN"])
        assertFalse("Credentials must never enter a command argument",
            command.any { it.contains("secret stays in environment") })
    }

    @Test fun childReceivesRestoredLibraryPathAndSecretsStayOutOfArguments() {
        val builder = ProcessBuilder("/usr/bin/env").apply {
            environment().clear()
            environment()["LD_LIBRARY_PATH"] = "/trusted/runtime/lib:/trusted/native"
            environment()["API_TOKEN"] = "secret-check-value"
        }
        prepareManagedProcessLauncher(builder, "/usr/bin/setsid", "/bin/sh")
        // Execute the real generated shell script without session wrapping; the
        // JVM test checks value restoration, while Android instrumentation covers setsid.
        val command = builder.command()
        val child = ProcessBuilder(command.drop(1)).apply {
            environment().clear()
            environment().putAll(builder.environment())
        }.start()
        val stdout = child.inputStream.bufferedReader().readText()
        val stderr = child.errorStream.bufferedReader().readText()
        assertEquals("restore failed: $stderr", 0, child.waitFor())
        assertTrue(stdout.lines().contains("LD_LIBRARY_PATH=/trusted/runtime/lib:/trusted/native"))
        assertTrue(stdout.lines().contains("API_TOKEN=secret-check-value"))
        assertFalse(stdout.contains("DSH_MANAGED_LD_LIBRARY_PATH="))
        assertFalse(command.any { it.contains("secret-check-value") })
    }
    @Test fun processThatExitedBeforePollingStillYieldsBufferedPid() {
        // Android's process pipe may report available() == 0 after a fast
        // executable exits even while its handshake header remains readable.
        val pipe = object : ByteArrayInputStream("4242\nversion".toByteArray()) {
            override fun available() = 0
        }
        val exited = object : Process() {
            override fun getInputStream(): InputStream = pipe
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() = Unit
            override fun isAlive() = false
        }
        assertEquals(4242L, readManagedProcessGroup(exited))
        assertEquals("version", pipe.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun failedLauncherKeepsItsRealStderrInsteadOfGenericHandshakeError() {
        val exited = object : Process() {
            override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getErrorStream(): InputStream =
                ByteArrayInputStream("CANNOT LINK EXECUTABLE: libexample.so missing".toByteArray())
            override fun waitFor() = 127
            override fun exitValue() = 127
            override fun destroy() = Unit
            override fun isAlive() = false
        }
        val error = runCatching { readManagedProcessGroup(exited) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("退出码 127"))
        assertTrue(error?.message.orEmpty().contains("libexample.so missing"))
    }
}
