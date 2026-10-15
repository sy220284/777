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

    @Test fun installsOnlyMobileMediaSkillsAndUpdatesBuiltIns() {
        val root = Files.createTempDirectory("minimax-media-skills").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillStore(workspace).seedOnce()
            LocalSkillStore(workspace).remove("research-check")
            val store = LocalSkillStore(workspace, ::bundle)
            store.seedOnce()
            assertEquals(11, store.installed().size) // 8 general (after one removal) + 3 mobile media
            assertFalse(workspace.skills().contains("research-check"))
            assertEquals(setOf("minimax-media", "minimax-music-gen", "minimax-playlist"),
                LocalMiniMaxSkillCatalog.entries.map { it.id }.toSet())
            LocalMiniMaxSkillCatalog.retiredDesktopAdaptations.forEach { id ->
                assertFalse(workspace.skills().contains(id))
                assertFalse(workspace.modelSkillCatalog().contains(id))
            }
            for (preset in LocalMiniMaxSkillCatalog.entries) {
                assertTrue(workspace.readModelSkill(preset.id).contains("用途："))
                assertTrue(File(root, ".dsh/skills/${preset.id}/MOBILE.md").isFile)
                assertTrue(File(root, ".dsh/skills/${preset.id}/LICENSE").readText().contains("MIT License"))
            }
            store.updateDocument("minimax-music-gen", store.readDocument("minimax-music-gen") + "\n用户补充\n")
            store.remove("minimax-playlist")
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-playlist"))
            assertFalse(store.readDocument("minimax-music-gen").contains("用户补充"))
            assertTrue(store.readDocument("minimax-music-gen").contains("用途："))
            store.install("minimax-playlist")
            assertTrue(workspace.readModelSkill("minimax-playlist").contains("歌单"))
        } finally { root.deleteRecursively() }
    }

    @Test fun archivesRetiredDesktopAdaptationsIncludingUserFilesWithoutReinstall() {
        val root = Files.createTempDirectory("minimax-retire").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillStore(workspace).seedOnce()
            workspace.write(".dsh/skills/minimax-docx/SKILL.md", "用户修改的旧技能\n")
            workspace.write(".dsh/skills/minimax-docx/user-notes.md", "保留这份文档")
            workspace.write(".dsh/skills/minimax-pptx/SKILL.md", "旧版演示文稿")
            workspace.write(".dsh/skills/minimax-ios/SKILL.md", "用户修改的更早技能\n")
            val store = LocalSkillStore(workspace, ::bundle)
            store.seedOnce()
            for (id in listOf("minimax-docx", "minimax-pptx", "minimax-ios")) {
                assertFalse(workspace.skills().contains(id))
            }
            assertEquals("保留这份文档",
                File(root, ".dsh/retired-skills/minimax-desktop-v3/minimax-docx/user-notes.md").readText())
            assertEquals("用户修改的旧技能\n",
                File(root, ".dsh/retired-skills/minimax-desktop-v3/minimax-docx/SKILL.md").readText())
            assertTrue(File(root, ".dsh/retired-skills/minimax-mobile-v2/minimax-ios/SKILL.md").isFile)
            store.seedOnce()
            assertFalse(workspace.skills().contains("minimax-docx"))
            assertTrue(File(root, ".dsh/skills/.minimax-retired-desktop-v3").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun existingMediaSkillAlwaysUpgradesToBundledVersion() {
        val root = Files.createTempDirectory("minimax-guides-current").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillStore(workspace).seedOnce()
            workspace.write(".dsh/skills/minimax-media/SKILL.md", "旧版本说明或个人改写")
            workspace.write(".dsh/skills/minimax-music-gen/SKILL.md", "旧版音乐说明")
            workspace.write(".dsh/skills/my-owned/SKILL.md", "---\\ndescription: owned\\n---\\n私有技能")
            val store = LocalSkillStore(workspace, ::bundle)
            store.seedOnce()
            assertTrue(store.readDocument("minimax-media").contains("用途："))
            assertTrue(store.readDocument("minimax-music-gen").contains("用途："))
            assertTrue(workspace.readSkill("my-owned").contains("私有技能"))
            workspace.write(".dsh/skills/minimax-media/SKILL.md", "任意旧内容")
            store.seedOnce()
            assertTrue(store.readDocument("minimax-media").contains("用途："))
        } finally { root.deleteRecursively() }
    }

    @Test fun editedMobileAndCliReferencesSurviveSeedRefresh() {
        val root = Files.createTempDirectory("minimax-user-references").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, ::bundle)
            store.seedOnce()
            val mobile = ".dsh/skills/minimax-media/MOBILE.md"
            val cli = ".dsh/skills/minimax-media/CLI.md"
            val editedMobile = workspace.readRaw(mobile) + "\n用户自定安卓说明\n"
            val editedCli = workspace.readRaw(cli) + "\n用户 CLI 备忘\n"
            workspace.write(mobile, editedMobile)
            workspace.write(cli, editedCli)
            store.seedOnce()
            assertEquals(editedMobile, workspace.readRaw(mobile))
            assertEquals(editedCli, workspace.readRaw(cli))
            assertTrue(workspace.readModelSkill("minimax-media").contains("原生媒体"))
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteMediaBundleDoesNotAdvertiseSkill() {
        val root = Files.createTempDirectory("minimax-media-incomplete").toFile()
        try {
            val store = LocalSkillStore(LocalWorkspace(root)) { mapOf("LICENSE" to "MIT") }
            assertThrows(IllegalArgumentException::class.java) { store.seedOnce() }
            assertFalse(File(root, ".dsh/skills/minimax-media/SKILL.md").isFile)
            assertFalse(File(root, ".dsh/skills/.minimax-seed-v1").exists())
        } finally { root.deleteRecursively() }
    }
}
