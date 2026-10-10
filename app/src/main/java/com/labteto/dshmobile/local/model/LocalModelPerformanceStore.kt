package com.labteto.dshmobile.local.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whole-app upper bounds. CUSTOM preserves each conversation's independent selections. */
internal enum class ModelReasoningCeiling { CUSTOM, DEFAULT, FAST, LOW, DEEP, MAX }

internal data class ModelPerformanceLimits(
    val reasoningCeiling: ModelReasoningCeiling = ModelReasoningCeiling.CUSTOM,
    val temperatureCeiling: Double = 1.3,
) {
    /** Preserve model's required reasoning floor. DEFAULT keeps the provider default. */
    fun constrainEffort(requested: String?, minimum: String = "none"): String? {
        if (reasoningCeiling == ModelReasoningCeiling.CUSTOM || reasoningCeiling == ModelReasoningCeiling.MAX) return requested
        if (reasoningCeiling == ModelReasoningCeiling.DEFAULT) {
            return if (requested == "none" && minimum == "none") "none" else null
        }
        val levels = listOf("none", "low", "medium", "high", "max")
        val maximum = when (reasoningCeiling) {
            ModelReasoningCeiling.FAST -> "none"
            ModelReasoningCeiling.LOW -> "low"
            ModelReasoningCeiling.DEEP -> "high"
            ModelReasoningCeiling.MAX -> "max"
            else -> error("Handled above")
        }
        val floorIndex = levels.indexOf(minimum).coerceAtLeast(0)
        val capIndex = levels.indexOf(maximum).coerceAtLeast(floorIndex)
        val requestedIndex = requested?.let(levels::indexOf)?.takeIf { it >= 0 } ?: capIndex
        return levels[requestedIndex.coerceIn(floorIndex, capIndex)]
    }

    fun constrainTemperature(requested: Double?): Double? =
        requested?.let { minOf(it, temperatureCeiling.coerceIn(1.0, 2.0)) }

    /** Last selectable one of the existing five stops, with the last stop attaining the cap. */
    fun temperatureStopLimit(range: LocalModelTemperatureRange): Int =
        (0..4).firstOrNull { range.at(it * 25) >= temperatureCeiling } ?: 4
}

/** One on-device preference and hot observable value, shared by Settings, Chat, Work and requests. */
internal object LocalModelPerformanceStore {
    private const val PREFERENCES = "model_performance"
    private const val KEY_REASONING = "reasoning_ceiling"
    private const val KEY_TEMPERATURE = "temperature_ceiling"

    private val mutable = MutableStateFlow(ModelPerformanceLimits())
    val state: StateFlow<ModelPerformanceLimits> = mutable
    @Volatile private var preferences: android.content.SharedPreferences? = null

    fun attach(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences != null) return
            val saved = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val ceiling = runCatching {
                ModelReasoningCeiling.valueOf(
                    saved.getString(KEY_REASONING, ModelReasoningCeiling.CUSTOM.name).orEmpty(),
                )
            }.getOrDefault(ModelReasoningCeiling.CUSTOM)
            val temperature = saved.getFloat(KEY_TEMPERATURE, 1.3f).toDouble().coerceIn(1.0, 2.0)
            preferences = saved
            mutable.value = ModelPerformanceLimits(ceiling, temperature)
        }
    }

    fun current(): ModelPerformanceLimits = mutable.value

    fun setReasoningCeiling(value: ModelReasoningCeiling) {
        synchronized(this) {
            mutable.value = mutable.value.copy(reasoningCeiling = value)
            preferences?.edit()?.putString(KEY_REASONING, value.name)?.apply()
        }
    }

    fun setTemperatureCeiling(value: Double) {
        require(value.isFinite())
        val safe = value.coerceIn(1.0, 2.0)
        synchronized(this) {
            mutable.value = mutable.value.copy(temperatureCeiling = safe)
            preferences?.edit()?.putFloat(KEY_TEMPERATURE, safe.toFloat())?.apply()
        }
    }
}
