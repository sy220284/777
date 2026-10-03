package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeatureNavigationTest {
    @Test
    fun backReturnsToFeatureThatOpenedCurrentPage() {
        var stack = localFeatureHome()
        stack = localFeaturePush(stack, LocalFeaturePage.DIARY)
        stack = localFeaturePush(stack, LocalFeaturePage.TOOLS)
        stack = localFeaturePush(stack, LocalFeaturePage.SETTINGS)

        assertEquals(LocalFeaturePage.SETTINGS, localFeatureCurrent(stack))
        stack = localFeaturePop(stack)
        assertEquals(LocalFeaturePage.TOOLS, localFeatureCurrent(stack))
        stack = localFeaturePop(stack)
        assertEquals(LocalFeaturePage.DIARY, localFeatureCurrent(stack))
        stack = localFeaturePop(stack)
        assertEquals(LocalFeaturePage.HOME, localFeatureCurrent(stack))
    }

    @Test
    fun pushingSamePageDoesNotDuplicateHistory() {
        var stack = localFeatureHome()
        stack = localFeaturePush(stack, LocalFeaturePage.WORKSPACE)
        val once = stack
        stack = localFeaturePush(stack, LocalFeaturePage.WORKSPACE)

        assertEquals(once, stack)
    }

    @Test
    fun onlyGroupWithConfiguredMembersCountsAsEstablished() {
        val emptyGroup = LocalSessionSummary(
            id = "group-empty",
            title = "群聊",
            updatedAt = 1L,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.GROUP,
            groupMemberCount = 0,
            blank = true,
        )
        val configuredGroup = emptyGroup.copy(
            id = "group-configured",
            groupMemberCount = 2,
        )
        val direct = LocalSessionSummary(
            id = "direct",
            title = "单聊",
            updatedAt = 2L,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.SINGLE,
            blank = false,
        )

        assertFalse(hasEstablishedGroupChat(listOf(emptyGroup, direct)))
        assertTrue(hasEstablishedGroupChat(listOf(configuredGroup, direct)))
        assertFalse(hasEstablishedGroupChat(listOf(direct)))
    }
}
