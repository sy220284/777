package com.labteto.dshmobile.local.model

import java.net.URI

/**
 * Explicit provider-model-protocol allowlist for per-turn native reasoning selection.
 * Unknown and tool-incompatible routes always use the provider's default.
 * This policy is shared by the UI and the request coordinator: a disabled UI never sends
 * an override, and replayed calls preserve their selected request fingerprint.
 */
internal data class LocalReasoningSelection(
    val enabledEffort: String,
    val disabledEffort: String,
)

internal object LocalReasoningRequestPolicy {
    fun resolve(
        profile: LocalModelProfile?,
        withTools: Boolean = false,
    ): LocalReasoningSelection? {
        if (profile == null) return null
        if (profile.authKind == LocalModelAuthKind.CHATGPT_PLAN &&
            (profile.protocol != LocalModelProtocol.RESPONSES || profile.credentialRef.isNullOrBlank())
        ) return null
        val host = runCatching { URI(normalizeModelBaseUrl(profile.baseUrl)).host }
            .getOrNull()?.lowercase() ?: return null
        val model = profile.model.trim().lowercase()
        if (profile.authKind == LocalModelAuthKind.API_KEY && host == "api.deepseek.com" &&
            profile.protocol == LocalModelProtocol.CHAT_COMPLETIONS &&
            model in setOf("deepseek-flash", "deepseek-v4-pro")
        ) return LocalReasoningSelection("high", "none")
        if (host != "api.openai.com") return null
        if (profile.protocol !in setOf(
            LocalModelProtocol.CHAT_COMPLETIONS,
            LocalModelProtocol.RESPONSES,
        )) return null
        // This is deliberately a versioned allowlist of documented models and their minimum reasoning efforts.
        // Mandatory-reasoning models select low for FAST; UI must disclose this floor.
        val minimumEffort = if (model in setOf("gpt-6-astra", "gpt-6.1-sol")) "low" else "none"
        if (model !in setOf(
            "gpt-5.5", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
            "gpt-6-sol", "gpt-6-luna", "gpt-6-astra", "gpt-6.1-sol",
        )) return null
        // High-effort tool calls on these Chat Completions routes are not portable.
        if (withTools && profile.protocol == LocalModelProtocol.CHAT_COMPLETIONS &&
            model != "gpt-5.5"
        ) return null
        return LocalReasoningSelection("high", minimumEffort)
    }
}
