package com.labteto.dshmobile.local.network

import java.net.URI
import java.util.Locale

/**
 * Android 17 enforces ACCESS_LOCAL_NETWORK only for targetSdk 37+.
 * Do not request or declare the permission while still targeting 36.
 *
 * This is deliberately a literal-host hint for user-initiated connections,
 * not a DNS resolver or a security boundary: public DNS names may still
 * resolve to private addresses. The UI also offers a manual permission path.
 */
internal fun requiresAndroid17LocalNetworkPermission(
    deviceApi: Int,
    targetSdk: Int,
    endpoint: String,
): Boolean = deviceApi >= 37 && targetSdk >= 37 && isLikelyLocalNetworkEndpoint(endpoint)

internal fun isLikelyLocalNetworkEndpoint(endpoint: String): Boolean {
    val host = runCatching { URI(endpoint.trim()).host }.getOrNull()
        ?.lowercase(Locale.ROOT)?.trim('[', ']')?.removeSuffix(".") ?: return false
    if (host == "localhost" || host == "::1" || host.startsWith("127.")) return false
    if (host.endsWith(".local") || host.endsWith(".lan") ||
        host.endsWith(".home.arpa") || '.' !in host && ':' !in host) return true

    if (':' in host) {
        // IPv4-mapped IPv6 literals must receive the same LAN treatment as IPv4.
        // For example, ::ffff:192.168.1.5 reaches the LAN on Android 17.
        if ((host.startsWith("::ffff:") || host.startsWith("0:0:0:0:0:ffff:")) && '.' in host) {
            return isLikelyLocalNetworkEndpoint("http://" + host.substringAfterLast(':'))
        }
        // Only classify IPv6 unique-local (fc00::/7), link-local (fe80::/10),
        // site-local (deprecated fec0::/10) and local multicast (ff0x::/16).
        // Public global IPv6 must not trigger an unnecessary permission prompt.
        val firstHextet = host.substringBefore(':').toIntOrNull(16) ?: return false
        return firstHextet in 0xfc00..0xfdff ||
            firstHextet in 0xfe80..0xfebf ||
            firstHextet in 0xfec0..0xfeff ||
            firstHextet in 0xff01..0xff05
    }

    val segments = host.split('.')
    if (segments.size != 4 || segments.any { it.toIntOrNull()?.let { octet -> octet in 0..255 } != true }) {
        return false
    }
    val first = segments[0].toInt()
    val second = segments[1].toInt()
    return first == 10 ||
        first == 172 && second in 16..31 ||
        first == 192 && second == 168 ||
        first == 169 && second == 254 ||
        first == 100 && second in 64..127
}
