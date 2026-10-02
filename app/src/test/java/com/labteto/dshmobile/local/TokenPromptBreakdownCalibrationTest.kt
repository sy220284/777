package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenPromptBreakdownCalibrationTest {
    @Test
    fun calibratedBreakdownMatchesReportedInputExactly() {
        val estimated = TokenPromptBreakdown(
            systemBaseTokens = 100,
            memoryRuleTokens = 50,
            historyTokens = 600,
            currentUserTokens = 100,
            toolDefinitionTokens = 150,
        )

        val calibrated = estimated.calibratedToReportedInput(400)

        assertEquals(400L, calibrated.estimatedInputTokens)
        assertEquals(240, calibrated.historyTokens)
    }
}
