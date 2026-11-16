package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Official MiniMax compatibility contract; shared by controls and both wire protocols. */
internal object LocalMiniMaxPolicy {
    fun isOfficial(baseUrl: String): Boolean = runCatching {
        val uri = URI(normalizeModelBaseUrl(baseUrl))
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in setOf("api.minimaxi.com", "api.minimax.cn", "api.minimax.io") &&
            uri.port in setOf(-1, 443) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.trimEnd('/') in setOf(
                "", "/v1", "/v1/models", "/v1/chat/completions",
                "/anthropic", "/anthropic/v1", "/anthropic/v1/messages",
            )
    }.getOrDefault(false)

    /** Keep existing OpenAI-compatible profiles on /v1; default Anthropic profiles use /anthropic/v1. */
    fun protocolForEndpoint(baseUrl: String): LocalModelProtocol =
        if (URI(normalizeModelBaseUrl(baseUrl)).path.orEmpty().split('/').contains("anthropic")) {
            LocalModelProtocol.ANTHROPIC_MESSAGES
        } else {
            LocalModelProtocol.CHAT_COMPLETIONS
        }

    /** M3.1 already separates reasoning_content natively; older models use the legacy flag. */
    fun needsLegacyReasoningSplit(model: String): Boolean =
        !model.equals("MiniMax-M3.1-Flash", ignoreCase = true)

    /** M3 Messages video blocks only accept MP4/AVI/MOV/MKV, unlike Kimi's wider list. */
    fun videoMediaType(name: String, mediaType: String): String? {
        val extensionType = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> return null
        }
        val declared = mediaType.substringBefore(';').trim().lowercase()
        return extensionType.takeIf { declared in setOf("", "application/octet-stream", extensionType) }
    }

    fun nativeVideoSupported(model: String, baseUrl: String, protocol: LocalModelProtocol): Boolean =
        isOfficial(baseUrl) &&
            protocol == LocalModelProtocol.ANTHROPIC_MESSAGES &&
            model.lowercase() in setOf("minimax-m3", "minimax-m3.1-flash", "minimax-m3.1-flash-preview")

    /** Authentication is rejected by the provider; this is not proof that the header was omitted. */
    fun authenticationFailureHint(): String =
        "MiniMax 鉴权失败（HTTP 401）：服务端拒绝当前 API 密钥。请确认密钥由当前接口对应的官方开放平台签发、仍有效且具有模型调用权限；请勿混用其他产品或套餐凭据。"

    fun requireSuccessfulResponse(root: JsonObject) {
        val status = root["base_resp"] as? JsonObject ?: return
        val code = status["status_code"]?.jsonPrimitive?.intOrNull ?: return
        if (code != 0) throw LocalModelException("MINIMAX_API_$code",
            "MiniMax 请求失败（$code）：${status["status_msg"]?.jsonPrimitive?.contentOrNull ?: "请检查账户和请求配置"}", false)
    }

    fun selection(model: String): LocalReasoningSelection? = when (model.lowercase()) {
        "minimax-m3" -> LocalReasoningSelection("high", "none")
        "minimax-m3.1-flash", "minimax-m3.1-flash-preview" ->
            LocalReasoningSelection("high", "low", maxEffort = "max")
        else -> null // M2.x always thinks; an off switch would be misleading.
    }

    fun writeThinking(builder: JsonObjectBuilder, model: String, effort: String?, anthropic: Boolean) {
        if (effort == null) return
        when (model.lowercase()) {
            "minimax-m3" -> builder.put("thinking", buildJsonObject {
                put("type", if (effort == "none") "disabled" else "adaptive")
            })
            "minimax-m3.1-flash", "minimax-m3.1-flash-preview" -> {
                require(effort in setOf("low", "medium", "high", "xhigh", "max")) {
                    "MiniMax M3.1 必须保留思考，请选择基础思考或更高档位"
                }
                // The stable release controls depth directly. Keep the already-saved Preview
                // transport's explicit adaptive flag unchanged for compatibility.
                if (model.equals("MiniMax-M3.1-Flash-Preview", ignoreCase = true)) {
                    builder.put("thinking", buildJsonObject { put("type", "adaptive") })
                }
                if (anthropic) builder.put("output_config", buildJsonObject { put("effort", effort) })
                else builder.put("reasoning_effort", effort)
            }
        }
    }
}
