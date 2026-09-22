package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledPythonRuntimeTest {
    @Test
    fun bundledPythonRunsStdlibAndAndroidShellOnAndroid16() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BundledPythonRuntime(context)
        runtime.prepare()

        val python = File(runtime.binDir, "python3")
        assertTrue("python3 command link is missing", python.exists())

        val script = """
            import bz2
            import lzma
            import sqlite3
            import ssl
            import subprocess
            import sys
            shell = subprocess.check_output("printf shell-ok", shell=True, text=True)
            sys.stdout.write(sys.version.split()[0] + "|" + shell)
        """.trimIndent()

        val process = ProcessBuilder(python.absolutePath, "-c", script)
            .apply { environment().putAll(runtime.environment()) }
            .redirectErrorStream(true)
            .start()

        assertTrue("bundled Python timed out", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

        assertEquals("bundled Python failed:\n$output", 0, process.exitValue())
        assertTrue("unexpected Python version: $output", output.startsWith("3.14.6|"))
        assertTrue("Python subprocess shell failed: $output", output.endsWith("shell-ok"))
    }
}
