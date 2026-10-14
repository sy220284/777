package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Kimi Code membership route. The pay-as-you-go Moonshot API is deliberately excluded:
 * its API keys and billing are not interchangeable with Kimi Code subscription benefits.
 */
internal object LocalKimiPolicy {
    private const val officialHost = "api.kimi.com"
    private val controllable = setOf("k3", "k3-256k", "kimi-for-coding")

    fun isOfficial(baseUrl: String): Boolean = runCatching {
        val uri = URI(normalizeModelBaseUrl(baseUrl))
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(officialHost, ignoreCase = true) &&
            uri.port == -1 && uri.path.trimEnd('/') == "/coding/v1" &&
            uri.rawQuery == null
    }.getOrDefault(false)

    fun selection(model: String): LocalReasoningSelection? =
        if (model.trim().lowercase() in controllable) {
            // Selecting Fast cannot silently switch K3 to a different non-thinking model.
            LocalReasoningSelection("high", "low", "low", "max")
        } else null // Highspeed's thinking stays on with no advertised effort control.

    fun writeThinking(builder: JsonObjectBuilder, model: String, effort: String?) {
        if (effort == null || model.trim().lowercase() !in controllable) return
        require(effort in setOf("low", "high", "max")) {
            "Kimi Code 当前型号的思考强度仅支持 low、high、max"
        }
        builder.put("reasoning_effort", effort)
    }

    /** k3's 1M context requires an eligible Pro/Allegretto subscription. */
    fun contextWindow(
        model: String?,
        baseUrl: String?,
        requested: Int?,
        defaultWindow: Int?,
    ): Int? {
        val override = requested?.takeIf { it in 4_096..16_000_000 }
        return if (model.equals("k3", true) && baseUrl != null && isOfficial(baseUrl)) {
            // Allow an explicit context setting; never assume a Plus tier has 1M.
            override ?: defaultWindow
        } else defaultWindow ?: override
    }
}
