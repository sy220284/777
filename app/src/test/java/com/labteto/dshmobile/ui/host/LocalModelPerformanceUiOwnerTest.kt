package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.ModelPerformanceUiCommand
import com.labteto.dshmobile.uicontract.ModelPerformanceUiReceipt
import com.labteto.dshmobile.uicontract.UiModelReasoningCeiling
import org.junit.Assert.assertEquals
import org.junit.Test

/** The V6 UI adapter and the shipped Settings/Composer must mutate the exact same Owner. */
class LocalModelPerformanceUiOwnerTest {
    @Test fun reasoningAndTemperatureRoundTripThroughExistingStore() {
        val original = LocalModelPerformanceUiOwner.snapshot()
        try {
            assertEquals(ModelPerformanceUiReceipt.Applied,
                LocalModelPerformanceUiOwner.submit(
                    ModelPerformanceUiCommand.SetReasoning(UiModelReasoningCeiling.DEEP)))
            assertEquals(UiModelReasoningCeiling.DEEP,
                LocalModelPerformanceUiOwner.snapshot().reasoning)
            assertEquals(ModelPerformanceUiReceipt.Applied,
                LocalModelPerformanceUiOwner.submit(
                    ModelPerformanceUiCommand.SetTemperature(1.75)))
            assertEquals(1.75,
                LocalModelPerformanceUiOwner.snapshot().temperatureCeiling, 0.0001)
        } finally {
            LocalModelPerformanceUiOwner.submit(
                ModelPerformanceUiCommand.SetReasoning(original.reasoning))
            LocalModelPerformanceUiOwner.submit(
                ModelPerformanceUiCommand.SetTemperature(original.temperatureCeiling))
        }
    }

    @Test fun invalidTemperatureNeverChangesExistingPolicy() {
        val before = LocalModelPerformanceUiOwner.snapshot()
        assertEquals(ModelPerformanceUiReceipt.InvalidValue,
            LocalModelPerformanceUiOwner.submit(
                ModelPerformanceUiCommand.SetTemperature(Double.NaN)))
        assertEquals(ModelPerformanceUiReceipt.InvalidValue,
            LocalModelPerformanceUiOwner.submit(
                ModelPerformanceUiCommand.SetTemperature(2.5)))
        assertEquals(before, LocalModelPerformanceUiOwner.snapshot())
    }
}
