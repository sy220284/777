package com.labteto.dshmobile.uicontract

import kotlinx.coroutines.flow.Flow

/**
 * Whole-app Model Owner performance policy. The UI Pack never imports app-only
 * preference stores, model gateway internals or Android Context.
 */
enum class UiModelReasoningCeiling { CUSTOM, DEFAULT, FAST, LOW, DEEP, MAX }

data class UiModelPerformanceSnapshot(
    val reasoning: UiModelReasoningCeiling = UiModelReasoningCeiling.CUSTOM,
    val temperatureCeiling: Double = 1.3,
)

sealed interface ModelPerformanceUiCommand {
    data class SetReasoning(val ceiling: UiModelReasoningCeiling) : ModelPerformanceUiCommand
    data class SetTemperature(val ceiling: Double) : ModelPerformanceUiCommand
}

sealed interface ModelPerformanceUiReceipt {
    data object Applied : ModelPerformanceUiReceipt
    data object InvalidValue : ModelPerformanceUiReceipt
    data class Failed(val message: String?) : ModelPerformanceUiReceipt
}

interface ModelPerformanceUiPort {
    val snapshots: Flow<UiModelPerformanceSnapshot>
    fun snapshot(): UiModelPerformanceSnapshot
    /** The existing Model Performance Store owns validation and persistence. */
    fun submit(command: ModelPerformanceUiCommand): ModelPerformanceUiReceipt
}
