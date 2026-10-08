package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.SharedPreferences
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

    fun isSupported(profile: LocalModelProfile?, withTools: Boolean = false): Boolean =
        LocalReasoningRequestPolicy.resolve(profile, withTools) != null

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
    ): String? = LocalReasoningRequestPolicy.resolve(profile, withTools)?.let { policy ->
        if (enabled(sessionId)) policy.enabledEffort else policy.disabledEffort
    }
}
