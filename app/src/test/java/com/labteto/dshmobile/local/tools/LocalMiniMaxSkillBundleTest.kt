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
            assertEquals(22, store.installed().size)
            assertFalse(workspace.skills().contains("research-check"))
            for (preset in LocalMiniMaxSkillCatalog.entries) {
                assertTrue(workspace.readModelSkill(preset.id).contains("upstream/SKILL.md"))
                assertTrue(File(root, ".dsh/skills/${preset.id}/upstream/SKILL.md").isFile)
                assertTrue(File(root, ".dsh/skills/${preset.id}/LICENSE").readText().contains("MIT License"))
                assertTrue(File(root, ".dsh/skills/${preset.id}/SOURCE.md").readText().contains("60aaae52"))
            }
            store.updateDocument("minimax-xlsx", store.readDocument("minimax-xlsx") + "\n用户补充\n")
            store.remove("minimax-pdf")
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-pdf"))
            assertTrue(store.readDocument("minimax-xlsx").contains("用户补充"))
            store.install("minimax-pdf")
            assertTrue(workspace.readModelSkill("minimax-pdf").contains("PDF"))
            assertTrue(workspace.modelSkillCatalog(query = "Excel 表格", maxChars = 2200).contains("minimax-xlsx"))
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
