package com.labteto.dshmobile.local.model

import java.net.URI

/**
 * DeepSeek Chat Completions wire limits, based on the official API schema.
 * This is scoped to the exact official HTTPS API origin: custom gateways never
 * silently inherit DeepSeek-only parameters.
 *
 * https://api-docs.deepseek.com/api/create-chat-completion/
 */
internal object DeepSeekChatRequestPolicy {
    const val MAX_OUTPUT_TOKENS = 393_216
    private const val CONTEXT_HEADROOM_TOKENS = 8_192

    fun isOfficial(model: String, baseUrl: String): Boolean =
        model.trim().lowercase() in setOf("deepseek-flash", "deepseek-v4-pro") &&
            isOfficialOrigin(baseUrl)

    fun isOfficialOrigin(baseUrl: String): Boolean =
        runCatching {
            val uri = URI(normalizeModelBaseUrl(baseUrl))
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("api.deepseek.com", ignoreCase = true) &&
                uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.port in setOf(-1, 443) &&
                // Existing saved routes may use the full completion endpoint.
                uri.path.trimEnd('/') in setOf(
                    "", "/v1", "/chat/completions", "/v1/chat/completions",
                )
        }.getOrDefault(false)

    /**
     * Explicit output ceiling for a frozen request, not a promise to consume
     * that many tokens. The mode-specific targets preserve ordinary chat latency
     * while allowing Work longer outputs. Inputs are estimated; keep headroom
     * inside the published 1M total context limit.
     */
    fun outputBudget(
        model: String,
        baseUrl: String,
        reasoningEffort: String?,
        work: Boolean,
        estimatedInputTokens: Int,
        contextWindowTokens: Int?,
    ): Int? {
        if (!isOfficial(model, baseUrl)) return null
        val target = when {
            reasoningEffort == "none" -> if (work) 32_768 else 8_192
            reasoningEffort == "max" -> if (work) 196_608 else 131_072
            else -> if (work) 131_072 else 65_536
        }
        val window = (contextWindowTokens ?: 1_000_000).coerceAtMost(1_000_000)
        val remaining = (window.toLong() - estimatedInputTokens.toLong() -
            CONTEXT_HEADROOM_TOKENS).coerceAtLeast(1L)
        return minOf(target.toLong(), MAX_OUTPUT_TOKENS.toLong(), remaining).toInt()
    }

    fun checkedMaxTokens(model: String, baseUrl: String, requested: Int?): Int? {
        if (requested == null || !isOfficial(model, baseUrl)) return null
        require(requested in 1..MAX_OUTPUT_TOKENS) {
            "DeepSeek max_tokens 超出官方范围 1..$MAX_OUTPUT_TOKENS"
        }
        return requested
    }
}
