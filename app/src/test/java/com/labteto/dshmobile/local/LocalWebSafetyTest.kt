package com.labteto.dshmobile.local

import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebSafetyTest {
    @Test
    fun proxyPinnedUriKeepsPathAndQueryButUsesValidatedIp() {
        val original = URI("https://example.com/path/to?q=1")
        val pinned = pinUriToAddress(original, InetAddress.getByName("93.184.216.34"))

        assertEquals("https", pinned.scheme)
        assertEquals("93.184.216.34", pinned.host)
        assertEquals("/path/to", pinned.path)
        assertEquals("q=1", pinned.query)
    }

    @Test
    fun connectivityProbeDistinguishesProxyGatewayFailureFromReachableOrigin() {
        assertFalse(classifyProbeStatus(407).first)
        assertFalse(classifyProbeStatus(502).first)
        assertFalse(classifyProbeStatus(504).first)
        assertTrue(classifyProbeStatus(403).first)
        assertTrue(classifyProbeStatus(500).first)
    }

    @Test
    fun webFetchRejectsBinaryMediaTypes() {
        assertTrue(isTextualWebMediaType("text/html; charset=utf-8"))
        assertTrue(isTextualWebMediaType("application/problem+json"))
        assertTrue(isTextualWebMediaType("application/rss+xml"))
        assertFalse(isTextualWebMediaType("application/pdf"))
        assertFalse(isTextualWebMediaType("image/png"))
        assertFalse(isTextualWebMediaType("application/octet-stream"))
    }
}
