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
        URI(normalizeModelBaseUrl(baseUrl)).host?.lowercase() in
            setOf("api.minimaxi.com", "api.minimax.cn", "api.minimax.io")
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
