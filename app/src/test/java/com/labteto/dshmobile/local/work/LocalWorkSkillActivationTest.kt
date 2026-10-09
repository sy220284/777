package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkSkillActivationTest {
    @Test
    fun chosenSkillIsLoadedBeforeModelRequestAndIsNotGuesswork() {
        val root = Files.createTempDirectory("selected-skill").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/writing-polish/SKILL.md",
                "---\ndescription: polish\n---\n# Rules\nKeep voice and meaning.\n")
            val context = resolveLocalWorkSkillGuidance(workspace, "@skill:writing-polish\n帮我修改这段文字")
            assertTrue(context.contains("[本轮技能已加载：writing-polish]"))
            assertTrue(context.contains("Keep voice and meaning."))
            assertFalse(context.contains("本地技能目录"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun userOnlyRequiresExplicitSelectionButRemovedSkillFails() {
        val root = Files.createTempDirectory("skill-disabled").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/private-notes/SKILL.md",
                "---\ndescription: private\ndisable-model-invocation: true\n---\nsecret")
            assertTrue(resolveLocalWorkSkillGuidance(workspace, "@skill:private-notes\n内容").contains("secret"))
            assertThrows(IllegalArgumentException::class.java) {
                resolveLocalWorkSkillGuidance(workspace, "@skill:missing-one\n内容")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ordinaryWorkPromptOffersDiscoveryWithoutForcingSkill() {
        val root = Files.createTempDirectory("skill-catalog").toFile()
        try {
            val workspace = LocalWorkspace(root)
            workspace.write(".dsh/skills/code-review/SKILL.md",
                "---\ndescription: Review code\nwhen-to-use: PR analysis\n---\nInspect call chains.")
            val context = resolveLocalWorkSkillGuidance(workspace, "请检查这个 PR")
            assertTrue(context.contains("code-review：Review code"))
            assertTrue(context.contains("适用：PR analysis"))
            assertFalse(context.contains("Inspect call chains."))
        } finally {
            root.deleteRecursively()
        }
    }
}
