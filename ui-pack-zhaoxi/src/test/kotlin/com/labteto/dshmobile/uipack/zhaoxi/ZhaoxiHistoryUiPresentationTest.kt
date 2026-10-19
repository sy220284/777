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
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxSettingsNavigationIcon
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
        assertEquals("应用信息", zxCapabilityActionLabel("android_app_info"))
        assertEquals("读取 GitHub 信息", zxCapabilityActionLabel("github_api_get"))
        assertEquals("启动应用", zxCapabilityActionLabel("android_app_launch"))
        assertEquals("屏幕点击", zxCapabilityActionLabel("android_tap"))
        assertEquals("滑动屏幕", zxCapabilityActionLabel("android_swipe"))
        assertEquals("屏幕截图", zxCapabilityActionLabel("android_screenshot"))
        assertEquals("android_unknown_future_action", zxCapabilityActionLabel("android_unknown_future_action"))
        assertEquals("读取 · 无需额外确认", zxCapabilityPermissionLabel("READ_ONLY", "NEVER"))
        assertEquals("读取 · 需操作确认", zxCapabilityPermissionLabel("READ_ONLY", "MUTATION"))
        assertEquals("设备操作 · 需操作确认", zxCapabilityPermissionLabel("DEVICE", "MUTATION"))
        assertEquals("可修改 · 需操作确认", zxCapabilityPermissionLabel("MUTATION", "ALWAYS"))
        assertEquals("已启用", zxPluginStateLabel("ACTIVE"))
        assertEquals("需要修复", zxPluginStateLabel("FAILED"))
    }
    @Test fun everySettingsDestinationGetsAStableIconAndUnknownRowsRemainPlain() {
        val entries = listOf("外观", "聊天风格", "个人规则", "记忆", "模型",
            "模型性能", "ChatGPT 账户", "套餐额度", "操作审批模式", "权限与设备",
            "通知", "用量统计", "会话存储", "高级设置", "诊断与网络", "检查更新")
        assertTrue(entries.all { !zxSettingsNavigationIcon(it).isNullOrBlank() })
        assertEquals("palette", zxSettingsNavigationIcon("外观"))
        assertEquals("quota", zxSettingsNavigationIcon("模型性能"))
        assertNull(zxSettingsNavigationIcon("未知未来目的地"))
    }
    @Test fun workspaceAndUsagePresentationDoesNotExposeRawInternalIdentifiers() {
        assertEquals("PDF 文档", zxWorkspaceKindLabel("exports/report.pdf"))
        assertEquals("图片", zxWorkspaceKindLabel("output/photo.png"))
        assertEquals("工作任务", zxUsageActionLabel(TokenUsageAction.WORK_MAIN))
        assertEquals("图像理解", zxUsageActionLabel(TokenUsageAction.VISION))
    }
}
