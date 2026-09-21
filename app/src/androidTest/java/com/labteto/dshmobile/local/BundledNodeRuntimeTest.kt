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
class BundledNodeRuntimeTest {
    @Test
    fun bundledNodeRunsJavaScriptAndChildProcessOnAndroid16() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BundledNodeRuntime(context)
        runtime.prepare()

        val node = File(runtime.binDir, "node")
        assertTrue("node command link is missing", node.exists())

        val process = ProcessBuilder(
            node.absolutePath,
            "-e",
            "const c=require('child_process');process.stdout.write(process.version+'|'+c.execSync('printf shell-ok',{encoding:'utf8'}))",
        )
            .apply { environment().putAll(runtime.environment()) }
            .redirectErrorStream(true)
            .start()

        assertTrue("bundled Node timed out", process.waitFor(20, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

        assertEquals(0, process.exitValue())
        assertTrue("unexpected Node version: $output", output.startsWith("v24.18.0|"))
        assertTrue("Node child_process shell failed: $output", output.endsWith("shell-ok"))
    }
}
