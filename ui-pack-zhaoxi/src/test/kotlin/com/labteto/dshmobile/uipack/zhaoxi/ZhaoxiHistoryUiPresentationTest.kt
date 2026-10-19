package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAutomationTaskMatches
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxUsageModelNames
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPinnedSessionsFirst
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxHomeRecentSessions
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxHumanFileSize
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxToolFamilyLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxPluginStateLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxCapabilityActionLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxCapabilityPermissionLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkspaceKindLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxUsageActionLabel
import com.labteto.dshmobile.local.TokenUsageAction
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkspaceFileVisible
import org.junit.Assert.*
import org.junit.Test

class ZhaoxiHistoryUiPresentationTest {
    @Test fun pinnedHistoryAppearsFirstWithoutReorderingOtherSessions() {
        val all = listOf("a", "b", "c").mapIndexed { index, id ->
            com.labteto.dshmobile.local.session.LocalSessionSummary(id, id, index.toLong())
        }
        assertEquals(listOf("c", "a", "b"), zxPinnedSessionsFirst(all, setOf("c")).map { it.id })
        assertEquals(listOf("a", "b", "c"), zxPinnedSessionsFirst(all, emptySet()).map { it.id })
    }
    @Test fun homeHistoryExcludesOnlyUnsentSessionsAndPreservesOrdering() {
        val sessions = listOf(
            com.labteto.dshmobile.local.session.LocalSessionSummary("draft", "新会话", 40, blank = true),
            com.labteto.dshmobile.local.session.LocalSessionSummary("first", "已聊过", 30, blank = false),
            com.labteto.dshmobile.local.session.LocalSessionSummary("work", "继续工作", 20, blank = false),
        )
        assertEquals(listOf("work", "first"),
            zxHomeRecentSessions(sessions, setOf("work", "draft")).map { it.id })
        assertEquals(listOf("first", "work"),
            zxHomeRecentSessions(sessions, emptySet()).map { it.id })
        assertTrue("隐藏是展示策略，不能删除底层草稿", sessions.any { it.id == "draft" && it.blank })
    }
    @Test fun humanReadableFileSizes() {
        assertEquals("0 字节", zxHumanFileSize(0))
        assertEquals("1.5 KB", zxHumanFileSize(1536))
        assertEquals("1.0 MB", zxHumanFileSize(1048576))
        assertEquals("1.0 GB", zxHumanFileSize(1073741824))
    }
    @Test fun technicalFilesRemainAccessibleButDoNotFloodFirstScreen() {
        assertFalse(zxWorkspaceFileVisible(".dsh/skills/code-review/SKILL.md", "", false))
        assertTrue(zxWorkspaceFileVisible(".dsh/skills/code-review/SKILL.md", "", true))
        assertTrue(zxWorkspaceFileVisible("reports/result.md", "result", false))
        assertFalse(zxWorkspaceFileVisible("reports/result.md", "other", true))
    }
    @Test fun configuredModelsRemainSelectableBeforeFirstRequest() {
        assertEquals(listOf("gpt-alpha", "minimax", "old-model"),
            zxUsageModelNames(listOf("minimax", "gpt-alpha", "minimax"), setOf("old-model")))
        assertEquals(emptyList<String>(), zxUsageModelNames(emptyList(), emptySet()))
    }
    @Test fun automationSearchUsesRealPromptActorAndSchedule() {
        assertTrue(zxAutomationTaskMatches("提醒", "明天提醒写稿", null, "每日"))
        assertTrue(zxAutomationTaskMatches("小雨", "晚间互动", "小雨", "每周"))
        assertTrue(zxAutomationTaskMatches("每周", "备份文件", null, "每周"))
        assertFalse(zxAutomationTaskMatches("未匹配", "备份文件", null, "每日"))
    }
    @Test fun commonCapabilityGroupsGetHumanNames() {
        assertEquals("设备操作", zxToolFamilyLabel("Android"))
        assertEquals("代码分析", zxToolFamilyLabel("LSP"))
        assertEquals("MCP", zxToolFamilyLabel("MCP"))
    }
    @Test fun toolPermissionAndLifecycleLabelsAreHumanReadable() {
        assertEquals("查看应用和设备信息", zxCapabilityActionLabel("android_app_info"))
        assertEquals("读取 GitHub 信息", zxCapabilityActionLabel("github_api_get"))
        assertEquals("只读查询 · 无需额外确认", zxCapabilityPermissionLabel("READ_ONLY", "NEVER"))
        assertEquals("可能修改内容 · 操作前须确认", zxCapabilityPermissionLabel("MUTATION", "ALWAYS"))
        assertEquals("已启用", zxPluginStateLabel("ACTIVE"))
        assertEquals("需要修复", zxPluginStateLabel("FAILED"))
    }
    @Test fun workspaceAndUsagePresentationDoesNotExposeRawInternalIdentifiers() {
        assertEquals("PDF 文档", zxWorkspaceKindLabel("exports/report.pdf"))
        assertEquals("图片", zxWorkspaceKindLabel("output/photo.png"))
        assertEquals("工作任务", zxUsageActionLabel(TokenUsageAction.WORK_MAIN))
        assertEquals("图像理解", zxUsageActionLabel(TokenUsageAction.VISION))
    }
}
