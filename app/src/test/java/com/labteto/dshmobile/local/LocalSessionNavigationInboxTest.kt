package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSessionNavigationInboxTest {
    @Test
    fun lastTapWinsAcrossModeTabsAndHistory() {
        val inbox = LocalSessionNavigationInbox()
        inbox.offer(LocalSessionNavigationRequest.Mode(LocalUsageMode.CHAT))
        inbox.offer(LocalSessionNavigationRequest.Session("work-old"))
        inbox.offer(LocalSessionNavigationRequest.Mode(LocalUsageMode.WORK))
        inbox.offer(LocalSessionNavigationRequest.Session("chat-new"))
        assertEquals(LocalSessionNavigationRequest.Session("chat-new"), inbox.take())
        assertNull(inbox.take())
    }

    @Test
    fun tappingTheCurrentModeCancelsAnOlderQueuedMode() {
        val inbox = LocalSessionNavigationInbox()
        inbox.offer(LocalSessionNavigationRequest.Mode(LocalUsageMode.CHAT))
        inbox.offer(LocalSessionNavigationRequest.Mode(LocalUsageMode.WORK))
        assertEquals(LocalSessionNavigationRequest.Mode(LocalUsageMode.WORK), inbox.take())
    }

    @Test
    fun failedStaleRetryCannotOverwriteNewerTapAndDeleteClearsQueue() {
        val inbox = LocalSessionNavigationInbox()
        val old = LocalSessionNavigationRequest.Session("old")
        inbox.offer(LocalSessionNavigationRequest.Mode(LocalUsageMode.WORK))
        inbox.offerIfEmpty(old)
        assertEquals(LocalSessionNavigationRequest.Mode(LocalUsageMode.WORK), inbox.take())
        inbox.offer(old)
        inbox.clear()
        assertNull(inbox.take())
    }
}
