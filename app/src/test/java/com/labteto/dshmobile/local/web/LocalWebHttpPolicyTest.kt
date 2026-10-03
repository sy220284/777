package com.labteto.dshmobile.local.web

import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWebHttpPolicyTest {
    @Test
    fun unboundedSourceReadsOnlyTheLimitPlusOneAndReportsTruncation() {
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int { reads++; return 42 }
        }
        val result = readBoundedWebBody(source, LOCAL_WEB_MAX_RESPONSE_BYTES)
        assertTrue(result.truncated)
        assertEquals(LOCAL_WEB_MAX_RESPONSE_BYTES, result.bytes.size)
        assertEquals(LOCAL_WEB_MAX_RESPONSE_BYTES + 1, reads)
    }

    @Test
    fun exactBoundaryAndEmptyBodiesAreNotTruncated() {
        val bytes = byteArrayOf(1, 2, 3)
        val exact = readBoundedWebBody(bytes.inputStream(), 3)
        assertFalse(exact.truncated)
        assertArrayEquals(bytes, exact.bytes)
        val empty = readBoundedWebBody(byteArrayOf().inputStream(), 3)
        assertFalse(empty.truncated)
        assertEquals(0, empty.bytes.size)
    }

    @Test
    fun retriesKeepDnsAndCertificateFailuresTerminal() {
        assertFalse(isRetryableWebTransportFailure(UnknownHostException()))
        assertFalse(isRetryableWebTransportFailure(SSLPeerUnverifiedException("untrusted")))
        assertTrue(isRetryableWebTransportFailure(SocketTimeoutException()))
    }
}
