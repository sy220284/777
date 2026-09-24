package com.labteto.dshmobile.local

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class DeepSeekBillingTest {
    @Test
    fun parsesOfficialPricingTableShape() {
        val html = """
            <html><body>
              <h1>模型 &amp; 价格</h1>
              <table>
                <tr><td>缓存命中</td><td>空闲时段</td><td>0.02元</td><td>0.15元</td></tr>
                <tr><td>高峰时段</td><td>0.04元</td><td>0.30元</td></tr>
                <tr><td>缓存未命中</td><td>空闲时段</td><td>1元</td><td>4.5元</td></tr>
                <tr><td>高峰时段</td><td>2元</td><td>9.0元</td></tr>
                <tr><td>输出</td><td>空闲时段</td><td>4元</td><td>13.5元</td></tr>
                <tr><td>高峰时段</td><td>8元</td><td>27.0元</td></tr>
              </table>
              <h2>并发限制</h2>
            </body></html>
        """.trimIndent()

        val pricing = parseDeepSeekPricingPage(html)
        val flash = pricing.first { it.modelId == "deepseek-flash" }
        val pro = pricing.first { it.modelId == "deepseek-v4-pro" }

        assertEquals(0.02, flash.offPeak.cacheHitCnyPerMillion, 0.000001)
        assertEquals(2.0, flash.peak.cacheMissCnyPerMillion, 0.000001)
        assertEquals(13.5, pro.offPeak.outputCnyPerMillion, 0.000001)
        assertEquals(27.0, pro.peak.outputCnyPerMillion, 0.000001)
    }

    @Test
    fun calculatesPeakAndOffPeakCost() {
        val zone = ZoneId.of("Asia/Shanghai")
        val peak = ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val offPeak = ZonedDateTime.of(2026, 9, 23, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val usage = DeepSeekTokenUsage(
            promptTokens = 1_000_000,
            cacheHitTokens = 500_000,
            cacheMissTokens = 500_000,
            completionTokens = 100_000,
            reported = true,
        )
        val pricing = DeepSeekPricingState()

        assertEquals(
            1.82,
            DeepSeekCostCalculator.estimateCny("deepseek-flash", usage, pricing, peak)!!,
            0.000001,
        )
        assertEquals(
            0.91,
            DeepSeekCostCalculator.estimateCny("deepseek-flash", usage, pricing, offPeak)!!,
            0.000001,
        )
    }

    @Test
    fun parsesOpenAiAndAnthropicUsageShapes() {
        val json = kotlinx.serialization.json.Json

        val openAi = parseDeepSeekOpenAiUsage(
            json.parseToJsonElement(
                """{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40,"completion_tokens":20,"completion_tokens_details":{"reasoning_tokens":10}}}"""
            ).jsonObject,
        )
        assertEquals(100L, openAi.promptTokens)
        assertEquals(60L, openAi.cacheHitTokens)
        assertEquals(40L, openAi.cacheMissTokens)
        assertEquals(20L, openAi.completionTokens)
        assertEquals(10L, openAi.reasoningTokens)

        val anthropic = parseDeepSeekAnthropicUsage(
            json.parseToJsonElement(
                """{"usage":{"input_tokens":30,"cache_read_input_tokens":50,"cache_creation_input_tokens":20,"output_tokens":10}}"""
            ).jsonObject,
        )
        assertEquals(100L, anthropic.promptTokens)
        assertEquals(50L, anthropic.cacheHitTokens)
        assertEquals(50L, anthropic.cacheMissTokens)
        assertEquals(10L, anthropic.completionTokens)
        assertEquals(true, anthropic.reported)
    }

    @Test
    fun unreportedUsageIsCountedWithoutInventingTokens() {
        val current = DeepSeekUsageSnapshot(requestCount = 2, inputTokens = 100)
        val next = accumulateDeepSeekUsage(
            current = current,
            model = "deepseek-flash",
            usage = DeepSeekTokenUsage(reported = false),
            pricing = DeepSeekPricingState(),
            epochMillis = 1234L,
        )

        assertEquals(2L, next.requestCount)
        assertEquals(1L, next.unreportedRequestCount)
        assertEquals(3L, next.totalRequestCount)
        assertEquals(100L, next.inputTokens)
        assertEquals(1234L, next.updatedAt)
    }

    @Test
    fun reportedUsageStillAccumulatesMeasuredRequests() {
        val next = accumulateDeepSeekUsage(
            current = DeepSeekUsageSnapshot(unreportedRequestCount = 2),
            model = "deepseek-flash",
            usage = DeepSeekTokenUsage(
                promptTokens = 100,
                cacheHitTokens = 60,
                cacheMissTokens = 40,
                completionTokens = 20,
                reported = true,
            ),
            pricing = DeepSeekPricingState(),
            epochMillis = 5678L,
        )

        assertEquals(1L, next.requestCount)
        assertEquals(2L, next.unreportedRequestCount)
        assertEquals(3L, next.totalRequestCount)
        assertEquals(100L, next.inputTokens)
        assertEquals(20L, next.outputTokens)
    }

    @Test
    fun weekendsUseOffPeakPricing() {
        val zone = ZoneId.of("Asia/Shanghai")
        val saturdayNoon = ZonedDateTime.of(2026, 9, 26, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(DeepSeekPricePeriod.OFF_PEAK, DeepSeekBillingSchedule.periodAt(saturdayNoon))
    }
}
