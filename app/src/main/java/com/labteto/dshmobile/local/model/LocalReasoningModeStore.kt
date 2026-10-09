package com.labteto.dshmobile.local.model

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

internal enum class LocalReasoningMode { DEFAULT, FAST, LOW, DEEP, MAX }

/** Persistent user selection; callers may choose a mode-specific fallback only when no choice exists. */
internal class LocalReasoningPreferences(private val preferences: SharedPreferences) {
    fun modeOrNull(sessionId: String): LocalReasoningMode? {
        val stored = preferences.getString("mode:$sessionId", null)
        if (stored != null) return runCatching { LocalReasoningMode.valueOf(stored) }
            .getOrDefault(LocalReasoningMode.DEFAULT)
        // Only explicit legacy choices migrate. An absent boolean is not a user selection.
        return if (preferences.contains("reasoning:$sessionId")) {
            if (preferences.getBoolean("reasoning:$sessionId", false)) LocalReasoningMode.DEEP
            else LocalReasoningMode.FAST
        } else null
    }

    fun mode(sessionId: String): LocalReasoningMode = modeOrNull(sessionId) ?: LocalReasoningMode.DEFAULT

    fun setMode(sessionId: String, mode: LocalReasoningMode) {
        preferences.edit().putString("mode:$sessionId", mode.name)
            .remove("reasoning:$sessionId").apply()
    }
}

/**
 * Work sampling is owned by the session. Chat reads its character's persisted expression axis.
 * Pending changes are buffered until Android preferences have been attached.
 */
internal object LocalWorkTemperatureStore {
    private val pending = ConcurrentHashMap<String, Int>()
    @Volatile private var preferences: SharedPreferences? = null

    fun attach(context: Context) {
        if (preferences == null) synchronized(this) {
            if (preferences == null) {
                val saved = context.applicationContext.getSharedPreferences(
                    "conversation_work_temperature", Context.MODE_PRIVATE,
                )
                pending.forEach { (id, level) -> saved.edit().putInt("level:$id", level).apply() }
                preferences = saved
                pending.clear()
            }
        }
    }

    fun level(sessionId: String): Int = if (sessionId.isBlank()) 2 else {
        val saved = preferences
        when {
            saved?.contains("level:$sessionId") == true -> saved.getInt("level:$sessionId", 2)
            else -> pending[sessionId] ?: 2
        }.coerceIn(0, 4)
    }

    fun setLevel(sessionId: String, level: Int) {
        if (sessionId.isBlank()) return
        synchronized(this) {
            val safe = level.coerceIn(0, 4)
            preferences?.edit()?.putInt("level:$sessionId", safe)?.apply()
                ?: run { pending[sessionId] = safe }
        }
    }

    fun requestTemperature(sessionId: String, model: String, baseUrl: String): Double? {
        val range = LocalModelPresets.chatTemperatureRangeFor(model, baseUrl) ?: return null
        val selected = level(sessionId)
        if (selected == 2 && range.omitAtChatDefault) return null
        return range.at(selected * 25)
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

    /**
     * Chat starts in FAST when no preference exists. Explicit DEFAULT still restores provider
     * behavior, and Work keeps its own provider-default starting point.
     */
    fun mode(sessionId: String, defaultChatFast: Boolean = false): LocalReasoningMode =
        if (sessionId.isBlank()) LocalReasoningMode.DEFAULT
        else preferences?.modeOrNull(sessionId) ?: pending[sessionId]
            ?: if (defaultChatFast) LocalReasoningMode.FAST else LocalReasoningMode.DEFAULT

    fun setMode(sessionId: String, mode: LocalReasoningMode) {
        if (sessionId.isBlank()) return
        synchronized(this) {
            preferences?.setMode(sessionId, mode) ?: run { pending[sessionId] = mode }
        }
    }

    fun enabled(sessionId: String): Boolean = mode(sessionId) == LocalReasoningMode.DEEP
    fun setEnabled(sessionId: String, value: Boolean) =
        setMode(sessionId, if (value) LocalReasoningMode.DEEP else LocalReasoningMode.FAST)

    fun effortFor(
        sessionId: String,
        profile: LocalModelProfile?,
        withTools: Boolean = false,
        defaultChatFast: Boolean = false,
    ): String? =
        LocalReasoningRequestPolicy.resolve(profile, withTools)?.let { policy ->
            when (mode(sessionId, defaultChatFast)) {
                LocalReasoningMode.DEFAULT -> null
                LocalReasoningMode.DEEP -> policy.enabledEffort
                LocalReasoningMode.LOW -> policy.lowEffort
                LocalReasoningMode.MAX -> policy.maxEffort
                LocalReasoningMode.FAST -> policy.disabledEffort
            }
        }
}
