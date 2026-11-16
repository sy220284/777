package com.labteto.dshmobile.local.model

import java.net.URI

/**
 * One route contract for the editor, saved profiles, credential identity and wire endpoints.
 * Official MiniMax Chat and Messages share an account origin but have different API paths.
 * Arbitrary custom URLs never inherit a provider-specific redirect or credential.
 */
internal object LocalModelProtocolRoutePolicy {
    private val bothChatAndResponses = listOf(
        LocalModelProtocol.RESPONSES, LocalModelProtocol.CHAT_COMPLETIONS,
    )
    private val miniMaxM3 = setOf(
        "minimax-m3", "minimax-m3.1-flash", "minimax-m3.1-flash-preview",
    )
    private val kimiModels = setOf(
        "k3.1-pro", "k3.1-pro-highspeed", "k3", "k3-256k",
        "kimi-for-coding", "kimi-for-coding-highspeed",
    )

    fun supportedProtocols(model: String, baseUrl: String): List<LocalModelProtocol> {
        val name = model.trim().lowercase()
        return when {
            LocalMiniMaxPolicy.isOfficial(baseUrl) && name in miniMaxM3 ->
                listOf(LocalModelProtocol.ANTHROPIC_MESSAGES, LocalModelProtocol.CHAT_COMPLETIONS)
            LocalMiniMaxPolicy.isOfficial(baseUrl) && name.startsWith("minimax-m2") ->
                listOf(LocalModelProtocol.CHAT_COMPLETIONS)
            LocalKimiPolicy.isOfficial(baseUrl) && name in kimiModels -> bothChatAndResponses
            DeepSeekChatRequestPolicy.isOfficial(model, baseUrl) -> bothChatAndResponses
            isOfficialOpenAi(model, baseUrl) -> bothChatAndResponses
            isOfficialClaude(model, baseUrl) -> listOf(LocalModelProtocol.ANTHROPIC_MESSAGES)
            else -> LocalModelProtocol.entries.toList()
        }
    }

    /**
     * Normalize only the two documented MiniMax M3 protocol roots. The exact official
     * host and explicit HTTPS port are retained; a custom provider path is never rewritten.
     */
    fun baseUrlFor(model: String, baseUrl: String, protocol: LocalModelProtocol): String {
        val normalized = normalizeModelBaseUrl(baseUrl)
        require(protocol in supportedProtocols(model, normalized)) {
            "当前官方模型不支持 ${protocol.name} 协议，请选择界面提供的协议"
        }
        if (DeepSeekChatRequestPolicy.isOfficial(model, normalized) &&
            protocol == LocalModelProtocol.RESPONSES) {
            // Old saved Chat routes may point directly to /v1/chat/completions.
            // Responses always uses the documented API root, never that Chat path.
            val uri = URI(normalized)
            return "https://${uri.rawAuthority}"
        }
        if (!LocalMiniMaxPolicy.isOfficial(normalized) ||
            (model.trim().lowercase() !in miniMaxM3 &&
                !model.trim().lowercase().startsWith("minimax-m2"))) return normalized
        val uri = URI(normalized)
        val root = "https://${uri.rawAuthority}"
        return when (protocol) {
            LocalModelProtocol.CHAT_COMPLETIONS -> "$root/v1"
            LocalModelProtocol.ANTHROPIC_MESSAGES -> "$root/anthropic/v1"
            LocalModelProtocol.RESPONSES -> error("MiniMax M3 尚未接通 Responses 协议")
        }
    }

    /** The address shown to the user is derived by the same rules as the native clients. */
    fun requestEndpoint(model: String, baseUrl: String, protocol: LocalModelProtocol): String {
        val url = baseUrlFor(model, baseUrl, protocol).trimEnd('/')
        return when (protocol) {
            LocalModelProtocol.CHAT_COMPLETIONS ->
                if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
            LocalModelProtocol.RESPONSES ->
                if (url.endsWith("/responses")) url else "$url/responses"
            LocalModelProtocol.ANTHROPIC_MESSAGES ->
                if (url.endsWith("/messages")) url else "$url/messages"
        }
    }

    /**
     * A saved key may follow an explicit MiniMax M3 protocol switch on the exact same
     * official HTTPS origin. Never authorize crossing regions, providers or proxy hosts.
     */
    fun sameCredentialOrigin(previous: String, next: String): Boolean {
        val old = normalizeModelBaseUrl(previous)
        val current = normalizeModelBaseUrl(next)
        if (old == current) return true
        val miniMax = LocalMiniMaxPolicy.isOfficial(old) && LocalMiniMaxPolicy.isOfficial(current)
        val deepSeek = DeepSeekChatRequestPolicy.isOfficialOrigin(old) &&
            DeepSeekChatRequestPolicy.isOfficialOrigin(current)
        if (!miniMax && !deepSeek) return false
        val a = URI(old)
        val b = URI(current)
        return a.host.equals(b.host, true) &&
            (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)
    }

    private fun isOfficialOpenAi(model: String, baseUrl: String): Boolean = runCatching {
        val url = URI(normalizeModelBaseUrl(baseUrl))
        model.trim().lowercase().startsWith("gpt-") &&
            url.scheme.equals("https", true) &&
            url.host.equals("api.openai.com", true) &&
            url.port in setOf(-1, 443) &&
            url.userInfo == null && url.rawQuery == null && url.rawFragment == null &&
            url.path.trimEnd('/') == "/v1"
    }.getOrDefault(false)

    private fun isOfficialClaude(model: String, baseUrl: String): Boolean = runCatching {
        val url = URI(normalizeModelBaseUrl(baseUrl))
        model.trim().startsWith("claude-", ignoreCase = true) &&
            url.scheme.equals("https", true) &&
            url.host.equals("api.anthropic.com", true) &&
            url.port in setOf(-1, 443) &&
            url.userInfo == null && url.rawQuery == null && url.rawFragment == null &&
            url.path.trimEnd('/') in setOf("/v1", "/v1/messages")
    }.getOrDefault(false)
}
