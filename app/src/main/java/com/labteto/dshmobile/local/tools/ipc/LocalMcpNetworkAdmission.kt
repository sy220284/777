package com.labteto.dshmobile.local.tools.ipc

import com.labteto.dshmobile.local.security.LocalEffectiveExecutionPolicy
import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl

/** One-time worker endpoint validation; dynamic policy is checked only at real RPC boundaries. */
internal class LocalMcpNetworkAdmission(kind: String, endpoint: String?) {
    private val destination: Pair<String, String>? = when (kind) {
        "http" -> {
            val url = URI(requireNotNull(endpoint) { "MCP 缺少远端地址" })
            require(url.rawUserInfo == null && url.host != null && url.rawFragment == null) {
                "MCP 地址不能包含内嵌凭据或不确定主机"
            }
            val loopback = url.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            require(url.scheme.equals("https", true) ||
                (loopback && url.scheme.equals("http", true))) {
                "远端 MCP 必须使用 HTTPS；本地环回连接可使用 HTTP"
            }
            url.scheme to url.host
        }
        "stdio" -> null
        else -> error("未知 MCP 执行方式：$kind")
    }

    fun requireAllowed(policy: LocalEffectiveExecutionPolicy) {
        val target = destination
        if (target == null) {
            // Domain lists apply to HTTP destinations, not arbitrary local subprocesses.
            check(policy.networkEnabled) { "网络已关闭，无法启动 stdio 插件" }
        } else {
            policy.requireNetworkDestination(target.first, target.second)
        }
    }
}

/**
 * Every worker HTTP exchange must stay on the originally admitted origin.
 * Enforced even when the MCP endpoint has no bearer credential.
 */
internal fun requireMcpWorkerSameOrigin(endpoint: URI, request: HttpUrl) {
    val host = endpoint.host ?: error("MCP 地址缺少可核实主机")
    val scheme = endpoint.scheme?.lowercase(Locale.ROOT).orEmpty()
    val loopback = host.lowercase(Locale.ROOT) in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    require(endpoint.rawUserInfo == null && endpoint.rawFragment == null &&
        (scheme == "https" || (scheme == "http" && loopback))) {
        "MCP 请求来源不符合 HTTPS 或本地环回限制"
    }
    val port = if (endpoint.port >= 0) endpoint.port else if (scheme == "https") 443 else 80
    require(request.scheme == scheme && request.host.equals(host, true) && request.port == port) {
        "MCP HTTP 请求不能跳转到其他来源"
    }
}
