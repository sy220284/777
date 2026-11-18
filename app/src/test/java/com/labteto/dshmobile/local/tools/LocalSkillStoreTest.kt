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
        val all = store.presets().filterNot { it.id in LocalMiniMaxSkillCatalog.entries.map { item -> item.id } }
        assertEquals(2, all.size)
        assertTrue(all.all { it.installed })
        assertEquals(2, all.map { it.id }.distinct().size)
        assertEquals(2, store.installed().size)
        for (preset in all) {
            val content = workspace.readModelSkill(preset.id)
            assertTrue(content.contains("description:"))
            assertTrue(content.contains(preset.instructions.lineSequence().first()))
        }
    }

    @Test
    fun removingPresetSurvivesRestartAndCanBeReinstalled() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        store.remove("code-review")
        assertFalse(store.presets().first { it.id == "code-review" }.installed)
        assertFalse(workspace.modelSkillCatalog().contains("code-review"))
        store.seedOnce()
        assertFalse(store.presets().first { it.id == "code-review" }.installed)
        store.install("code-review")
        assertTrue(store.presets().first { it.id == "code-review" }.installed)
        assertThrows(IllegalArgumentException::class.java) { store.install("code-review") }
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
    fun inaccessiblePresetCannotBeReplacedDuringStartupRefresh() = inWorkspace { store, _, root ->
        store.seedOnce()
        val original = root.resolve(".dsh/skills/code-review/SKILL.md")
        val before = original.readText()
        val baseline = root.resolve(".dsh/skills/code-review/.bundled-baseline-sha256")
        baseline.delete()
        baseline.mkdir()
        assertThrows(IllegalStateException::class.java) { store.seedOnce() }
        assertTrue(baseline.isDirectory)
        assertEquals(before, original.readText())
    }

    @Test
    fun damagedLargePresetRetainsExactBackupBeforeRepair() = inWorkspace { store, _, root ->
        store.seedOnce()
        val original = root.resolve(".dsh/skills/imagegen/SKILL.md")
        val damaged = "broken\n".repeat(19_000)
        original.writeText(damaged)
        store.seedOnce()
        assertEquals(damaged, root.resolve(".dsh/skills/imagegen/SKILL.pre-repair.md").readText())
        assertTrue(store.readDocument("imagegen").contains("用途："))
    }

    @Test
    fun customizedOfficialPresetsStayEditedWhileRemovedOnesStayRemoved() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        store.updateDocument("code-review", store.readDocument("code-review") + "\n用户自定义后续规则\n")
        store.remove("imagegen")
        store.seedOnce()
        assertTrue(store.readDocument("code-review").contains("用户自定义后续规则"))
        assertTrue(store.readDocument("code-review").contains("用途："))
        assertFalse(workspace.skills().contains("imagegen"))
    }


    @Test
    fun disablingAnOfficialSkillSurvivesVersionRefresh() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        store.setModelInvocable("code-review", false)
        store.updateDocument("code-review", store.readDocument("code-review") + "\n旧附加说明\n")
        store.seedOnce()
        assertFalse(store.installed().first { it.name == "code-review" }.modelInvocable)
        assertTrue(store.readDocument("code-review").contains("旧附加说明"))
        assertTrue(store.readDocument("code-review").contains("用途："))
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
        workspace.write(".dsh/skills/code-review/SKILL.md", "---\ndescription: review\n---\n# code-review\nReview")
        assertEquals("代码审查", store.installed().first { it.name == "code-review" }.displayName)
    }
    @Test fun validLongSkillPreservesTailAcrossUiModelAndSelectedTurn() = inWorkspace { store, workspace, _ ->
        val body = "x\n".repeat(900) + "禁止删除原始文件"
        store.create("long-rules", "长文规则", "完整读取", body)
        assertTrue(workspace.readModelSkill("long-rules").endsWith("禁止删除原始文件\n"))
        val prompt = com.labteto.dshmobile.local.work.resolveLocalWorkSkillGuidance(workspace, "@skill:long-rules\n执行")
        assertTrue(prompt.contains("禁止删除原始文件"))
        assertEquals("完整读取", store.installed().single().description)
    }

    @Test fun untouchedOfficialPresetRefreshesWhenBundledRuleChanges() = inWorkspace { store, workspace, root ->
        store.seedOnce()
        val current = store.readDocument("code-review")
        val baseline = root.resolve(".dsh/skills/code-review/.bundled-baseline-sha256")
        assertTrue(baseline.isFile)
        // Simulate an older upstream release: content and its recorded hash agree.
        val previous = current.replace("代码审查", "旧版审查")
        workspace.write(".dsh/skills/code-review/SKILL.md", previous)
        val checksum = java.security.MessageDigest.getInstance("SHA-256")
            .digest(previous.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        baseline.writeText(checksum + "\n")
        store.seedOnce()
        assertEquals(current, store.readDocument("code-review"))
    }

    @Test fun unknownLegacyEditsAreNotSilentlyReplaced() = inWorkspace { store, workspace, root ->
        store.seedOnce()
        root.resolve(".dsh/skills/code-review/.bundled-baseline-sha256").delete()
        val edited = store.readDocument("code-review") + "\n旧版本用户自己的规则\n"
        workspace.write(".dsh/skills/code-review/SKILL.md", edited)
        store.seedOnce()
        assertEquals(edited.trimEnd() + "\n", store.readDocument("code-review"))
    }

    @Test fun locallyCustomizedOfficialSkillBeyondUiEditLimitIsNotOverwritten() = inWorkspace { store, workspace, _ ->
        store.seedOnce()
        val original = store.readDocument("code-review")
        val edited = original + "\\n" + "这是用户手动维护的规则。".repeat(2500)
        assertTrue(edited.toByteArray(Charsets.UTF_8).size > 24_000)
        assertTrue(edited.toByteArray(Charsets.UTF_8).size < 128 * 1024)
        workspace.write(".dsh/skills/code-review/SKILL.md", edited)
        store.seedOnce()
        assertEquals(edited, workspace.readSkill("code-review"))
    }

    @Test fun oversizedOfficialSkillIsRepairedFromBundledVersion() = inWorkspace { store, workspace, root ->
        store.seedOnce()
        val bad = root.resolve(".dsh/skills/imagegen/SKILL.md")
        bad.writeText("x".repeat(128 * 1024 + 1))
        store.seedOnce()
        assertTrue(workspace.modelSkillCatalog().contains("code-review"))
        assertTrue(workspace.modelSkillCatalog().contains("imagegen"))
        assertTrue(store.installed().single { it.name == "imagegen" }.modelInvocable)
        assertTrue(workspace.readSkill("imagegen").contains("用途："))
    }

    @Test fun validCustomizedBundledSkillBeyond128KbIsNotReplaced() = inWorkspace { store, workspace, root ->
        store.seedOnce()
        val modified = store.readDocument("imagegen") + "\n" + "用户扩展规则。".repeat(20_000)
        val file = root.resolve(".dsh/skills/imagegen/SKILL.md")
        file.writeText(modified)
        assertTrue(file.length() > 128L * 1024)
        store.seedOnce()
        assertEquals(modified, workspace.readSkill("imagegen"))
        assertEquals(modified, store.readDocument("imagegen"))
        store.setModelInvocable("imagegen", false)
        assertTrue(!workspace.skillMetadata("imagegen").modelInvocable)
        assertTrue(store.readDocument("imagegen").contains("用户扩展规则"))
        store.setModelInvocable("imagegen", true)
        assertTrue(workspace.skillMetadata("imagegen").modelInvocable)
        assertTrue(store.readDocument("imagegen").length > 128 * 1024)
    }


    private fun bundledBaseline(document: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(document.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test fun obsoleteUntouchedBundledSkillIsUnregisteredWithoutHistoricalArchive() =
        inWorkspace { store, workspace, root ->
            val oldId = "old-bundled-example"
            val original = buildLocalSkillDocument(oldId, "已退出的内置规则", "旧指令")
            workspace.write(".dsh/skills/$oldId/SKILL.md", original)
            workspace.write(".dsh/skills/$oldId/.bundled-baseline-sha256", bundledBaseline(original) + "\n")
            workspace.write(".dsh/skills/$oldId/user-notes.md", "用户自己留下的附件")
            workspace.write(".dsh/skills/handmade/SKILL.md",
                "---\ndescription: personal\n---\n用户自己安装的技能")

            assertEquals(setOf(oldId), store.seedOnce())
            assertFalse(workspace.skills().contains(oldId))
            assertFalse(workspace.modelSkillCatalog().contains(oldId))
            assertFalse(root.resolve(".dsh/skills/$oldId/SKILL.md").exists())
            assertFalse(root.resolve(".dsh/skills/$oldId/.bundled-baseline-sha256").exists())
            assertEquals("用户自己留下的附件",
                root.resolve(".dsh/skills/$oldId/user-notes.md").readText())
            assertFalse(root.resolve(".dsh/retired-skills/general-presets-v1").exists())
            assertTrue(workspace.skills().contains("handmade"))
            assertEquals(emptySet<String>(), store.seedOnce())
            assertFalse(workspace.skills().contains(oldId))
        }

    @Test fun locallyEditedFormerBundledSkillRemainsUserOwned() =
        inWorkspace { store, workspace, root ->
            val oldId = "former-customized-example"
            val original = buildLocalSkillDocument(oldId, "旧版说明", "官方规则")
            val edited = original + "\n用户自定义的新规则\n"
            workspace.write(".dsh/skills/$oldId/SKILL.md", edited)
            workspace.write(".dsh/skills/$oldId/.bundled-baseline-sha256", bundledBaseline(original) + "\n")
            assertEquals(emptySet<String>(), store.seedOnce())
            assertEquals(edited, workspace.readSkill(oldId))
            assertTrue(root.resolve(".dsh/skills/$oldId/.bundled-baseline-sha256").isFile)
            assertTrue(workspace.modelSkillCatalog().contains(oldId))
        }

    @Test fun obsoleteBundledSkillWithoutAttachmentsRemovesItsEmptyDirectory() =
        inWorkspace { store, workspace, root ->
            val id = "unlisted-example"
            val original = buildLocalSkillDocument(id, "过时预置", "旧规则")
            workspace.write(".dsh/skills/$id/SKILL.md", original)
            workspace.write(".dsh/skills/$id/.bundled-baseline-sha256", bundledBaseline(original))
            assertEquals(setOf(id), store.seedOnce())
            assertFalse(root.resolve(".dsh/skills/$id").exists())
        }

    @Test fun manuallyCreatedSkillSupportsLongInstructions() = inWorkspace { store, workspace, _ ->
        val instructions = "长篇技能规则。".repeat(20_000)
        store.create("long-custom-skill", "大型自定义技能", "包含大量引用和操作步骤", instructions)
        assertTrue(workspace.readSkill("long-custom-skill").contains(instructions))
        store.setModelInvocable("long-custom-skill", false)
        assertTrue(!workspace.skillMetadata("long-custom-skill").modelInvocable)
        store.setModelInvocable("long-custom-skill", true)
        assertTrue(workspace.readSkill("long-custom-skill").contains(instructions))
    }

}
