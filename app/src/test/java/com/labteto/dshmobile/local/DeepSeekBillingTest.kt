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
                """{"usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40,"prompt_tokens_details":{"cache_write_tokens":15},"completion_tokens":20,"completion_tokens_details":{"reasoning_tokens":10}}}"""
            ).jsonObject,
        )
        assertEquals(100L, openAi.promptTokens)
        assertEquals(60L, openAi.cacheHitTokens)
        assertEquals(40L, openAi.cacheMissTokens)
        assertEquals(15L, openAi.cacheWriteTokens)
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
        assertEquals(20L, anthropic.cacheWriteTokens)
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
                cacheWriteTokens = 12,
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
    fun nonOfficialRouteCountsTokensWithoutApplyingDeepSeekOfficialPrice() {
        val next = accumulateDeepSeekUsage(
            current = DeepSeekUsageSnapshot(),
            model = "deepseek-flash",
            usage = DeepSeekTokenUsage(
                promptTokens = 100,
                cacheMissTokens = 100,
                completionTokens = 20,
                reported = true,
            ),
            pricing = DeepSeekPricingState(),
            epochMillis = 5_678L,
            pricingEligible = false,
        )

        assertEquals(120L, next.totalTokens)
        assertEquals(120L, next.unpricedTokens)
        assertEquals(0.0, next.estimatedCostCny, 0.0)
    }

    @Test
    fun officialPricingRequiresBothDeepSeekProviderAndOfficialHost() {
        val official = LocalModelRouteIdentity(
            provider = "DeepSeek",
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
            authKind = LocalModelAuthKind.API_KEY.name,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS.name,
        )
        val proxy = official.copy(baseUrl = "https://proxy.example/v1")
        val renamedProvider = official.copy(provider = "自定义")

        assertEquals(true, official.allowsOfficialDeepSeekPricing())
        assertEquals(false, proxy.allowsOfficialDeepSeekPricing())
        assertEquals(false, renamedProvider.allowsOfficialDeepSeekPricing())
        assertEquals(false, (null as LocalModelRouteIdentity?).allowsOfficialDeepSeekPricing())
    }

    @Test
    fun weekendsUseOffPeakPricing() {
        val zone = ZoneId.of("Asia/Shanghai")
        val saturdayNoon = ZonedDateTime.of(2026, 9, 26, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(DeepSeekPricePeriod.OFF_PEAK, DeepSeekBillingSchedule.periodAt(saturdayNoon))
    }

    @Test
    fun extremeUsageSaturatesInsteadOfWrappingNegative() {
        val next = accumulateDeepSeekUsage(
            current = DeepSeekUsageSnapshot(
                inputTokens = Long.MAX_VALUE - 1L,
                outputTokens = Long.MAX_VALUE - 1L,
                requestCount = Long.MAX_VALUE,
                unpricedTokens = Long.MAX_VALUE - 1L,
                estimatedCostCny = Double.MAX_VALUE,
            ),
            model = "unknown-unpriced-model",
            usage = DeepSeekTokenUsage(
                promptTokens = Long.MAX_VALUE,
                cacheHitTokens = Long.MAX_VALUE,
                cacheMissTokens = Long.MAX_VALUE,
                completionTokens = Long.MAX_VALUE,
                reasoningTokens = Long.MAX_VALUE,
                reported = true,
            ),
            pricing = DeepSeekPricingState(),
            epochMillis = 9_999L,
        )

        assertEquals(Long.MAX_VALUE, next.inputTokens)
        assertEquals(Long.MAX_VALUE, next.outputTokens)
        assertEquals(Long.MAX_VALUE, next.totalTokens)
        assertEquals(Long.MAX_VALUE, next.requestCount)
        assertEquals(Long.MAX_VALUE, next.unpricedTokens)
        assertEquals(Double.MAX_VALUE, next.estimatedCostCny, 0.0)
    }

    @Test
    fun negativeProviderUsageIsNormalizedToZero() {
        val json = kotlinx.serialization.json.Json
        val openAi = parseDeepSeekOpenAiUsage(
            json.parseToJsonElement(
                """{"usage":{"prompt_tokens":-10,"prompt_cache_hit_tokens":-5,"prompt_cache_miss_tokens":-2,"completion_tokens":-3,"completion_tokens_details":{"reasoning_tokens":-4}}}"""
            ).jsonObject,
        )
        val anthropic = parseDeepSeekAnthropicUsage(
            json.parseToJsonElement(
                """{"usage":{"input_tokens":-10,"cache_read_input_tokens":-20,"cache_creation_input_tokens":-30,"output_tokens":-40}}"""
            ).jsonObject,
        )

        assertEquals(0L, openAi.totalTokens)
        assertEquals(0L, openAi.cacheHitTokens)
        assertEquals(0L, openAi.cacheMissTokens)
        assertEquals(0L, openAi.reasoningTokens)
        assertEquals(0L, anthropic.totalTokens)
        assertEquals(0L, anthropic.cacheHitTokens)
        assertEquals(0L, anthropic.cacheMissTokens)
    }

    @Test
    fun anthropicUsageAdditionCannotOverflow() {
        val json = kotlinx.serialization.json.Json
        val usage = parseDeepSeekAnthropicUsage(
            json.parseToJsonElement(
                """{"usage":{"input_tokens":9223372036854775807,"cache_read_input_tokens":9223372036854775807,"cache_creation_input_tokens":9223372036854775807,"output_tokens":9223372036854775807}}"""
            ).jsonObject,
        )

        assertEquals(Long.MAX_VALUE, usage.promptTokens)
        assertEquals(Long.MAX_VALUE, usage.cacheMissTokens)
        assertEquals(Long.MAX_VALUE, usage.totalTokens)
    }

    @Test
    fun invalidPricingValuesCannotProduceNanOrNegativeCost() {
        val invalidPricing = DeepSeekPricingState(
            models = listOf(
                DeepSeekModelPricing(
                    modelId = "broken",
                    displayName = "broken",
                    version = "broken",
                    offPeak = DeepSeekPriceTier(Double.NaN, -1.0, Double.POSITIVE_INFINITY),
                    peak = DeepSeekPriceTier(Double.NaN, -1.0, Double.POSITIVE_INFINITY),
                ),
            ),
        )
        val cost = DeepSeekCostCalculator.estimateCny(
            model = "broken",
            usage = DeepSeekTokenUsage(
                promptTokens = Long.MAX_VALUE,
                cacheHitTokens = Long.MAX_VALUE,
                completionTokens = Long.MAX_VALUE,
                reported = true,
            ),
            pricing = invalidPricing,
            epochMillis = 0L,
        )

        assertEquals(0.0, requireNotNull(cost), 0.0)
    }

}
