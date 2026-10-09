package com.labteto.dshmobile.ui.screens.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillDisplayNameDocumentTest {
    @Test
    fun editingExistingSkillUpdatesOnlyDisplayNameInItsSourceFile() {
        val original = "---\ndescription: details\nwhen-to-use: writing\n---\n# Instructions\nDo work"
        val updated = withLocalSkillDisplayName(original, "文学创作")
        assertTrue(updated.contains("display-name: \"文学创作\""))
        assertTrue(updated.contains("description: details"))
        assertTrue(updated.contains("when-to-use: writing"))
        assertTrue(updated.contains("# Instructions\nDo work"))
        assertEquals(1, updated.lines().count { it.startsWith("display-name:") })
        val renamed = withLocalSkillDisplayName(updated, "剧情设计")
        assertEquals(1, renamed.lines().count { it.startsWith("display-name:") })
        assertTrue(renamed.contains("display-name: \"剧情设计\""))
    }

    @Test
    fun headerlessLegacySkillGetsDisplayNameWithoutLosingInstructions() {
        val updated = withLocalSkillDisplayName("# 自定义能力\nRule", "任务管理")
        assertTrue(updated.startsWith("---\ndisplay-name: \"任务管理\"\n---\n"))
        assertTrue(updated.endsWith("# 自定义能力\nRule"))
    }
}
