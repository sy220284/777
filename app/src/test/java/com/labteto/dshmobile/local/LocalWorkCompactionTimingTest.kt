package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkCompactionTimingTest {
    @Test
    fun workAllowsProactiveCompactionOnlyAtTurnEntrance() {
        assertTrue(shouldProactivelyCompactBeforeModelStep(LocalUsageMode.WORK, 0))
        assertFalse(shouldProactivelyCompactBeforeModelStep(LocalUsageMode.WORK, 1))
        assertFalse(shouldProactivelyCompactBeforeModelStep(LocalUsageMode.WORK, 20))
    }

    @Test
    fun chatKeepsExistingPerStepCompactionBehavior() {
        assertTrue(shouldProactivelyCompactBeforeModelStep(LocalUsageMode.CHAT, 0))
        assertTrue(shouldProactivelyCompactBeforeModelStep(LocalUsageMode.CHAT, 3))
    }
}
