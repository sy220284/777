package com.labteto.dshmobile.uipack.v6

import org.junit.Assert.*
import org.junit.Test

class V6LineChartTest {
    @Test fun emptySingleAndConstantSeriesHaveExplicitGeometry() {
        assertTrue(v6LineChartCoordinates(emptyList()).isEmpty())
        assertEquals(listOf(.5f to .5f), v6LineChartCoordinates(listOf(9.0)))
        assertEquals(listOf(0f to .5f, 1f to .5f), v6LineChartCoordinates(listOf(-7.0, -7.0)))
    }
    @Test fun extremeAndNegativeValuesStayFiniteAndOrdered() {
        val result = v6LineChartCoordinates(listOf(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE))
        assertEquals(listOf(0f to 1f, .5f to .5f, 1f to 0f), result)
        assertEquals(listOf(0f to 1f, 1f to 0f), v6LineChartCoordinates(listOf(-20.0, -10.0)))
    }
}
