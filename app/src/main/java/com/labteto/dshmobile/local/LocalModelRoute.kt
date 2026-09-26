package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import com.labteto.dshmobile.harness.agent.AgentModelRoute
import java.net.URI

internal fun resolveLocalModelRoute(
    baseUrl: String,
    model: String,
): AgentModelRoute {
    val normalizedBase = normalizeModelBaseUrl(baseUrl)
    val host = runCatching { URI(normalizedBase).host.orEmpty().lowercase() }.getOrDefault("")
    val officialDeepSeek = host == "api.deepseek.com"
    return AgentModelRoute(
        provider = if (officialDeepSeek) "deepseek" else "openai-compatible",
        baseUrl = normalizedBase,
        model = model.trim().ifBlank { "deepseek-flash" },
        protocol = if (officialDeepSeek) {
            AgentModelProtocol.ANTHROPIC_MESSAGES
        } else {
            AgentModelProtocol.OPENAI_CHAT
        },
    )
}

internal fun resolveSessionModelRoute(
    session: LocalHarnessSession,
    fallbackBaseUrl: String,
    fallbackModel: String,
): AgentModelRoute = session.modelRoute ?: resolveLocalModelRoute(fallbackBaseUrl, fallbackModel)
