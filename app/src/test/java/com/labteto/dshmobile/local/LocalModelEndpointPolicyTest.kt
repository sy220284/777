package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelEndpointPolicyTest {
    @Test fun httpsRemoteEndpointIsAccepted() {
        assertEquals("https://api.example.com/v1", normalizeModelBaseUrl(" https://api.example.com/v1/ "))
    }
    @Test fun loopbackHttpIsAccepted() {
        assertEquals("http://127.0.0.1:8080/v1", normalizeModelBaseUrl("http://127.0.0.1:8080/v1"))
        assertEquals("http://localhost:8080/v1", normalizeModelBaseUrl("http://localhost:8080/v1"))
    }
    @Test fun remoteHttpIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { normalizeModelBaseUrl("http://example.com/v1") }
    }
    @Test fun embeddedCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { normalizeModelBaseUrl("https://user:pass@example.com/v1") }
    }
}
