package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelConnectionTesterTest {
    @Test fun diagnosesFailuresUsingStableCategories() {
        assertEquals("dns_failure", modelProbeFailureKind(UnknownHostException("dns")))
        assertEquals("tls_failure", modelProbeFailureKind(SSLHandshakeException("certificate")))
        assertEquals("invalid_credentials", modelProbeFailureKind(
            LocalModelException("MODEL_HTTP_401", "invalid", false, status = 401)))
        assertEquals("provider_forbidden", modelProbeFailureKind(
            LocalModelException("MODEL_HTTP_403", "forbidden", false, status = 403)))
        assertEquals("provider_rate_limited", modelProbeFailureKind(
            LocalModelException("MODEL_HTTP_429", "limit", true, status = 429)))
        assertEquals("protocol_mismatch", modelProbeFailureKind(
            LocalModelException("MODEL_PROTOCOL_INPUT_UNSUPPORTED", "file", false)))
    }
}
