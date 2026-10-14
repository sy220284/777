package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kimi API Platform and Kimi Code are distinct credential/endpoint families.
 * Only the documented official API route and known API model IDs opt in.
 * Proxies and the pre-existing coding subscription endpoint retain their own defaults.
 */
internal object LocalKimiPolicy {
    private const val API_BASE = "https://api.moonshot.cn/v1"

    fun isOfficial(baseUrl: String): Boolean =
        runCatching { normalizeModelBaseUrl(baseUrl).equals(API_BASE, ignoreCase = true) }
            .getOrDefault(false)

    fun selection(model: String): LocalReasoningSelection? = when (model.trim().lowercase()) {
        // K3 always thinks. "Fast" means low reasoning effort, not thinking disabled.
        "kimi-k3" -> LocalReasoningSelection("high", "low", "low", "max")
        // K2.6 alone permits turning thinking off; reasoning_effort is not its API.
        "kimi-k2.6" -> LocalReasoningSelection("high", "none")
        // K2.7 Code (including highspeed) always thinks and exposes no effort switch.
        else -> null
    }

    fun writeThinking(builder: JsonObjectBuilder, model: String, effort: String?) {
        if (effort == null) return
        when (model.trim().lowercase()) {
            "kimi-k3" -> {
                require(effort in setOf("low", "high", "max")) {
                    "Kimi K3 始终启用思考，只支持 low、high、max 推理强度"
                }
                builder.put("reasoning_effort", effort)
            }
            "kimi-k2.6" -> {
                require(effort in setOf("none", "high")) {
                    "Kimi K2.6 仅支持启用或关闭思考"
                }
                builder.put("thinking", buildJsonObject {
                    put("type", if (effort == "none") "disabled" else "enabled")
                })
            }
        }
    }
}
