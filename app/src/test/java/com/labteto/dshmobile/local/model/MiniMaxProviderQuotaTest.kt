package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MiniMaxProviderQuotaTest {
    private fun parse(body: String, balance: Boolean = false): MiniMaxProviderUsage =
        MiniMaxProviderQuotaParser.parse(Json.parseToJsonElement(body).jsonObject, balance)

    @Test fun legacyRemainingCountAndNewUsedCountAreResolvedByProviderPercent() {
        val legacy = parse("""{"model_remains":[{
            "model_name":"general","current_interval_total_count":100,
            "current_interval_usage_count":80,"current_interval_remaining_percent":80,
            "current_interval_status":1,"current_weekly_total_count":500,
            "current_weekly_usage_count":400,"current_weekly_remaining_percent":80,
            "current_weekly_status":1,"remains_time":180000
        }]}""") as MiniMaxProviderUsage.TokenPlan
        assertEquals(80L, legacy.models.single().interval.remaining)
        assertEquals(400L, legacy.models.single().weekly.remaining)
        assertEquals(180000L, legacy.models.single().intervalResetMillis)
        val modern = parse("""{"model_remains":[{
            "model_name":"general","current_interval_total_count":100,
            "current_interval_usage_count":20,"current_interval_remaining_percent":80,
            "current_interval_status":1,"current_weekly_total_count":500,
            "current_weekly_usage_count":100,"current_weekly_remaining_percent":80,
            "current_weekly_status":1
        }]}""") as MiniMaxProviderUsage.TokenPlan
        assertEquals(80L, modern.models.single().interval.remaining)
        assertEquals(400L, modern.models.single().weekly.remaining)
    }

    @Test fun conflictingNumbersCannotBecomeInventedRemainingCount() {
        val usage = parse("""{"model_remains":[{
            "model_name":"general","current_interval_total_count":100,
            "current_interval_usage_count":30,"current_interval_remaining_percent":52,
            "current_weekly_total_count":100,"current_weekly_usage_count":30,
            "current_weekly_remaining_percent":52
        }]}""") as MiniMaxProviderUsage.TokenPlan
        assertNull(usage.models.single().interval.remaining)
        assertEquals(52.0, usage.models.single().interval.remainingPercent!!, 0.001)
    }

    @Test fun noPlanCannotAppearAsUnlimitedAndWeeklyBoostIsPreserved() {
        val usage = parse("""{"model_remains":[{
            "model_name":"video","current_interval_total_count":0,
            "current_interval_usage_count":0,"current_interval_status":3,
            "current_weekly_total_count":0,"current_weekly_usage_count":0,
            "current_weekly_status":3
        },{
            "model_name":"general","current_interval_total_count":100,
            "current_interval_usage_count":60,"current_interval_remaining_percent":60,
            "current_interval_status":1,"current_weekly_total_count":100,
            "current_weekly_usage_count":90,"current_weekly_remaining_percent":90,
            "weekly_boost_permille":1500,"current_weekly_status":1
        }]}""") as MiniMaxProviderUsage.TokenPlan
        assertTrue(usage.models.first().interval.notInPlan)
        assertFalse(usage.models.first().weekly.unlimited)
        assertEquals(135.0, usage.models.last().weekly.remainingPercent!!, 0.001)
    }

    @Test fun accountBalanceDoesNotPretendToBeTokenPlan() {
        val usage = parse("""{"available_amount":"12.50","cash_balance":"10.00",
          "voucher_balance":"2.50","credit_balance":"0","base_resp":{"status_code":0,"status_msg":"success"}}""", balance=true)
        assertEquals("12.50", (usage as MiniMaxProviderUsage.AccountBalance).available)
    }

    @Test fun providerBusinessErrorIsNotSuccessfulQuery() {
        val error = assertThrows(Exception::class.java) {
            parse("""{"base_resp":{"status_code":1008,"status_msg":"额度不足"},"model_remains":[]}""")
        }
        assertTrue(error.message.orEmpty().contains("额度不足"))
    }
}
