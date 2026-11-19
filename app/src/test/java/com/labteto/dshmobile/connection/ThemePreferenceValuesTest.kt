package com.labteto.dshmobile.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceValuesTest {
    @Test fun migratesHistoricalNightModeToMatteBlack() {
        assertEquals("matte_black", ThemePreferenceValues.normalize("dark"))
        assertEquals("matte_black", ThemePreferenceValues.normalize("DARK"))
        assertEquals("matte_black", ThemePreferenceValues.normalize("matte_black"))
    }

    @Test fun preservesWhiteOrSystemAndNormalizesUnsupportedValues() {
        assertEquals("light", ThemePreferenceValues.normalize("light"))
        assertEquals("system", ThemePreferenceValues.normalize("system"))
        assertEquals("system", ThemePreferenceValues.normalize(null))
        assertEquals("system", ThemePreferenceValues.normalize("unknown"))
    }
}
