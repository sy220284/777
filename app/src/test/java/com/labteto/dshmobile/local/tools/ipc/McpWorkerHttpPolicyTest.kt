package com.labteto.dshmobile.local.tools.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpWorkerHttpPolicyTest {
    @Test
    fun readIdleBudgetExceedsToolDeadlineWithoutAutomaticMutationReplay() {
        val client = createMcpWorkerHttpClient()
        assertEquals(75_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
