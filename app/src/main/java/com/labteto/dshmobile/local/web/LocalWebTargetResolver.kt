package com.labteto.dshmobile.local.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.labteto.dshmobile.local.LocalWebException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Owns URL normalization, DNS/SSRF validation and pinned transport routing. */
internal class LocalWebTargetResolver(
    private val context: Context,
    private val http: OkHttpClient,
) {
    fun validateTarget(input: String): ValidatedTarget {
        val normalized = normalizeInput(input)
        val uri = parseUri(normalized)
        val host = requireNotNull(uri.host)
        val addresses = resolve(host)
        val vpn = isVpnActive()
        val blocked = addresses.filterNot { isPublicAddress(it) || isAllowedVpnFakeAddress(host, it, vpn) }
        if (blocked.isNotEmpty()) {
            throw LocalWebException(
                "SSRF_BLOCKED",
                buildString {
                    append("目标域名被解析为受保护地址：$host → ")
                    append(blocked.joinToString { it.hostAddress ?: it.toString() })
                    append("。")
                    append(blockedHint(host, blocked, vpn))
                },
            )
        }
        return ValidatedTarget(uri, addresses, vpn)
    }

    fun parseUri(input: String): URI {
        val uri = runCatching { URI(input.trim()) }.getOrElse {
            throw LocalWebException("INVALID_URL", "网址格式不正确")
        }
        if (uri.scheme != "https" && uri.scheme != "http") {
            throw LocalWebException("INVALID_URL", "仅允许 HTTP/HTTPS 地址")
        }
        if (uri.host == null || uri.userInfo != null) {
            throw LocalWebException("INVALID_URL", "网址缺少有效主机或包含用户信息")
        }
        if (uri.port != -1 && uri.port !in setOf(80, 443)) {
            throw LocalWebException("SSRF_BLOCKED", "网页获取只允许 80 或 443 端口")
        }
        return uri
    }

    fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching {
            val uri = URI(candidate)
            if (uri.host.equals("github.com", ignoreCase = true) && uri.path.endsWith(".git")) {
                URI(uri.scheme, uri.authority, uri.path.removeSuffix(".git"), uri.query, uri.fragment).toString()
            } else candidate
        }.getOrDefault(candidate)
    }

    fun resolve(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).toList().also {
            if (it.isEmpty()) throw UnknownHostException(host)
        }
    } catch (error: UnknownHostException) {
        throw LocalWebException("DNS_FAILED", "无法解析域名 $host", error)
    }

    fun requestRoute(target: ValidatedTarget, timeoutSeconds: Long): RequestRoute {
        val timeout = timeoutSeconds.coerceIn(MIN_FETCH_TIMEOUT_SECONDS, MAX_FETCH_TIMEOUT_SECONDS)
        val proxy = systemHttpProxy()
        val fakeIp = target.addresses.any { isAllowedVpnFakeAddress(target.uri.host, it, target.vpn) }
        if (proxy == null || fakeIp) {
            val client = http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (!hostname.equals(target.uri.host, ignoreCase = true)) {
                            throw UnknownHostException("主机发生变化")
                        }
                        return target.addresses
                    }
                })
                .connectTimeout(FETCH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout + FETCH_CALL_GRACE_SECONDS, TimeUnit.SECONDS)
                .build()
            return RequestRoute(client, target.uri.toString(), null)
        }

        // 代理仍然使用，但 CONNECT/请求目标固定到已通过安全检查的 IP，
        // 防止代理侧重新解析同一域名后把请求导向 localhost/LAN/保留地址。
        val address = target.addresses.firstOrNull { it is Inet4Address } ?: target.addresses.first()
        val pinnedUri = pinUriToAddress(target.uri, address)
        val builder = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            .connectTimeout(FETCH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(timeout + FETCH_CALL_GRACE_SECONDS, TimeUnit.SECONDS)

        if (target.uri.scheme.equals("https", ignoreCase = true)) {
            val originalHost = target.uri.host
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            val trustManager = requireNotNull(http.x509TrustManager) {
                "当前 HTTP 客户端没有可用的 X509TrustManager"
            }
            builder.sslSocketFactory(
                SniSocketFactory(http.sslSocketFactory, originalHost),
                trustManager,
            )
            builder.hostnameVerifier { _, session -> verifier.verify(originalHost, session) }
        }

        return RequestRoute(
            client = builder.build(),
            url = pinnedUri.toString(),
            hostHeader = hostHeader(target.uri),
        )
    }

    private fun hostHeader(uri: URI): String {
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) uri.host else "${uri.host}:${uri.port}"
    }

    fun systemHttpProxy(): ProxyEndpoint? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val proxy = manager.getLinkProperties(network)?.httpProxy ?: return null
        val host = proxy.host ?: return null
        val port = proxy.port
        return if (host.isNotBlank() && port in 1..65535) ProxyEndpoint(host, port) else null
    }

    fun isVpnActive(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    fun activeTunnelInterfaces(): List<String> = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("wg") || it.name.startsWith("ppp")) }
            .map { it.name }
    }.getOrDefault(emptyList())

    fun isAllowedVpnFakeAddress(host: String, address: InetAddress, vpn: Boolean): Boolean {
        if (!vpn || looksLikeIpLiteral(host) || address !is Inet4Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 198 && second in 18..19
    }

    private fun looksLikeIpLiteral(host: String): Boolean =
        host.contains(':') || host.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.toIntOrNull()?.let { value -> value in 0..255 } == true
            }
        }

    fun blockedHint(host: String, blocked: List<InetAddress>, vpn: Boolean): String {
        val fakeRange = blocked.any { address ->
            if (address !is Inet4Address) false else {
                val bytes = address.address
                (bytes[0].toInt() and 0xff) == 198 && (bytes[1].toInt() and 0xff) in 18..19
            }
        }
        return when {
            fakeRange && !vpn ->
                "该地址位于 RFC 2544 基准测试保留网段，常见于代理/VPN 的 Fake-IP 模式；当前 App 未检测到活动 VPN，请检查代理/TUN 状态。"
            fakeRange ->
                "检测到 VPN/TUN，但该目标仍未满足安全放行条件；请在“网络诊断”中检查代理与解析状态。"
            else ->
                "这属于安全策略主动拦截，并非普通网络超时。若你确认 $host 是公网域名，请运行“网络诊断”检查 DNS/代理。"
        }
    }

    fun networkHint(target: ValidatedTarget): String {
        val proxy = systemHttpProxy()
        return buildString {
            append("解析地址：${target.addresses.joinToString { it.hostAddress ?: it.toString() }}")
            if (target.vpn) append("；检测到 VPN/TUN")
            if (proxy != null) append("；系统代理 ${proxy.host}:${proxy.port}")
            append("。可运行“网络诊断”进一步定位。")
        }
    }

    fun isPublicAddress(address: InetAddress): Boolean = PublicAddressPolicy.isPublic(address)

    internal data class RequestRoute(
        val client: OkHttpClient,
        val url: String,
        val hostHeader: String?,
    )

    internal data class ValidatedTarget(
        val uri: URI,
        val addresses: List<InetAddress>,
        val vpn: Boolean,
    )

    internal data class ProxyEndpoint(val host: String, val port: Int)

    private class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val serverName: String,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(socket, serverName, port, autoClose)

        override fun createSocket(host: String, port: Int): Socket =
            delegate.createSocket(serverName, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(serverName, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate.createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = delegate.createSocket(address, port, localAddress, localPort)
    }
    private companion object {
        const val MIN_FETCH_TIMEOUT_SECONDS = 5L
        const val MAX_FETCH_TIMEOUT_SECONDS = 300L
        const val FETCH_CONNECT_TIMEOUT_SECONDS = 10L
        const val FETCH_CALL_GRACE_SECONDS = 10L
    }
}
