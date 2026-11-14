package com.labteto.dshmobile.local.model.chatgpt

import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One boundary for the official Sign in with ChatGPT token response.
 * A JSON object or array in a scalar field must produce a protocol error,
 * never the kotlinx.serialization "is not a JsonPrimitive" implementation detail.
 *
 * https://developers.openai.com/siwc/token-sharing-open-source/token-reference
 */
internal fun parseChatGptTokenResponse(json: Json, body: String): ChatGptTokenResponse {
    val root = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        // JSON decoder exceptions can quote portions of a token payload; keep them out of logs.
        throw IOException("ChatGPT OAuth 令牌响应不是合法 JSON")
    } ?: throw IOException("ChatGPT OAuth 令牌响应必须是 JSON 对象")

    val accessToken = root.optionalString("access_token")?.takeIf(String::isNotBlank)
        ?: throw IOException("ChatGPT OAuth 令牌响应缺少 access_token")
    val expires = (root["expires_in"] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.longOrNull
        ?: throw IOException("ChatGPT OAuth 令牌响应的 expires_in 必须为整数")
    if (expires <= 0L) throw IOException("ChatGPT OAuth 令牌响应的 expires_in 必须大于零")

    val tokenType = root.optionalString("token_type") ?: "Bearer"
    if (!tokenType.equals("Bearer", ignoreCase = true)) {
        throw IOException("ChatGPT OAuth 返回了不支持的 token_type")
    }
    val earliestRefresh = root.optionalScalar("earliest_refresh_at")?.let { raw ->
        val value = raw.toLongOrNull()
            ?: try { Instant.parse(raw).epochSecond } catch (_: Exception) { null }
            ?: throw IOException("ChatGPT OAuth 令牌响应的 earliest_refresh_at 格式无效")
        val seconds = if (value > 1_000_000_000_000L) value / 1_000L else value
        if (seconds <= 0L) throw IOException("ChatGPT OAuth 令牌响应的 earliest_refresh_at 无效")
        seconds
    }

    return ChatGptTokenResponse(
        accessToken = accessToken,
        refreshToken = root.optionalString("refresh_token"),
        idToken = root.optionalString("id_token"),
        tokenType = tokenType,
        expiresInSeconds = expires,
        scopes = root.optionalString("scope").orEmpty().split(' ')
            .map(String::trim).filter(String::isNotBlank).toSet(),
        earliestRefreshAtEpochSeconds = earliestRefresh,
    )
}

/** Preserve status, structure, machine code and request ID without echoing token response bodies. */
internal fun parseChatGptTokenFailure(
    json: Json,
    body: String,
    status: Int,
    requestId: String?,
): ChatGptOAuthTokenException {
    val element = try { json.parseToJsonElement(body) } catch (_: Exception) { null }
    val root = element as? JsonObject
    val errorField = root?.get("error")
    val code = when (errorField) {
        is JsonPrimitive -> errorField.stringValue()
        is JsonObject -> (errorField["code"] as? JsonPrimitive)?.stringValue()
        else -> null
    }
    val shape = when {
        // OAuth error responses must be JSON objects. Primitive/array bodies are
        // malformed protocol responses even if the JSON parser accepts them.
        root == null -> "invalid-json"
        errorField is JsonObject -> "object-error"
        errorField is JsonPrimitive -> "scalar-error"
        errorField == null -> "other-object"
        else -> "other-error"
    }
    val safeRequestId = requestId?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) }
    val description = when (code) {
        "invalid_grant" -> "授权码已失效，请重新授权"
        "invalid_client" -> "OAuth 客户端注册无效"
        "access_denied" -> "账户未批准本次授权"
        "unsupported_country_region_territory" -> "服务端当前不向所在地区提供本次账户授权；请按服务商的账户与地区要求检查"
        else -> "OAuth 令牌交换未成功"
    }
    val message = buildString {
        append("ChatGPT ")
        append(description)
        append("（HTTP ")
        append(status)
        append("，响应结构 ")
        append(shape)
        code?.takeIf(String::isNotBlank)?.let { append("，错误码 ").append(it.take(100)) }
        safeRequestId?.let { append("，请求 ID ").append(it) }
        append('）')
    }
    return ChatGptOAuthTokenException(code, message)
}

private fun JsonPrimitive.stringValue(): String? =
    takeIf { isString }?.contentOrNull

/** NULL is absent; a wrong non-null type is an actionable protocol failure. */
private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IOException("ChatGPT OAuth 令牌响应字段 $name 应为字符串，实际为 JSON 对象或数组")
    return primitive.stringValue()
        ?: throw IOException("ChatGPT OAuth 令牌响应字段 $name 应为字符串")
}

private fun JsonObject.optionalScalar(name: String): String? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw IOException("ChatGPT OAuth 令牌响应字段 $name 应为时间标量")
    return primitive.contentOrNull
}
