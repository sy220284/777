package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.local.model.ModelReasoningCeiling
import com.labteto.dshmobile.local.presentation.LocalModelPerformanceControls
import com.labteto.dshmobile.uicontract.ModelPerformanceUiCommand
import com.labteto.dshmobile.uicontract.ModelPerformanceUiPort
import com.labteto.dshmobile.uicontract.ModelPerformanceUiReceipt
import com.labteto.dshmobile.uicontract.UiModelPerformanceSnapshot
import com.labteto.dshmobile.uicontract.UiModelReasoningCeiling
import kotlinx.coroutines.flow.map

/** Adapter only: no extra preference, policy copy, or second model configuration source. */
internal object LocalModelPerformanceUiOwner : ModelPerformanceUiPort {
    override val snapshots = LocalModelPerformanceControls.state.map { limits ->
        UiModelPerformanceSnapshot(
            reasoning = UiModelReasoningCeiling.valueOf(limits.reasoningCeiling.name),
            temperatureCeiling = limits.temperatureCeiling,
        )
    }

    override fun snapshot(): UiModelPerformanceSnapshot =
        LocalModelPerformanceControls.state.value.let { limits ->
            UiModelPerformanceSnapshot(
                reasoning = UiModelReasoningCeiling.valueOf(limits.reasoningCeiling.name),
                temperatureCeiling = limits.temperatureCeiling,
            )
        }

    override fun submit(command: ModelPerformanceUiCommand): ModelPerformanceUiReceipt = try {
        when (command) {
            is ModelPerformanceUiCommand.SetReasoning ->
                LocalModelPerformanceControls.setReasoningCeiling(
                    ModelReasoningCeiling.valueOf(command.ceiling.name))
            is ModelPerformanceUiCommand.SetTemperature -> {
                if (!command.ceiling.isFinite() || command.ceiling !in 1.0..2.0)
                    return ModelPerformanceUiReceipt.InvalidValue
                LocalModelPerformanceControls.setTemperatureCeiling(command.ceiling)
            }
        }
        ModelPerformanceUiReceipt.Applied
    } catch (failure: Exception) {
        ModelPerformanceUiReceipt.Failed(failure.message)
    }
}
