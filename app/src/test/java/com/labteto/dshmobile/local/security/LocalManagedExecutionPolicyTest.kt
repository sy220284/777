package com.labteto.dshmobile.local.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalManagedExecutionPolicyTest {
    @Test fun managedRestrictionsCannotBeBroadenedByWorkspaceOrUser() {
        val effective = mergeLocalExecutionPolicy(
            user = LocalPolicyLayer(
                allowedHosts = setOf("api.deepseek.com", "api.example.com"),
                browserUploadAllowed = true,
            ),
            workspace = LocalPolicyLayer(
                allowedHosts = setOf("api.deepseek.com", "other.example.com"),
                deniedHosts = setOf("bad.example.com"),
            ),
            managed = LocalPolicyLayer(
                allowedHosts = setOf("api.deepseek.com"),
                deniedHosts = setOf("api.example.com"),
                allowedModelProviders = setOf("deepseek"),
                browserUploadAllowed = false,
            ),
        )
        assertEquals(setOf("api.deepseek.com"), effective.allowedHosts)
        assertTrue("network.allowedHosts" in effective.managedFields)
        effective.requireModelProvider("DeepSeek", "https://api.deepseek.com")
        assertFails { effective.requireModelProvider("other", "https://api.deepseek.com") }
        assertFails { effective.requireNetworkDestination("https", "api.example.com") }
        assertFails { effective.requireNetworkDestination("https", "other.example.com") }
        assertFails { effective.requireBrowserOrigin("https", "api.deepseek.com", "upload") }
        effective.requireBrowserOrigin("https", "api.deepseek.com", "read")
    }

    @Test fun emptyPolicyKeepsExistingHttpRoutesButRestrictsBrowserActions() {
        val effective = mergeLocalExecutionPolicy()
        effective.requireNetworkDestination("https", "api.openai.com")
        effective.requireNetworkDestination("http", "127.0.0.1")
        effective.requireNetworkDestination("http", "::1")
        assertFails { effective.requireBrowserOrigin("https", "example.com", "control") }
        assertFails { effective.requireBrowserOrigin("https", "example.com", "history") }
    }

    @Test fun managedNetworkDisableWinsOverUserAndMalformedPolicyFails() {
        val layer = parseLocalPolicyLayer(
            """{"network":{"enabled":false,"deniedHosts":["BAD.EXAMPLE.COM"]},"browser":{"control":false}}""",
        )
        val effective = mergeLocalExecutionPolicy(
            user = LocalPolicyLayer(networkEnabled = true),
            managed = layer,
        )
        assertFalse(effective.networkEnabled)
        assertFails { effective.requireNetworkDestination("https", "api.openai.com") }
        assertFails { parseLocalPolicyLayer("""{"network":{"enabled":"yes"}}""") }
        assertFails { parseLocalPolicyLayer("""{"unknown":1}""") }
        assertFails { parseLocalPolicyLayer("""{"network":{"allowedHosts":["https://evil.com"]}}""") }
    }

    private inline fun assertFails(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalArgumentException) { failed = true }
        catch (_: IllegalStateException) { failed = true }
        assertTrue("策略应拒绝越权操作", failed)
    }
}
