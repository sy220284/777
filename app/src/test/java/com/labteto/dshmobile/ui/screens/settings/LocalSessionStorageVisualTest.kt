package com.labteto.dshmobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSessionStorageVisualTest {
    @Test fun fractionIsBoundedAndUsesRealBudget() {
        assertEquals(0f, sessionStorageUsageFraction(1, 0), 0f)
        assertEquals(0f, sessionStorageUsageFraction(-1, 100), 0f)
        assertEquals(0.25f, sessionStorageUsageFraction(25, 100), 0.00001f)
        assertEquals(1f, sessionStorageUsageFraction(250, 100), 0f)
        assertEquals(1f, sessionStorageUsageFraction(Long.MAX_VALUE, 1), 0f)
    }
}
