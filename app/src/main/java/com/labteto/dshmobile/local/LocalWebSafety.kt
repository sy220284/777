package com.labteto.dshmobile.local

import java.net.InetAddress
import java.net.URI

internal fun pinUriToAddress(uri: URI, address: InetAddress): URI =
    URI(
        uri.scheme,
        null,
        address.hostAddress,
        uri.port,
        uri.rawPath?.ifEmpty { "/" } ?: "/",
        uri.rawQuery,
        null,
    )

internal fun classifyProbeStatus(code: Int): Pair<Boolean, String> = when (code) {
    407 -> false to "系统代理可达，但要求代理认证（HTTP 407）"
    502, 503, 504 -> false to "已连接到代理/网关，但其无法正常连接目标（HTTP $code）"
    in 500..599 -> true to "目标 HTTP/TLS 路径已建立，但服务端返回错误（HTTP $code）"
    else -> true to "已建立目标 HTTP/TLS 连接（HTTP $code）"
}

internal fun isTextualWebMediaType(mediaType: String): Boolean {
    val type = mediaType.substringBefore(';').trim().lowercase()
    return type.startsWith("text/") ||
        type in setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/rss+xml",
            "application/atom+xml",
            "application/javascript",
            "application/x-javascript",
        ) ||
        type.endsWith("+json") ||
        type.endsWith("+xml")
}
