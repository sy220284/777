package com.labteto.dshmobile.ui.screens.local

import androidx.activity.BackEventCompat
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.LocalChatMode
import com.labteto.dshmobile.local.feature.LocalFeatureCatalog
import com.labteto.dshmobile.local.feature.LocalFeatureModuleId
import com.labteto.dshmobile.local.feature.LocalFeatureRoute
import com.labteto.dshmobile.local.presentation.findEstablishedGroupChatSession
import com.labteto.dshmobile.local.session.LocalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeatureNavigationTest {
    @Test
    fun bothSystemEdgesAndButtonBackPopFeature() {
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureProductBackAction(BackEventCompat.EDGE_LEFT),
        )
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureProductBackAction(BackEventCompat.EDGE_RIGHT),
        )
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureProductBackAction(null),
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
    fun everySidebarFeatureKeepsDrawerAndBackFlowConsistent() {
        val pages = LocalFeatureCatalog.routes.filter { it != LocalFeaturePage.HOME }

        pages.forEach { page ->
            val opened = localFeatureOpenFromDrawer(
                stack = localFeatureHome(),
                originStack = null,
                page = page,
            )

            assertEquals(
                listOf(LocalFeaturePage.HOME.name, page.name),
                opened.stack,
            )
            assertEquals(
                LocalFeatureBackAction.POP_FEATURE,
                localFeatureProductBackAction(BackEventCompat.EDGE_LEFT),
            )
            assertEquals(page, localFeatureCurrent(opened.stack))
            assertEquals(
                LocalFeaturePage.HOME,
                localFeatureCurrent(localFeaturePop(opened.stack)),
            )
        }
    }

    @Test
    fun featureCatalogAssignsEveryRouteToExactlyOneModule() {
        val registered = LocalFeatureCatalog.modules.flatMap { it.routes }

        assertEquals(LocalFeatureRoute.entries.toSet(), registered.toSet())
        assertEquals(registered.size, registered.toSet().size)
        assertEquals(
            LocalFeatureModuleId.CHAT,
            LocalFeatureCatalog.ownerOf(LocalFeaturePage.PERSONA_GALLERY),
        )
        assertEquals(
            listOf(LocalFeaturePage.WORKSPACE, LocalFeaturePage.RUN_CENTER),
            LocalFeatureCatalog.routesFor(LocalFeatureModuleId.WORK),
        )
        assertEquals(
            LocalFeatureModuleId.AUTOMATION,
            LocalFeatureCatalog.ownerOf(LocalFeaturePage.TASKS),
        )
    }
    @Test
    fun onlyGroupWithConfiguredMembersCountsAsEstablished() {
        val emptyGroup = LocalSessionSummary(
            id = "group-empty",
            title = "群聊",
            updatedAt = 1L,
            usageMode = LocalUsageMode.CHAT,
            chatMode = LocalChatMode.GROUP.name,
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
            chatMode = LocalChatMode.SINGLE.name,
            blank = false,
        )

        assertEquals(null, findEstablishedGroupChatSession(listOf(emptyGroup, direct)))
        assertEquals(configuredGroup, findEstablishedGroupChatSession(listOf(configuredGroup, direct)))
        assertEquals(null, findEstablishedGroupChatSession(listOf(direct)))
        assertEquals(
            "group-configured",
            findEstablishedGroupChatSession(listOf(emptyGroup, configuredGroup, direct))?.id,
        )
        assertEquals(null, findEstablishedGroupChatSession(listOf(emptyGroup, direct))?.id)
    }
    @Test
    fun featureContributionOwnsBackRestoreAndDrawerPolicy() {
        var drawerOpened = false
        val contribution = LocalFeatureUiContribution(
            moduleId = LocalFeatureModuleId.WORK,
            drawerActions = mapOf(LocalFeatureDrawerEntry.WORKSPACE to { drawerOpened = true }),
            backAction = { _, edge -> localFeatureProductBackAction(edge) },
            restorePage = ::localFeatureRestoreOwnedPage,
            content = { },
        )
        val contributions = listOf(
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.SHELL,
                restorePage = ::localFeatureRestoreOwnedPage,
                content = { },
            ),
            contribution,
        )

        localFeatureDrawerAction(LocalFeatureDrawerEntry.WORKSPACE, contributions)?.invoke()
        assertTrue(drawerOpened)
        assertEquals(
            LocalFeatureBackAction.POP_FEATURE,
            localFeatureOwnedBackAction(LocalFeaturePage.WORKSPACE, BackEventCompat.EDGE_LEFT, contributions),
        )
        assertEquals(
            listOf(LocalFeaturePage.HOME.name, LocalFeaturePage.WORKSPACE.name),
            localFeatureRestoreStack(
                listOf(LocalFeaturePage.HOME.name, LocalFeaturePage.WORKSPACE.name),
                contributions,
            ),
        )
    }

    @Test
    fun invalidOrEmptyNavigationStateFallsBackToHome() {
        assertEquals(LocalFeaturePage.HOME, localFeatureCurrent(emptyList()))
        assertEquals(LocalFeaturePage.HOME, localFeatureCurrent(listOf("UNKNOWN")))
        assertEquals(listOf(LocalFeaturePage.HOME.name), localFeaturePop(emptyList()))
        assertEquals(listOf(LocalFeaturePage.HOME.name), localFeaturePop(listOf(LocalFeaturePage.HOME.name)))
    }

    @Test
    fun navigationHistoryIsBoundedToTwelveEntries() {
        var stack = localFeatureHome()
        repeat(20) { index ->
            val page = if (index % 2 == 0) LocalFeaturePage.WORKSPACE else LocalFeaturePage.RUN_CENTER
            stack = localFeaturePush(stack, page)
        }

        assertEquals(12, stack.size)
        assertEquals(LocalFeaturePage.RUN_CENTER, localFeatureCurrent(stack))
    }

    @Test
    fun restoreStackDropsUnknownAndUnownedPagesThenRestoresHomePrefix() {
        val contributions = listOf(
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.SHELL,
                restorePage = ::localFeatureRestoreOwnedPage,
                content = { },
            ),
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.WORK,
                restorePage = { page -> page.takeIf { it == LocalFeaturePage.WORKSPACE } },
                content = { },
            ),
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.CHAT,
                restorePage = { null },
                content = { },
            ),
        )

        val restored = localFeatureRestoreStack(
            listOf("UNKNOWN", LocalFeaturePage.WORKSPACE.name, LocalFeaturePage.DIARY.name),
            contributions,
        )

        assertEquals(
            listOf(LocalFeaturePage.HOME.name, LocalFeaturePage.WORKSPACE.name),
            restored,
        )
    }

    @Test
    fun duplicateDrawerOwnersAndMissingPageOwnerFailFast() {
        val duplicateDrawer = listOf(
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.WORK,
                drawerActions = mapOf(LocalFeatureDrawerEntry.WORKSPACE to { }),
                content = { },
            ),
            LocalFeatureUiContribution(
                moduleId = LocalFeatureModuleId.SHELL,
                drawerActions = mapOf(LocalFeatureDrawerEntry.WORKSPACE to { }),
                content = { },
            ),
        )
        val duplicateFailure = runCatching {
            localFeatureDrawerAction(LocalFeatureDrawerEntry.WORKSPACE, duplicateDrawer)
        }.exceptionOrNull()
        assertTrue(duplicateFailure?.message?.contains("multiple Feature owners") == true)

        val missingFailure = runCatching {
            localFeatureContributionFor(
                LocalFeaturePage.SETTINGS,
                listOf(
                    LocalFeatureUiContribution(
                        moduleId = LocalFeatureModuleId.SHELL,
                        content = { },
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(missingFailure?.message?.contains("Missing UI contribution") == true)
    }

}
