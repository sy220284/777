package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxAutomationTaskMatches
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxHumanFileSize
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxToolFamilyLabel
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxWorkspaceFileVisible
import org.junit.Assert.*
import org.junit.Test

class ZhaoxiHistoryUiPresentationTest {
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
}
