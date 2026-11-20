package com.labteto.dshmobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessOutputTest {
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
        assertTrue(script.startsWith("printf '%s\\n'"))
        assertTrue(script.indexOf("printf") < script.indexOf("export LD_LIBRARY_PATH"))
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
}
