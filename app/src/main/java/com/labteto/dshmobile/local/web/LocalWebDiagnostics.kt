package com.labteto.dshmobile.local.web

import com.labteto.dshmobile.core.wire.withCancellableHttpResponse
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/** DNS/security facts and bounded HTTP/TLS probes; shares the fetch target policy. */
internal class LocalWebDiagnostics(private val targetResolver: LocalWebTargetResolver) {
    suspend fun diagnose(input: String): String = withContext(Dispatchers.IO) {
        val normalized = targetResolver.normalizeInput(input)
        val uri = targetResolver.parseUri(normalized)
        val host = requireNotNull(uri.host)
        val proxy = targetResolver.systemHttpProxy()
        val vpn = targetResolver.isVpnActive()
        val interfaces = targetResolver.activeTunnelInterfaces()
        val addresses = targetResolver.resolve(host)
        val blocked = addresses.filterNot { targetResolver.isPublicAddress(it) || targetResolver.isAllowedVpnFakeAddress(host, it, vpn) }

        if (blocked.isNotEmpty()) {
            return@withContext buildString {
                appendLine("网络诊断")
                appendLine("目标：$normalized")
                appendLine("域名：$host")
                appendLine("解析：${addresses.joinToString { it.hostAddress ?: it.toString() }}")
                appendLine("系统代理：${proxy?.let { "${it.host}:${it.port}" } ?: "未检测到"}")
                appendLine("VPN/TUN：${if (vpn) "已启用" else "未检测到"}${if (interfaces.isNotEmpty()) "（${interfaces.joinToString()}）" else ""}")
                appendLine("命中受保护地址：${blocked.joinToString { it.hostAddress ?: it.toString() }}")
                append("结论：安全策略会主动拦截。${targetResolver.blockedHint(host, blocked, vpn)}")
            }.trimEnd()
        }

        val target = LocalWebTargetResolver.ValidatedTarget(uri, addresses, vpn)
        val probe = probeConnectivity(target)
        buildString {
            appendLine("网络诊断")
            appendLine("目标：$normalized")
            appendLine("域名：$host")
            appendLine("解析：${addresses.joinToString { it.hostAddress ?: it.toString() }}")
            appendLine("系统代理：${proxy?.let { "${it.host}:${it.port}" } ?: "未检测到"}")
            appendLine("VPN/TUN：${if (vpn) "已启用" else "未检测到"}${if (interfaces.isNotEmpty()) "（${interfaces.joinToString()}）" else ""}")
            appendLine("安全检查：通过")
            appendLine("实际连通性：${probe.detail}")
            append(
                if (probe.reachable) {
                    "结论：DNS、安全策略与实际 HTTP/TLS 连通性均已验证。"
                } else {
                    "结论：DNS 与安全策略检查通过，但连续探测仍未建立稳定连接；这可能是链路抖动、代理/网关、TLS 或目标服务问题，单凭该结果不能判定 VPN/代理存在规则性阻断。"
                },
            )
        }.trimEnd()
    }


    private suspend fun probeConnectivity(target: LocalWebTargetResolver.ValidatedTarget): ConnectivityProbe {
        val failures = mutableListOf<String>()
        repeat(PROBE_ATTEMPTS) { index ->
            val attempt = probeConnectivityOnce(target)
            if (attempt.reachable) {
                return if (index == 0) {
                    attempt
                } else {
                    attempt.copy(detail = "第 ${index + 1}/$PROBE_ATTEMPTS 次探测成功：${attempt.detail}")
                }
            }
            failures += "${index + 1}/$PROBE_ATTEMPTS ${attempt.detail}"
            if (!attempt.retryable || index == PROBE_ATTEMPTS - 1) {
                return if (index == 0) {
                    attempt
                } else {
                    attempt.copy(
                        detail = "连续 ${index + 1} 次探测未建立稳定连接；${failures.joinToString("；")}",
                    )
                }
            }
            delay(NETWORK_RETRY_BACKOFF_MS * (index + 1))
        }
        error("网络探测重试循环异常结束")
    }

    private suspend fun probeConnectivityOnce(target: LocalWebTargetResolver.ValidatedTarget): ConnectivityProbe {
        return try {
            val route = targetResolver.requestRoute(target, PROBE_CALL_TIMEOUT_SECONDS)
            val builder = Request.Builder()
                .url(route.url)
                .header("User-Agent", LOCAL_WEB_USER_AGENT)
                .head()
            route.hostHeader?.let { builder.header("Host", it) }
            withCancellableHttpResponse(route.client.newCall(builder.build())) { response ->
                val classification = classifyProbeStatus(response.code)
                ConnectivityProbe(
                    reachable = classification.first,
                    detail = classification.second,
                    retryable = shouldRetryProbeStatus(response.code),
                )
            }
        } catch (error: SocketTimeoutException) {
            ConnectivityProbe(
                reachable = false,
                detail = "探测超时（PROBE_TIMEOUT）：${error.message ?: "连接未完成"}",
                retryable = true,
            )
        } catch (error: java.io.IOException) {
            ConnectivityProbe(
                reachable = false,
                detail = "探测失败（PROBE_FAILED）：${error.message ?: error::class.java.simpleName}",
                retryable = isRetryableWebTransportFailure(error),
            )
        }
    }

    private data class ConnectivityProbe(
        val reachable: Boolean,
        val detail: String,
        val retryable: Boolean = false,
    )

    private companion object {
        const val PROBE_CALL_TIMEOUT_SECONDS = 8L
        const val PROBE_ATTEMPTS = 3
        const val NETWORK_RETRY_BACKOFF_MS = 200L
    }
}
