package com.labteto.dshmobile.local.tools.ipc

import com.labteto.dshmobile.local.security.LocalEffectiveExecutionPolicy
import java.net.URI

/** Host-side admission covers even MCP traffic running in a separate OS process. */
internal fun requireMcpWorkerNetworkAdmission(
    policy: LocalEffectiveExecutionPolicy,
    kind: String,
    endpoint: String?,
) {
    when (kind) {
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
            policy.requireNetworkDestination(url.scheme, url.host)
        }
        "stdio" -> {
            // A local child process inherits the app UID. It can open sockets outside OkHttp.
            // When a managed network restriction exists it cannot be correctly confined here.
            require(policy.networkEnabled && policy.allowedHosts == null && policy.deniedHosts.isEmpty()) {
                "网络策略限制下，无法证明 stdio 插件的网络目的地，已拒绝执行"
            }
        }
        else -> error("未知 MCP 执行方式：" + kind)
    }
}
