package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class HostEndpointUrlTest {
    @Test
    fun `url authorities bracket ipv6 literals and preserve normal hosts`() {
        assertEquals("192.168.1.20:3080", urlAuthority("192.168.1.20", 3080))
        assertEquals("agent.home:443", urlAuthority("agent.home", 443))
        assertEquals("[::1]:3080", urlAuthority("::1", 3080))
    }

    @Test
    fun `base urls use the endpoint tls mode`() {
        assertEquals("http://192.168.1.20:3080", harnessBaseUrl("192.168.1.20", 3080, useTls = false))
        assertEquals("https://agent.home:443", harnessBaseUrl("agent.home", 443, useTls = true))
        assertEquals("http://[::1]:3080", harnessBaseUrl("::1", 3080, useTls = false))
    }
}
