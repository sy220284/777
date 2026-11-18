package com.labteto.dshmobile.local.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPdfPageSelectionTest {
    @Test
    fun consolidatesRangesAndSinglePages() {
        assertEquals(listOf(2, 4, 5, 6), parseLocalPdfPageSelection("6, 2，4-6"))
        assertEquals(emptyList<Int>(), parseLocalPdfPageSelection(" "))
    }

    @Test
    fun refusesInvalidOrOversizedRanges() {
        listOf("0", "6-4", "1-7", "1-3,4-6,9", "2,").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { parseLocalPdfPageSelection(invalid) }
        }
    }
}
