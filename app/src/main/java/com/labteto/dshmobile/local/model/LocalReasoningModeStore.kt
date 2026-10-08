package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-conversation user preference for native reasoning-mode controls.
 * Only explicitly supported routes receive wire overrides; others retain provider defaults.
 * SharedPreferences stays local on-device, and the UI binds only the application context.
 */
internal object LocalReasoningModeStore {
    private val cached = ConcurrentHashMap<String, Boolean>()
    @Volatile private var preferences: SharedPreferences? = null

    fun attach(context: Context) {
        if (preferences == null) synchronized(this) {
            if (preferences == null) {
                preferences = context.applicationContext.getSharedPreferences(
                    "conversation_reasoning_mode", Context.MODE_PRIVATE,
                )
            }
        }
    }

    fun isSupported(profile: LocalModelProfile?, withTools: Boolean = false): Boolean {
        if (profile == null || profile.authKind != LocalModelAuthKind.API_KEY) return false
        val host = runCatching { URI(normalizeModelBaseUrl(profile.baseUrl)).host }
            .getOrNull()?.lowercase() ?: return false
        val model = profile.model.lowercase()
        if (host == "api.deepseek.com") {
            return profile.protocol == LocalModelProtocol.CHAT_COMPLETIONS &&
                model in setOf("deepseek-flash", "deepseek-v4-pro")
        }
        if (host == "api.openai.com") {
            val supportsOff = model in setOf(
                "gpt-5.5",
                "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
                "gpt-6-sol", "gpt-6-luna",
            )
            // Reasoning with function calls on these Chat Completions routes is unsupported.
            // Responses is the correct protocol for tool-enabled high-effort turns.
            if (withTools && profile.protocol == LocalModelProtocol.CHAT_COMPLETIONS &&
                model != "gpt-5.5"
            ) return false
            return supportsOff && profile.protocol in setOf(
                LocalModelProtocol.CHAT_COMPLETIONS, LocalModelProtocol.RESPONSES,
            )
        }
        return false
    }

    fun enabled(sessionId: String): Boolean {
        if (sessionId.isBlank()) return true
        return cached[sessionId]
            ?: preferences?.getBoolean("reasoning:$sessionId", true)
            ?: true
    }

    fun setEnabled(sessionId: String, value: Boolean) {
        if (sessionId.isBlank()) return
        cached[sessionId] = value
        preferences?.edit()?.putBoolean("reasoning:$sessionId", value)?.apply()
    }

    fun effortFor(
        sessionId: String,
        profile: LocalModelProfile?,
        withTools: Boolean = false,
    ): String? =
        if (isSupported(profile, withTools)) {
            if (enabled(sessionId)) "high" else "none"
        } else null
}
