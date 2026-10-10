package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.tools.LocalTaskCapabilityKind
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadiness
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadinessProjector
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskCapabilityReadinessTest {
    @Test fun showsOnlyCapabilitiesRelevantToCurrentTask() {
        assertEquals(
            listOf(
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.GITHUB, LocalTaskCapabilityState.CONNECTION_REQUIRED),
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.WEB_SEARCH, LocalTaskCapabilityState.DISABLED),
            ),
            LocalTaskCapabilityReadinessProjector.project(
                "检查 GitHub PR #608，再联网搜索相关资料",
                githubConfigured = false,
                networkSearchEnabled = false,
            ),
        )
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "仅调整本地页面，不联网", githubConfigured = true, networkSearchEnabled = true,
        ).isEmpty())
    }

    @Test fun configurationIsNotMistakenForExecutionPermission() {
        assertEquals(
            listOf(LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.GITHUB, LocalTaskCapabilityState.CONFIGURED)),
            LocalTaskCapabilityReadinessProjector.project(
                "处理 PR #608", githubConfigured = true, networkSearchEnabled = false,
            ),
        )
        assertEquals(
            listOf(LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.GITHUB, LocalTaskCapabilityState.UNKNOWN)),
            LocalTaskCapabilityReadinessProjector.project(
                "处理 PR #608", githubConfigured = null, networkSearchEnabled = false,
            ),
        )
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "不涉及 GitHub，也不需要网页搜索", githubConfigured = true, networkSearchEnabled = true,
        ).isEmpty())
    }

    @Test fun modelAndPluginReadinessAreCurrentTaskHintsNotExecutionGrants() {
        assertEquals(
            listOf(
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.MODEL, LocalTaskCapabilityState.CONNECTION_REQUIRED),
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.MCP, LocalTaskCapabilityState.CONFIGURED),
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.PLUGINS, LocalTaskCapabilityState.UNKNOWN),
            ),
            LocalTaskCapabilityReadinessProjector.project(
                "调用 MCP 插件", githubConfigured = null, networkSearchEnabled = false,
                showModelStatus = true, modelConfigured = false, mcpToolsAvailable = true,
                pluginsInstalled = null,
            ),
        )
        assertEquals(
            listOf(LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.MODEL, LocalTaskCapabilityState.CONFIGURED)),
            LocalTaskCapabilityReadinessProjector.project(
                "整理本地文件", githubConfigured = null, networkSearchEnabled = false,
                showModelStatus = true, modelConfigured = true,
            ),
        )
    }

    @Test fun devicePermissionsAreTaskSpecificAndNeverPretendAuthorization() {
        assertEquals(
            listOf(
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.ACCESSIBILITY, LocalTaskCapabilityState.CONNECTION_REQUIRED),
                LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.NOTIFICATION_ACCESS, LocalTaskCapabilityState.CONFIGURED),
            ),
            LocalTaskCapabilityReadinessProjector.project(
                "通过无障碍操控手机并读取通知",
                githubConfigured = null, networkSearchEnabled = false,
                accessibilityActive = false, notificationAccessActive = true,
            ),
        )
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "不需要无障碍，不读取通知，仅分析文本",
            githubConfigured = null, networkSearchEnabled = false,
            accessibilityActive = true, notificationAccessActive = true,
        ).isEmpty())
    }

    @Test fun screenCaptureIsShownOnlyForRequestedTasksAndRequiresPerSessionApproval() {
        assertEquals(
            listOf(LocalTaskCapabilityReadiness(
                LocalTaskCapabilityKind.SCREEN_CAPTURE, LocalTaskCapabilityState.UNKNOWN,
            )),
            LocalTaskCapabilityReadinessProjector.project(
                "帮我共享屏幕录制操作过程", githubConfigured = null, networkSearchEnabled = false,
            ),
        )
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "不要共享屏幕，只总结文档", githubConfigured = null, networkSearchEnabled = false,
        ).isEmpty())
    }

    @Test fun unrelatedTaskAndBlankHandoffHaveNoProjectedRequirements() {
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "", githubConfigured = null, networkSearchEnabled = false,
        ).isEmpty())
        assertTrue(LocalTaskCapabilityReadinessProjector.project(
            "整理本地文件内容", githubConfigured = null, networkSearchEnabled = false,
        ).isEmpty())
        assertEquals(
            listOf(LocalTaskCapabilityReadiness(LocalTaskCapabilityKind.WEB_SEARCH, LocalTaskCapabilityState.CONFIGURED)),
            LocalTaskCapabilityReadinessProjector.project(
                "网页搜索最新文档", githubConfigured = null, networkSearchEnabled = true,
            ),
        )
    }
}
