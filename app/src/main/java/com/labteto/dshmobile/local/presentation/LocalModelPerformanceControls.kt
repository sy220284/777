package com.labteto.dshmobile.local.presentation

import android.content.Context
import com.labteto.dshmobile.local.model.LocalModelPerformanceStore
import com.labteto.dshmobile.local.model.ModelReasoningCeiling
import com.labteto.dshmobile.local.model.LocalModelTemperatureRange

/** Settings/Composer's presentation access to the model-owned performance policy. */
internal object LocalModelPerformanceControls {
    val state get() = LocalModelPerformanceStore.state

    fun attach(context: Context) = LocalModelPerformanceStore.attach(context)
    fun setReasoningCeiling(value: ModelReasoningCeiling) =
        LocalModelPerformanceStore.setReasoningCeiling(value)
    fun setTemperatureCeiling(value: Double) =
        LocalModelPerformanceStore.setTemperatureCeiling(value)

    fun temperatureStopLimit(range: LocalModelTemperatureRange): Int =
        LocalModelPerformanceStore.current().temperatureStopLimit(range)

    fun visibleTemperature(value: Double): Double =
        LocalModelPerformanceStore.current().constrainTemperature(value) ?: value
}
