package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LocalPromptCacheContinuityStoreTest {
    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    @Test
    fun appendedMessagesKeepSameCacheSeries() {
        val store = LocalPromptCacheContinuityStore()
        val tools = JsonArray(emptyList())
        val first = listOf(message("system", "固定规则"), message("user", "第一步"))

        val cold = store.assess("s", "route", first, tools)
        assertEquals(LocalPromptPrefixContinuity.COLD, cold.continuity)
        store.recordSuccess("s", "route", first, tools, cold.generation)

        val nextMessages = first + message("assistant", "结果") + message("user", "继续")
        val next = store.assess("s", "route", nextMessages, tools)

        assertEquals(LocalPromptPrefixContinuity.CONTINUOUS, next.continuity)
        assertEquals(1, next.generation)
        assertTrue(next.toolSurfaceStable)
        assertTrue(next.messagePrefixStable)
    }

    @Test
    fun historyRewriteStartsNewCacheSeries() {
        val store = LocalPromptCacheContinuityStore()
        val tools = JsonArray(emptyList())
        val first = listOf(message("system", "固定规则"), message("user", "第一步"))
        val cold = store.assess("s", "route", first, tools)
        store.recordSuccess("s", "route", first, tools, cold.generation)

        val rewritten = listOf(message("system", "固定规则"), message("user", "压缩摘要"))
        val next = store.assess("s", "route", rewritten, tools)

        assertEquals(LocalPromptPrefixContinuity.BROKEN, next.continuity)
        assertEquals(2, next.generation)
        assertFalse(next.messagePrefixStable)
    }

    @Test
    fun toolSurfaceChangeStartsNewCacheSeriesEvenWhenMessagesAreUnchanged() {
        val store = LocalPromptCacheContinuityStore()
        val emptyTools = JsonArray(emptyList())
        val messages = listOf(message("system", "固定规则"), message("user", "任务"))
        val cold = store.assess("s", "route", messages, emptyTools)
        store.recordSuccess("s", "route", messages, emptyTools, cold.generation)

        val changedTools = JsonArray(listOf(buildJsonObject { put("name", "read") }))
        val next = store.assess("s", "route", messages, changedTools)

        assertEquals(LocalPromptPrefixContinuity.BROKEN, next.continuity)
        assertEquals(2, next.generation)
        assertFalse(next.toolSurfaceStable)
        assertTrue(next.messagePrefixStable)
    }

    @Test
    fun overflowRewriteBecomesTheSuccessfulBaselineForTheNextRequest() {
        val store = LocalPromptCacheContinuityStore()
        val tools = JsonArray(emptyList())
        val original = listOf(message("system", "固定规则"), message("user", "很长历史"))
        val cold = store.assess("s", "route", original, tools)
        store.recordSuccess("s", "route", original, tools, cold.generation)

        val compacted = listOf(message("system", "固定规则"), message("user", "压缩后状态"))
        val rewritten = store.assess("s", "route", compacted, tools)
        assertEquals(LocalPromptPrefixContinuity.BROKEN, rewritten.continuity)
        assertEquals(2, rewritten.generation)
        store.recordSuccess("s", "route", compacted, tools, rewritten.generation)

        val next = store.assess("s", "route", compacted + message("assistant", "继续"), tools)
        assertEquals(LocalPromptPrefixContinuity.CONTINUOUS, next.continuity)
        assertEquals(2, next.generation)
    }

    @Test
    fun failedUncommittedRequestCannotBecomeNextPrefixBaseline() {
        val store = LocalPromptCacheContinuityStore()
        val tools = JsonArray(emptyList())
        val first = listOf(message("user", "A"))
        val cold = store.assess("s", "route", first, tools)
        store.recordSuccess("s", "route", first, tools, cold.generation)

        val failed = first + message("assistant", "未成功提交")
        assertEquals(
            LocalPromptPrefixContinuity.CONTINUOUS,
            store.assess("s", "route", failed, tools).continuity,
        )

        val realNext = first + message("assistant", "真实成功")
        val next = store.assess("s", "route", realNext, tools)
        assertEquals(LocalPromptPrefixContinuity.CONTINUOUS, next.continuity)
        assertEquals(1, next.generation)
    }
}
