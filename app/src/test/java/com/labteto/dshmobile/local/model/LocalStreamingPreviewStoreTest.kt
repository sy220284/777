package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStreamingPreviewStoreTest {
    @Test
    fun staleRequestCannotOverwriteOrClearNewerPreviewInSameSession() {
        val store = LocalStreamingPreviewStore()
        val old = store.newOwner("session-a", "request-old", LocalUsageMode.WORK)
        val fresh = store.newOwner("session-a", "request-new", LocalUsageMode.WORK)

        store.begin(old)
        store.publishAssistant(old, "旧请求")
        store.begin(fresh)
        store.publishAssistant(fresh, "新请求")
        store.publishAssistant(old, "迟到的旧增量")
        store.clear(old)

        assertEquals("session-a", store.state.value.sessionId)
        assertEquals("request-new", store.state.value.requestId)
        assertEquals("新请求", store.state.value.assistant)
    }

    @Test
    fun previewIsRejectedByDifferentSessionOrUsageModeSurface() {
        val preview = LocalHarnessStreamingState(
            sessionId = "work-session",
            requestId = "request-1",
            usageMode = LocalUsageMode.WORK,
            assistant = "工作模式内容",
        )

        assertEquals(
            "",
            preview.forSurface("chat-session", LocalUsageMode.CHAT).assistant,
        )
        assertEquals(
            "",
            preview.forSurface("work-session", LocalUsageMode.CHAT).assistant,
        )
        assertEquals(
            "工作模式内容",
            preview.forSurface("work-session", LocalUsageMode.WORK).assistant,
        )
    }

    @Test
    fun olderSameSessionRetryCannotReclaimPreviewAfterNewerRequestStarts() {
        val store = LocalStreamingPreviewStore()
        val old = store.newOwner("session-a", "request-old", LocalUsageMode.WORK)
        val fresh = store.newOwner("session-a", "request-new", LocalUsageMode.WORK)

        store.begin(old)
        store.begin(fresh)
        store.publishAssistant(fresh, "新请求")
        store.begin(old)
        store.publishAssistant(old, "旧请求重试")

        assertEquals("request-new", store.state.value.requestId)
        assertEquals("新请求", store.state.value.assistant)
    }

    @Test
    fun sameOwnerBeginClearsPartialPreviewBeforeRetry() {
        val store = LocalStreamingPreviewStore()
        val owner = store.newOwner("session-a", "request-1", LocalUsageMode.WORK)

        store.begin(owner)
        store.publishAssistant(owner, "上一尝试的半截回复")
        store.begin(owner)

        assertEquals("session-a", store.state.value.sessionId)
        assertEquals("request-1", store.state.value.requestId)
        assertEquals("", store.state.value.assistant)
    }

    @Test
    fun exactOwnerClearRetiresPreview() {
        val store = LocalStreamingPreviewStore()
        val owner = store.newOwner("session-a", "request-1", LocalUsageMode.WORK)

        store.begin(owner)
        store.publishAssistant(owner, "处理中")
        store.clear(owner)

        assertEquals(LocalHarnessStreamingState(), store.state.value)
    }

    @Test
    fun olderRequestFromAnotherSessionCanClaimWhenThatSessionBecomesVisible() {
        val store = LocalStreamingPreviewStore()
        val background = store.newOwner("session-b", "request-b", LocalUsageMode.WORK)
        val later = store.newOwner("session-a", "request-a", LocalUsageMode.WORK)

        store.begin(later)
        store.publishAssistant(later, "A")
        store.publishAssistant(background, "B 恢复可见")

        assertEquals("session-b", store.state.value.sessionId)
        assertEquals("request-b", store.state.value.requestId)
        assertEquals("B 恢复可见", store.state.value.assistant)
    }

    @Test
    fun visibleSessionCanClaimPreviewWithoutLettingPreviousSessionClearIt() {
        val store = LocalStreamingPreviewStore()
        val first = store.newOwner("session-a", "request-a", LocalUsageMode.WORK)
        val second = store.newOwner("session-b", "request-b", LocalUsageMode.WORK)

        store.begin(first)
        store.publishAssistant(first, "A")
        store.begin(second)
        store.publishAssistant(second, "B")
        store.clear(first)

        assertEquals("session-b", store.state.value.sessionId)
        assertEquals("request-b", store.state.value.requestId)
        assertEquals("B", store.state.value.assistant)
    }
}
