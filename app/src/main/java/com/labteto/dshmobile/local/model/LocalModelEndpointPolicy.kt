package com.labteto.dshmobile.local

import java.net.URI

internal fun normalizeModelBaseUrl(raw: String): String {
    val value = raw.trim().trimEnd('/')
    require(value.isNotBlank()) { "模型接口地址不能为空" }
    val uri = runCatching { URI(value) }
        .getOrElse { error -> throw IllegalArgumentException("模型接口地址无效：${error.message}", error) }
    require(uri.userInfo == null) { "模型接口地址不能包含用户名或密码" }
    require(uri.fragment == null) { "模型接口地址不能包含片段" }
    val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("模型接口地址缺少协议")
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("模型接口地址缺少主机")
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
    require(scheme == "https" || (scheme == "http" && loopback)) {
        "模型接口必须使用 HTTPS；仅 localhost、127.0.0.1 或 ::1 允许 HTTP"
    }
    return value
}
