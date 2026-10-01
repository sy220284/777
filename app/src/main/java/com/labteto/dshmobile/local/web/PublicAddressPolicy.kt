package com.labteto.dshmobile.local

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Conservative public-unicast policy shared by every validated web destination. */
internal object PublicAddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (address is Inet4Address) {
            val a = b[0]; val c = b[1]; val d = b[2]
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false
            if (a == 100 && c in 64..127 || a == 169 && c == 254 || a == 172 && c in 16..31) return false
            if (a == 192 && (c == 168 || c == 0 && d in listOf(0, 2) || c == 88 && d == 99)) return false
            if (a == 198 && (c in 18..19 || c == 51 && d == 100)) return false
            if (a == 203 && c == 0 && d == 113) return false
            return true
        }
        if (address is Inet6Address) {
            // Only global unicast; excludes translation, mapped, local and multicast ranges.
            if (b[0] and 0xe0 != 0x20) return false
            if (b[0] == 0x20 && b[1] == 1 && b[2] <= 1) return false // protocol assignments /23
            if (b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && b[3] == 0xb8) return false
            if (b[0] == 0x20 && b[1] == 2) return false // 6to4
            if (b[0] == 0x3f && b[1] == 0xff && b[2] and 0xf0 == 0) return false
            return true
        }
        return false
    }
}
