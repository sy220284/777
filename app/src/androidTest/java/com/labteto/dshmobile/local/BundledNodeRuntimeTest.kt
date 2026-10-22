package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.runtime.BundledNodeRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import javax.net.ssl.TrustManagerFactory
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
        val caPath = runtime.environment()["NODE_EXTRA_CA_CERTS"]
        assertTrue("Node must reuse Android trusted CA roots", !caPath.isNullOrBlank())
        val caFile = File(requireNotNull(caPath))
        assertTrue("Node CA file missing", caFile.isFile)
        val pem = caFile.readText()
        assertTrue("Node CA file must contain platform certificates", pem.contains("-----BEGIN CERTIFICATE-----"))
        assertFalse("Never disable Node TLS verification", runtime.environment().containsKey("NODE_TLS_REJECT_UNAUTHORIZED"))
        assertTrue("Android trust manager must be available",
            TrustManagerFactory.getDefaultAlgorithm().isNotBlank())

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
        assertTrue("unexpected Node version: $output", output.startsWith("v26.12.1|"))
        assertTrue("Node child_process shell failed: $output", output.endsWith("shell-ok"))
    }
}
