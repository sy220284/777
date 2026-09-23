package com.labteto.dshmobile.local

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicAddressPolicyTest {
    @Test fun documentationAndProtocolAddressesAreNotPublicDestinations() {
        for (literal in listOf("192.0.0.1", "192.0.2.1", "192.88.99.1", "198.51.100.1",
            "203.0.113.1", "198.18.0.1", "100.64.0.1", "2001:db8::1", "2002::1",
            "2001:2::1", "3fff::1", "64:ff9b::1", "fc00::1", "::1")) {
            assertFalse(literal, PublicAddressPolicy.isPublic(InetAddress.getByName(literal)))
        }
    }
    @Test fun publicUnicastAddressesRemainAllowed() {
        for (literal in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888")) {
            assertTrue(literal, PublicAddressPolicy.isPublic(InetAddress.getByName(literal)))
        }
    }
}
