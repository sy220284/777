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
        LocalMcpNetworkAdmission("http", "https://trusted.example.com/mcp").requireAllowed(managed)
        assertDenied { LocalMcpNetworkAdmission("http", "http://trusted.example.com/mcp").requireAllowed(managed) }
        assertDenied { LocalMcpNetworkAdmission("http", "https://other.example.com/mcp").requireAllowed(managed) }
        assertDenied { LocalMcpNetworkAdmission("http", "https://user@trusted.example.com/mcp").requireAllowed(managed) }
        assertDenied { LocalMcpNetworkAdmission("http", "https://trusted.example.com/mcp#fragment").requireAllowed(managed) }
    }

    @Test fun domainListsDoNotBlockLocalStdioToolsButNetworkOffDoes() {
        val unrestricted = mergeLocalExecutionPolicy()
        LocalMcpNetworkAdmission("stdio", null).requireAllowed(unrestricted)
        val disabled = mergeLocalExecutionPolicy(managed = LocalPolicyLayer(networkEnabled = false))
        assertDenied { LocalMcpNetworkAdmission("stdio", null).requireAllowed(disabled) }
        val constrained = mergeLocalExecutionPolicy(
            managed = LocalPolicyLayer(deniedHosts = setOf("bad.example.com")),
        )
        LocalMcpNetworkAdmission("stdio", null).requireAllowed(constrained)
        val allowed = mergeLocalExecutionPolicy(
            managed = LocalPolicyLayer(allowedHosts = setOf("trusted.example.com")),
        )
        LocalMcpNetworkAdmission("stdio", null).requireAllowed(allowed)
    }

    @Test fun onlyLoopbackAllowsPlainHttp() {
        val unrestricted = mergeLocalExecutionPolicy()
        LocalMcpNetworkAdmission("http", "http://localhost:7777/mcp").requireAllowed(unrestricted)
        assertDenied { LocalMcpNetworkAdmission("http", "http://192.0.2.10/mcp").requireAllowed(unrestricted) }
    }

    private inline fun assertDenied(action: () -> Unit) {
        var denied = false
        try { action() } catch (_: IllegalArgumentException) { denied = true }
        catch (_: IllegalStateException) { denied = true }
        assertTrue("违规访问必须被拒绝", denied)
    }
}
