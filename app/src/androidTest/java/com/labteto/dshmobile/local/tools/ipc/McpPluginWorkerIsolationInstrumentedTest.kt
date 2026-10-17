package com.labteto.dshmobile.local.tools.ipc

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises a real bound Android service across Binder, not an in-process fake.
 * The loopback port is deliberately closed: no user credentials, network server or
 * paid/irreversible tool operations are used by this crash/fault injection test.
 */
@RunWith(AndroidJUnit4::class)
class McpPluginWorkerIsolationInstrumentedTest {
    @Test fun isolatedProcessDeathEmitsOneTerminalRevocationWithoutReconnectOrReplay() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val client = McpPluginWorkerTransport(app, kind = "http",
            endpoint = "http://127.0.0.1:1")
        try {
            // Even a failed tool ping must first open and attest to a different PID.
            val call = runCatching {
                withTimeout(15_000) {
                    client.request("ping", kotlinx.serialization.json.JsonObject(emptyMap()))
                }
            }
            assertTrue(call.isFailure)
            val pid = client.workerPid
            assertNotNull("Binder handshake must prove the separate worker exists", pid)
            assertNotEquals(Process.myPid(), pid)
            Process.killProcess(requireNotNull(pid))
            val signal = withTimeout(15_000) { client.terminalEvents.first() }
            assertTrue(signal is IOException)
            assertTrue(runCatching {
                client.request("tools/call", kotlinx.serialization.json.JsonObject(emptyMap()))
            }.isFailure)
        } finally {
            client.close()
        }
    }
}
