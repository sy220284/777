package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillStoreTest {
    private fun inWorkspace(block: (LocalSkillStore, LocalWorkspace, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("skills-store-test").toFile()
        try {
            val workspace = LocalWorkspace(root)
            block(LocalSkillStore(workspace), workspace, root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundledSkillsAreRealInvocableInstructions() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        val all = store.presets()
        assertEquals(8, all.size)
        assertTrue(all.all { it.installed })
        assertEquals(8, all.map { it.id }.distinct().size)
        assertEquals(8, store.installed().size)
        for (preset in all) {
            val content = workspace.readModelSkill(preset.id)
            assertTrue(content.contains("description:"))
            assertTrue(content.contains(preset.instructions.lineSequence().first()))
        }
    }

    @Test
    fun removingPresetSurvivesRestartAndCanBeReinstalled() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        store.remove("research-check")
        assertFalse(store.presets().first { it.id == "research-check" }.installed)
        assertFalse(workspace.modelSkillCatalog().contains("research-check"))
        store.seedOnce()
        assertFalse(store.presets().first { it.id == "research-check" }.installed)
        store.install("research-check")
        assertTrue(store.presets().first { it.id == "research-check" }.installed)
        assertThrows(IllegalArgumentException::class.java) { store.install("research-check") }
    }

    @Test
    fun customSkillIsVisibleUntilRemovedAndItsSupportingFilesSurvive() = inWorkspace { store, workspace, root ->
        store.create("my-review", "文稿审查", "检查文稿", "先核对内容，再给出修订稿。")
        assertTrue(workspace.modelSkillCatalog().contains("my-review：检查文稿"))
        assertEquals("文稿审查", store.installed().first { it.name == "my-review" }.displayName)
        val extra = root.resolve(".dsh/skills/my-review/notes.txt")
        extra.writeText("保留")
        store.remove("my-review")
        assertFalse(workspace.skills().contains("my-review"))
        assertTrue(extra.exists())
        assertEquals("保留", extra.readText())
        assertThrows(IllegalArgumentException::class.java) { workspace.readModelSkill("my-review") }
    }

    @Test
    fun invalidAndUnsafeInputsCannotOverwriteExistingSkills() = inWorkspace { store, workspace, root ->
        assertThrows(IllegalArgumentException::class.java) {
            store.create("../outside", "测试技能", "路径穿越", "恶意文本")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create("unsafe", "测试技能", "多行\nother: true", "说明")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create("bad", "测试技能", "", "说明")
        }
        store.create("safe", "测试技能", "安全技能", "工作规则")
        assertThrows(IllegalArgumentException::class.java) {
            store.create("safe", "测试技能", "第二个", "不应覆盖")
        }
        assertTrue(workspace.readRaw(".dsh/skills/safe/SKILL.md").contains("安全技能"))
        assertFalse(root.resolve("outside/SKILL.md").exists())
    }

    @Test
    fun removingSymlinkNeverDeletesTarget() = inWorkspace { store, _, root ->
        val external = Files.createTempDirectory("skill-external").toFile()
        try {
            external.resolve("SKILL.md").writeText("target")
            val parent = root.resolve(".dsh/skills").apply { mkdirs() }
            Files.createSymbolicLink(parent.toPath().resolve("external-skill"), external.toPath())
            assertThrows(IllegalArgumentException::class.java) { store.remove("external-skill") }
            assertEquals("target", external.resolve("SKILL.md").readText())
        } finally {
            external.deleteRecursively()
        }
    }
    @Test
    fun editingAndModelInvocationToggleUseTheSameSkillFile() = inWorkspace { store, workspace, _ ->
        store.create("edited-one", "测试技能", "编辑前", "Original instructions.")
        val original = store.readDocument("edited-one")
        store.updateDocument("edited-one", original.replace("Original instructions.", "Revised instructions."))
        assertTrue(workspace.readModelSkill("edited-one").contains("Revised instructions."))
        store.setModelInvocable("edited-one", false)
        assertFalse(store.installed().first { it.name == "edited-one" }.modelInvocable)
        assertThrows(IllegalArgumentException::class.java) { workspace.readModelSkill("edited-one") }
        store.setModelInvocable("edited-one", true)
        assertTrue(workspace.readModelSkill("edited-one").contains("Revised instructions."))
    }

    @Test
    fun disabledHeaderlessSkillPreservesBody() = inWorkspace { store, workspace, _ ->
        workspace.write(".dsh/skills/plain-note/SKILL.md", "# Keep this\nDo work")
        store.setModelInvocable("plain-note", false)
        assertTrue(store.readDocument("plain-note").contains("Do work"))
        assertFalse(store.installed().first { it.name == "plain-note" }.modelInvocable)
        store.setModelInvocable("plain-note", true)
        assertTrue(workspace.readModelSkill("plain-note").contains("Do work"))
    }

    @Test
    fun editedAndRemovedPresetsStayUnchangedOnSeedRestart() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        store.updateDocument("code-review", store.readDocument("code-review") + "\n用户自定义后续规则\n")
        store.remove("research-check")
        store.seedOnce()
        assertTrue(store.readDocument("code-review").contains("用户自定义后续规则"))
        assertFalse(workspace.skills().contains("research-check"))
    }


    @Test
    fun existingCustomSkillReadsChineseHeadingAndRefreshesAfterRename() = inWorkspace { store, workspace, _ ->
        workspace.write(".dsh/skills/legacy-note/SKILL.md", "---\ndescription: notes\n---\n# 故事规划\n内容")
        assertEquals("故事规划", store.installed().first { it.name == "legacy-note" }.displayName)
        store.updateDocument("legacy-note",
            "---\ndescription: notes\ndisplay-name: \"长篇创作\"\n---\n# 故事规划\n内容")
        assertEquals("长篇创作", store.installed().first { it.name == "legacy-note" }.displayName)
        assertEquals(true, workspace.readModelSkill("legacy-note").contains("内容"))
    }

    @Test
    fun presetTitleWorksForHistoricalInstalledDocumentWithoutNewField() = inWorkspace { store, workspace, _ ->
        workspace.write(".dsh/skills/data-insights/SKILL.md", "---\ndescription: analyze\n---\n# data-insights\nAnalyze")
        assertEquals("数据分析", store.installed().first { it.name == "data-insights" }.displayName)
    }
}
