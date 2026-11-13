package com.labteto.dshmobile.local.tools.ipc

import com.labteto.dshmobile.local.security.LocalPolicyLayer
import com.labteto.dshmobile.local.security.mergeLocalExecutionPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMcpNetworkAdmissionTest {
    @Test fun remoteHttpRequiresTlsAndVerifiedDomain() {
        val managed = mergeLocalExecutionPolicy(
            managed = LocalPolicyLayer(allowedHosts = setOf("trusted.example.com")),
        )
        requireMcpWorkerNetworkAdmission(managed, "http", "https://trusted.example.com/mcp")
        assertDenied { requireMcpWorkerNetworkAdmission(managed, "http", "http://trusted.example.com/mcp") }
        assertDenied { requireMcpWorkerNetworkAdmission(managed, "http", "https://other.example.com/mcp") }
        assertDenied { requireMcpWorkerNetworkAdmission(managed, "http", "https://user@trusted.example.com/mcp") }
        assertDenied { requireMcpWorkerNetworkAdmission(managed, "http", "https://trusted.example.com/mcp#fragment") }
    }

    @Test fun domainListsDoNotBlockLocalStdioToolsButNetworkOffDoes() {
        val unrestricted = mergeLocalExecutionPolicy()
        requireMcpWorkerNetworkAdmission(unrestricted, "stdio", null)
        val disabled = mergeLocalExecutionPolicy(managed = LocalPolicyLayer(networkEnabled = false))
        assertDenied { requireMcpWorkerNetworkAdmission(disabled, "stdio", null) }
        val constrained = mergeLocalExecutionPolicy(
            managed = LocalPolicyLayer(deniedHosts = setOf("bad.example.com")),
        )
        requireMcpWorkerNetworkAdmission(constrained, "stdio", null)
        val allowed = mergeLocalExecutionPolicy(
            managed = LocalPolicyLayer(allowedHosts = setOf("trusted.example.com")),
        )
        requireMcpWorkerNetworkAdmission(allowed, "stdio", null)
    }

    @Test fun onlyLoopbackAllowsPlainHttp() {
        val unrestricted = mergeLocalExecutionPolicy()
        requireMcpWorkerNetworkAdmission(unrestricted, "http", "http://localhost:7777/mcp")
        assertDenied { requireMcpWorkerNetworkAdmission(unrestricted, "http", "http://192.0.2.10/mcp") }
    }

    private inline fun assertDenied(action: () -> Unit) {
        var denied = false
        try { action() } catch (_: IllegalArgumentException) { denied = true }
        catch (_: IllegalStateException) { denied = true }
        assertTrue("违规访问必须被拒绝", denied)
    }
}
