package com.labteto.dshmobile.local.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalNetworkPermissionPolicyTest {
    @Test fun android16AndLegacyTargetNeverRequestNewPermission() {
        val local = "http://192.168.1.10:8000/mcp"
        assertFalse(requiresAndroid17LocalNetworkPermission(36, 36, local))
        assertFalse(requiresAndroid17LocalNetworkPermission(36, 37, local))
        assertFalse(requiresAndroid17LocalNetworkPermission(37, 36, local))
        assertTrue(requiresAndroid17LocalNetworkPermission(37, 37, local))
    }

    @Test fun localNetworkHostsAreClassifiedWithoutDnsOrExternalTraffic() {
        listOf(
            "http://10.3.2.1:8080",
            "https://172.16.1.1/mcp",
            "https://172.31.255.254",
            "https://192.168.0.20",
            "http://169.254.1.1",
            "http://100.100.10.1",
            "http://printer.local:80",
            "http://nas.home.arpa",
            "http://router.lan",
            "http://nas",
            "http://[fd00::1234]:9000",
            "http://[fe80::1]:9000",
            "http://[fc00:1234::2]",
            "http://[ff02::fb]:5353",
            "http://[::ffff:192.168.1.5]:8080",
            "http://[::ffff:10.2.3.4]:8080",
        ).forEach { endpoint ->
            assertTrue(endpoint, isLikelyLocalNetworkEndpoint(endpoint))
        }
    }

    @Test fun publicAndLoopbackConnectionsNeverPromptImplicitly() {
        listOf(
            "https://api.github.com",
            "https://mcp.example.com",
            "https://8.8.8.8",
            "http://127.0.0.1:9000",
            "http://localhost:10101",
            "http://[::1]:33200",
            "http://[::ffff:127.0.0.1]:8888",
            "http://[::ffff:8.8.8.8]:8888",
            "https://[2001:4860:4860::8888]/dns-query",
            "https://[2606:4700:4700::1111]",
            "https://[2001:db8::1]",
            "https://192.167.1.1",
            "not a URL",
        ).forEach { endpoint ->
            assertFalse(endpoint, isLikelyLocalNetworkEndpoint(endpoint))
        }
    }
}
