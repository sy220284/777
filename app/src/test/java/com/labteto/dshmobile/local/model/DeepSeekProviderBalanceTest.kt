package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekProviderBalanceTest {
    @Test fun officialResponsePreservesExactMoneyPerCurrency() {
        val result = parseDeepSeekProviderBalance(
            """{"is_available":true,"balance_infos":[
                {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
                {"currency":"USD","total_balance":"0.00012345","granted_balance":"0","topped_up_balance":"0.00012345"}]}""",
        )
        assertTrue(result.available)
        assertEquals(2, result.amounts.size)
        assertEquals("110.00", result.amounts.first().total)
        assertEquals("10.00", result.amounts.first().granted)
        assertEquals("0.00012345", result.amounts.last().total)
    }

    @Test fun insufficientBalanceIsAnExplicitServerStatusNotAClientGuess() {
        val result = parseDeepSeekProviderBalance(
            """{"is_available":false,"balance_infos":[]}""",
        )
        assertFalse(result.available)
        assertTrue(result.amounts.isEmpty())
    }

    @Test fun malformedAndDuplicatedCurrencyResponseFailsClosed() {
        val invalid = listOf(
            """{"is_available":true}""",
            """{"balance_infos":[]}""",
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"1","granted_balance":"2","topped_up_balance":"oops"}]}""",
            """{"is_available":true,"balance_infos":[{"currency":"BTC","total_balance":"1","granted_balance":"0","topped_up_balance":"1"}]}""",
            """{"is_available":true,"balance_infos":[
                {"currency":"USD","total_balance":"1","granted_balance":"0","topped_up_balance":"1"},
                {"currency":"USD","total_balance":"1","granted_balance":"0","topped_up_balance":"1"}]}""",
        )
        invalid.forEach { sample ->
            assertThrows(Exception::class.java) { parseDeepSeekProviderBalance(sample) }
        }
    }
}
