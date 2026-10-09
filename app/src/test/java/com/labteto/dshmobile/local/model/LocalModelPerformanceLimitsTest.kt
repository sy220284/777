package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModelPerformanceLimitsTest {
    @Test fun customLeavesConversationAndProviderSelectionsUntouched() {
        val limits = ModelPerformanceLimits()
        assertNull(limits.constrainEffort(null))
        assertEquals("max", limits.constrainEffort("max"))
        assertEquals(1.3, limits.constrainTemperature(1.9)!!, 0.0001)
    }

    @Test fun cappedReasoningNeverEscapesSelectedCeiling() {
        val high = ModelPerformanceLimits(ModelReasoningCeiling.DEEP)
        assertEquals("high", high.constrainEffort("max"))
        assertEquals("low", high.constrainEffort("low"))
        assertEquals("high", high.constrainEffort(null)) // Provider default cannot bypass a known ceiling
        val low = ModelPerformanceLimits(ModelReasoningCeiling.LOW)
        assertEquals("low", low.constrainEffort("high"))
        assertEquals("none", low.constrainEffort("none"))
        assertEquals("low", low.constrainEffort("none", minimum = "low"))
        assertEquals("none", ModelPerformanceLimits(ModelReasoningCeiling.FAST).constrainEffort("max"))
        assertEquals("low", ModelPerformanceLimits(ModelReasoningCeiling.FAST).constrainEffort("max", minimum = "low"))
    }

    @Test fun defaultAndHighestDoNotForceUnnecessaryExtraReasoning() {
        val default = ModelPerformanceLimits(ModelReasoningCeiling.DEFAULT)
        assertNull(default.constrainEffort("max"))
        assertEquals("none", default.constrainEffort("none"))
        assertNull(ModelPerformanceLimits(ModelReasoningCeiling.MAX).constrainEffort(null))
    }

    @Test fun temperatureClipsAtCentralBoundaryAndComposerStop() {
        val range = LocalModelTemperatureRange(0.0, 2.0, 1.3)
        val limits = ModelPerformanceLimits(temperatureCeiling = 1.5)
        assertEquals(1.5, limits.constrainTemperature(2.0)!!, 0.0001)
        assertEquals(0.2, limits.constrainTemperature(0.2)!!, 0.0001)
        assertNull(limits.constrainTemperature(null))
        assertEquals(3, limits.temperatureStopLimit(range))
        assertEquals(2, ModelPerformanceLimits().temperatureStopLimit(range))
        assertEquals(2, ModelPerformanceLimits(temperatureCeiling = 1.0).temperatureStopLimit(range))
    }
    @Test fun deepSeekSamplingCeilingCanBeRaisedWithoutChangingTheDefaultTemperature() {
        try {
            LocalModelPerformanceStore.setTemperatureCeiling(1.3)
            val before = LocalModelPresets.chatTemperatureRangeFor(
                "deepseek-flash", "https://api.deepseek.com",
            )!!
            assertEquals(1.3, before.maximum, 0.0001)
            assertEquals(100, before.defaultPosition)

            LocalModelPerformanceStore.setTemperatureCeiling(2.0)
            val after = LocalModelPresets.chatTemperatureRangeFor(
                "deepseek-flash", "https://api.deepseek.com",
            )!!
            assertEquals(2.0, after.maximum, 0.0001)
            assertEquals(1.3, after.at(50), 0.0001)
            assertEquals(2.0, after.at(100), 0.0001)

            LocalModelPerformanceStore.setTemperatureCeiling(1.0)
            assertEquals(1.0, LocalModelPerformanceStore.current().constrainTemperature(2.0)!!, 0.0001)
        } finally {
            LocalModelPerformanceStore.setTemperatureCeiling(1.3)
        }
    }

}
