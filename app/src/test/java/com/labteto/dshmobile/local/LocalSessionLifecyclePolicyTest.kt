package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.local.chat.shouldContinueSingleChatBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionLifecyclePolicyTest {
    @Test
    fun deletingCurrentAlwaysReturnsFreshHomeInSameMode() {
        assertEquals(
            LocalUsageMode.CHAT,
            localDeletedCurrentSessionMode("active", setOf("active", "work"), LocalUsageMode.CHAT),
        )
        assertEquals(
            LocalUsageMode.WORK,
            localDeletedCurrentSessionMode("active", setOf("active"), LocalUsageMode.WORK),
        )
        assertEquals(
            null,
            localDeletedCurrentSessionMode("active", setOf("other"), LocalUsageMode.CHAT),
        )
    }

    @Test
    fun sessionNavigationIgnoresDetachedWorkRunsAndKeepsVisibleGuards() {
        assertFalse(
            localSessionNavigationBusy(
                sessionTransitioning = false,
                visibleRunActive = false,
            ),
        )
        assertTrue(
            localSessionNavigationBusy(
                sessionTransitioning = true,
                visibleRunActive = false,
            ),
        )
        assertTrue(
            localSessionNavigationBusy(
                sessionTransitioning = false,
                visibleRunActive = true,
            ),
        )
    }

    @Test
    fun independentChatDoesNotCarryCurrentCharacterBinding() {
        assertFalse(
            shouldContinueSingleChatBinding(
                mode = LocalConversationMode.INDEPENDENT,
                targetUsageMode = LocalUsageMode.CHAT,
                sourceUsageMode = LocalUsageMode.CHAT,
                sourceGroupEnabled = false,
            ),
        )
    }

    @Test
    fun projectChatDoesNotCarryCurrentCharacterBinding() {
        assertFalse(
            shouldContinueSingleChatBinding(
                mode = LocalConversationMode.PROJECT,
                targetUsageMode = LocalUsageMode.CHAT,
                sourceUsageMode = LocalUsageMode.CHAT,
                sourceGroupEnabled = false,
            ),
        )
    }

    @Test
    fun continuationSingleChatCarriesCurrentCharacterBinding() {
        assertTrue(
            shouldContinueSingleChatBinding(
                mode = LocalConversationMode.CONTINUATION,
                targetUsageMode = LocalUsageMode.CHAT,
                sourceUsageMode = LocalUsageMode.CHAT,
                sourceGroupEnabled = false,
            ),
        )
    }

    @Test
    fun groupChatNeverCarriesSingleCharacterBinding() {
        assertFalse(
            shouldContinueSingleChatBinding(
                mode = LocalConversationMode.CONTINUATION,
                targetUsageMode = LocalUsageMode.CHAT,
                sourceUsageMode = LocalUsageMode.CHAT,
                sourceGroupEnabled = true,
            ),
        )
    }
}
