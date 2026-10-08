package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

internal enum class LocalReasoningMode { DEFAULT, FAST, DEEP }

/** Persistent user selection; absence never overrides provider defaults. */
internal class LocalReasoningPreferences(private val preferences: SharedPreferences) {
    fun mode(sessionId: String): LocalReasoningMode {
        val stored = preferences.getString("mode:$sessionId", null)
        if (stored != null) return runCatching { LocalReasoningMode.valueOf(stored) }
            .getOrDefault(LocalReasoningMode.DEFAULT)
        // Only explicit legacy choices migrate. An absent boolean was never a user choice.
        return if (preferences.contains("reasoning:$sessionId")) {
            if (preferences.getBoolean("reasoning:$sessionId", false)) LocalReasoningMode.DEEP
            else LocalReasoningMode.FAST
        } else LocalReasoningMode.DEFAULT
    }

    fun setMode(sessionId: String, mode: LocalReasoningMode) {
        preferences.edit().putString("mode:$sessionId", mode.name)
            .remove("reasoning:$sessionId").apply()
    }
}

internal object LocalReasoningModeStore {
    private val pending = ConcurrentHashMap<String, LocalReasoningMode>()
    @Volatile private var preferences: LocalReasoningPreferences? = null

    fun attach(context: Context) {
        if (preferences == null) synchronized(this) {
            if (preferences == null) {
                preferences = LocalReasoningPreferences(context.applicationContext.getSharedPreferences(
                    "conversation_reasoning_mode", Context.MODE_PRIVATE,
                ))
                pending.forEach { (id, mode) -> preferences!!.setMode(id, mode) }
                pending.clear()
            }
        }
    }

    fun isSupported(profile: LocalModelProfile?, withTools: Boolean = false): Boolean =
        LocalReasoningRequestPolicy.resolve(profile, withTools) != null

    fun mode(sessionId: String): LocalReasoningMode = if (sessionId.isBlank()) LocalReasoningMode.DEFAULT
        else preferences?.mode(sessionId) ?: pending[sessionId] ?: LocalReasoningMode.DEFAULT

    fun setMode(sessionId: String, mode: LocalReasoningMode) {
        if (sessionId.isBlank()) return
        synchronized(this) {
            preferences?.setMode(sessionId, mode) ?: run { pending[sessionId] = mode }
        }
    }

    fun enabled(sessionId: String): Boolean = mode(sessionId) == LocalReasoningMode.DEEP
    fun setEnabled(sessionId: String, value: Boolean) =
        setMode(sessionId, if (value) LocalReasoningMode.DEEP else LocalReasoningMode.FAST)

    fun effortFor(sessionId: String, profile: LocalModelProfile?, withTools: Boolean = false): String? =
        LocalReasoningRequestPolicy.resolve(profile, withTools)?.let { policy ->
            when (mode(sessionId)) {
                LocalReasoningMode.DEFAULT -> null
                LocalReasoningMode.DEEP -> policy.enabledEffort
                LocalReasoningMode.FAST -> policy.disabledEffort
            }
        }
}
