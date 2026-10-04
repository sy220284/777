package com.labteto.dshmobile.ui.screens.local

import androidx.activity.BackEventCompat
import com.labteto.dshmobile.local.LocalChatMode
import com.labteto.dshmobile.local.LocalSessionSummary
import com.labteto.dshmobile.local.LocalUsageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeatureNavigationTest {
    @Test
    fun leftEdgeBackGestureOpensDrawerWhileOtherBackInputsPopFeature() {
        assertEquals(
            LocalFeatureBackAction.OPEN_DRAWER,
            localFeatureBackAction(BackEventCompat.EDGE_LEFT),
        )
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureBackAction(BackEventCompat.EDGE_RIGHT),
        )
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureBackAction(null),
        )
    }

    @Test
    fun sessionNavigationOnlyCommitsKnownAcceptedTargets() {
        val session = LocalSessionSummary(
            id = "target",
            title = "目标会话",
            updatedAt = 1L,
            usageMode = LocalUsageMode.WORK,
            blank = false,
        )
        var switchCalls = 0
        val rejected = acceptLocalSessionNavigation(
            currentSessionId = "current",
            targetSessionId = session.id,
            sessions = listOf(session),
        ) {
            switchCalls += 1
            false
        }
        assertFalse(rejected)
        assertEquals(1, switchCalls)

        val accepted = acceptLocalSessionNavigation(
            currentSessionId = "current",
            targetSessionId = session.id,
            sessions = listOf(session),
        ) {
            switchCalls += 1
            true
        }
        assertTrue(accepted)
        assertEquals(2, switchCalls)

        val alreadyCurrent = acceptLocalSessionNavigation(
            currentSessionId = session.id,
            targetSessionId = session.id,
            sessions = emptyList(),
        ) {
            switchCalls += 1
            false
        }
        assertTrue(alreadyCurrent)
        assertEquals(2, switchCalls)

        val unknown = acceptLocalSessionNavigation(
            currentSessionId = "current",
            targetSessionId = "missing",
            sessions = listOf(session),
        ) {
            switchCalls += 1
            true
        }
        assertFalse(unknown)
        assertEquals(2, switchCalls)
    }

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
    fun consecutiveDrawerSelectionsReplaceCurrentFeatureAndReturnToOriginalPage() {
        var stack = localFeatureHome()
        var originStack: List<String>? = null

        listOf(
            LocalFeaturePage.PERSONA_GALLERY,
            LocalFeaturePage.DIARY,
            LocalFeaturePage.TASKS,
        ).forEach { page ->
            val navigation = localFeatureOpenFromDrawer(
                stack = stack,
                originStack = originStack,
                page = page,
            )
            originStack = navigation.originStack
            stack = navigation.stack
        }

        assertEquals(
            listOf(LocalFeaturePage.HOME.name, LocalFeaturePage.TASKS.name),
            stack,
        )
        stack = localFeaturePop(stack)
        assertEquals(LocalFeaturePage.HOME, localFeatureCurrent(stack))
    }

    @Test
    fun drawerSelectionsPreserveThePageThatOpenedTheDrawerAsReturnOrigin() {
        var stack = localFeatureHome()
        stack = localFeaturePush(stack, LocalFeaturePage.RUN_CENTER)
        stack = localFeaturePush(stack, LocalFeaturePage.WORKSPACE)
        val expectedOrigin = stack

        var originStack: List<String>? = null
        var navigation = localFeatureOpenFromDrawer(
            stack = stack,
            originStack = originStack,
            page = LocalFeaturePage.TOOLS,
        )
        originStack = navigation.originStack
        stack = navigation.stack

        navigation = localFeatureOpenFromDrawer(
            stack = stack,
            originStack = originStack,
            page = LocalFeaturePage.SETTINGS,
        )
        stack = navigation.stack

        assertEquals(expectedOrigin, localFeaturePop(stack))
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
        assertEquals(
            "group-configured",
            establishedGroupChatSessionId(listOf(emptyGroup, configuredGroup, direct)),
        )
        assertEquals(null, establishedGroupChatSessionId(listOf(emptyGroup, direct)))
    }
}
