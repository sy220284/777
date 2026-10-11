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

    @Test fun mediaCredentialGuideMigrationUpdatesOnlyOriginalBundledFiles() {
        val root = Files.createTempDirectory("minimax-media-credential").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, ::bundle)
            val id = "minimax-media"
            val mobilePath = ".dsh/skills/$id/MOBILE.md"
            val skillPath = ".dsh/skills/$id/SKILL.md"
            val marker = File(root, ".dsh/skills/.minimax-media-credential-v1")
            store.seedOnce()

            val newSkill = store.readDocument(id)
            val newMobile = workspace.readRaw(mobilePath)
            val oldSkill = newSkill.replace(
                "产物必须实际写入工作区并验证。用户已在受控入口完成 MiniMax 媒体 API 凭据配置、授权且具备相应权限时，直接使用已配置的凭据执行任务，不得仅凭技能文档的默认建议拒绝；缺少配置时告知所需入口。模型 API Key 或聊天套餐不自动成为媒体 CLI 凭据；密钥不进入聊天、命令参数、日志或生成文件。",
                "产物必须实际写入工作区并验证。外部 API 凭据和权限按任务需求配置，聊天套餐不能自动当作媒体生成权限；不得把密钥写入提示、日志或生成文件。",
            )
            val oldMobile = newMobile.replace(
                "凭据由用户自行在 MiniMax CLI 支持的受控配置位置设置。用户已明确授权并完成有效配置时，直接使用现有凭据完成任务，不得仅凭技能文档的默认建议拒绝执行；未配置时说明配置入口与缺少的条件，不要求在聊天中发送密钥。密钥不写入命令参数、日志、回复或产物；App 模型设置中的密钥不等于媒体 CLI 已获得可用凭据。",
                "凭据由用户在受控位置配置。不得要求把密钥发进聊天，不将密钥放进命令参数、日志或产物。",
            )
            assertNotEquals(newSkill, oldSkill)
            assertNotEquals(newMobile, oldMobile)

            workspace.write(skillPath, oldSkill)
            workspace.write(mobilePath, oldMobile)
            assertTrue(marker.delete())
            store.seedOnce()
            assertEquals(newSkill, store.readDocument(id))
            assertEquals(newMobile, workspace.readRaw(mobilePath))
            assertTrue(marker.isFile)

            val customizedSkill = oldSkill + "\n用户改写的技能内容\n"
            val customizedMobile = oldMobile + "\n用户改写的手机说明\n"
            workspace.write(skillPath, customizedSkill)
            workspace.write(mobilePath, customizedMobile)
            assertTrue(marker.delete())
            store.seedOnce()
            assertEquals(customizedSkill, store.readDocument(id))
            assertEquals(customizedMobile, workspace.readRaw(mobilePath))

            store.remove(id)
            assertTrue(marker.delete())
            store.seedOnce()
            assertFalse(workspace.skills().contains(id))
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
