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
        store.create("my-review", "检查文稿", "先核对内容，再给出修订稿。")
        assertTrue(workspace.modelSkillCatalog().contains("my-review：检查文稿"))
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
            store.create("../outside", "路径穿越", "恶意文本")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create("unsafe", "多行\nother: true", "说明")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create("bad", "", "说明")
        }
        store.create("safe", "安全技能", "工作规则")
        assertThrows(IllegalArgumentException::class.java) {
            store.create("safe", "第二个", "不应覆盖")
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
}
