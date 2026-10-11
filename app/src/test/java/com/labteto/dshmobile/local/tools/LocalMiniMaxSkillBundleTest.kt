package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalMiniMaxSkillBundleTest {
    private fun bundle(id: String): Map<String, String> {
        val root = listOf(File("src/main/assets/minimax-skills/$id"), File("app/src/main/assets/minimax-skills/$id"))
            .first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to it.readText()
        }
    }

    @Test fun upgradeInstallsCompleteBundlesAndPreservesEditsAndDeletions() {
        val root = Files.createTempDirectory("minimax-skills").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillStore(workspace).seedOnce()
            LocalSkillStore(workspace).remove("research-check")
            val store = LocalSkillStore(workspace, ::bundle)
            store.seedOnce()
            assertEquals(16, store.installed().size)
            assertFalse(workspace.skills().contains("research-check"))
            for (preset in LocalMiniMaxSkillCatalog.entries) {
                assertTrue(workspace.readModelSkill(preset.id).contains("MOBILE.md"))
                assertTrue(File(root, ".dsh/skills/${preset.id}/MOBILE.md").isFile)
                assertTrue(File(root, ".dsh/skills/${preset.id}/LICENSE").readText().contains("MIT License"))
                assertTrue(File(root, ".dsh/skills/${preset.id}/SOURCE.md").readText().contains("60aaae52"))
            }
            store.updateDocument("minimax-xlsx", store.readDocument("minimax-xlsx") + "\n用户补充\n")
            store.remove("minimax-pptx")
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-pptx"))
            assertTrue(store.readDocument("minimax-xlsx").contains("用户补充"))
            store.install("minimax-pptx")
            assertTrue(workspace.readModelSkill("minimax-pptx").contains("演示文稿"))
            assertTrue(workspace.modelSkillCatalog(query = "Excel 表格", maxChars = 2200).contains("minimax-xlsx"))
        } finally { root.deleteRecursively() }
    }

    @Test fun desktopMigrationArchivesUserChangesAndUpgradesOnlyUntouchedGuides() {
        val root = Files.createTempDirectory("minimax-desktop-upgrade").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillStore(workspace).seedOnce()
            workspace.write(".dsh/skills/.minimax-seed-v1", "seeded\n")
            for (preset in LocalMiniMaxSkillCatalog.entries) {
                workspace.write(".dsh/skills/${preset.id}/SKILL.md", buildLocalSkillDocument(
                    preset.id, preset.description, LocalMiniMaxSkillCatalog.legacyInstructions(preset.id),
                    preset.whenToUse, preset.title))
            }
            workspace.write(".dsh/skills/minimax-ios/SKILL.md", "用户修改的旧技能\n")
            workspace.write(".dsh/skills/minimax-ios/notes.txt", "用户资料")
            val store = LocalSkillStore(workspace, ::bundle)
            store.remove("minimax-xlsx")
            val edited = store.readDocument("minimax-frontend") + "\n用户补充\n"
            store.updateDocument("minimax-frontend", edited)
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-ios"))
            assertFalse(workspace.modelSkillCatalog().contains("minimax-ios"))
            assertEquals("用户资料", File(root, ".dsh/retired-skills/minimax-mobile-v2/minimax-ios/notes.txt").readText())
            assertEquals("用户修改的旧技能\n", File(root, ".dsh/retired-skills/minimax-mobile-v2/minimax-ios/SKILL.md").readText())
            assertFalse(workspace.skills().contains("minimax-xlsx"))
            assertEquals(edited, store.readDocument("minimax-frontend"))
            assertTrue(store.readDocument("minimax-docx").contains("MOBILE.md"))
            assertFalse(store.readDocument("minimax-docx").contains("可按 OOXML 资料使用现有 Python 工具"))
            assertTrue(File(root, ".dsh/skills/minimax-docx/scripts/mobile_docx.py").isFile)
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-ios"))
            assertTrue(File(root, ".dsh/skills/.minimax-mobile-v2").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteBundleDoesNotAdvertiseInstalledSkillOrFinishMigration() {
        val root = Files.createTempDirectory("minimax-skills-failure").toFile()
        try {
            val store = LocalSkillStore(LocalWorkspace(root)) { mapOf("LICENSE" to "MIT") }
            assertThrows(IllegalArgumentException::class.java) { store.seedOnce() }
            assertFalse(File(root, ".dsh/skills/.minimax-seed-v1").exists())
            assertFalse(File(root, ".dsh/skills/minimax-frontend/SKILL.md").exists())
        } finally { root.deleteRecursively() }
    }
}
