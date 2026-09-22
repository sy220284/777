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
            import os
            import sqlite3
            import ssl
            import subprocess
            import sys
            cert = os.environ["SSL_CERT_FILE"]
            assert os.path.isfile(cert)
            ca_count = len(ssl.create_default_context().get_ca_certs())
            assert ca_count > 0
            shell = subprocess.check_output("printf shell-ok", shell=True, text=True)
            sys.stdout.write(sys.version.split()[0] + "|" + str(ca_count) + "|" + shell)
        """.trimIndent()

        val process = ProcessBuilder(python.absolutePath, "-c", script)
            .apply { environment().putAll(runtime.environment()) }
            .redirectErrorStream(true)
            .start()

        assertTrue("bundled Python timed out", process.waitFor(30, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

        val diagnostic = output.replace("\r", "\\r").replace("\n", "\\n")
        assertEquals("bundled Python failed: $diagnostic", 0, process.exitValue())
        val parts = output.split("|")
        assertEquals("unexpected Python output: $output", 3, parts.size)
        assertEquals("3.14.6", parts[0])
        assertTrue("Python CA store is empty: $output", parts[1].toIntOrNull()?.let { it > 0 } == true)
        assertEquals("shell-ok", parts[2])
    }
}
