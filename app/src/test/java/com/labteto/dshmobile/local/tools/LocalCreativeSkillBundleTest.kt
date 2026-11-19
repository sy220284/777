package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalCreativeSkillBundleTest {
    private fun bundle(id: String): Map<String, ByteArray> {
        val root = listOf(
            File("src/main/assets/creative-skills/$id"),
            File("app/src/main/assets/creative-skills/$id"),
        ).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to it.readBytes()
        }
    }

    @Test fun installsAllFourSkillsWithOriginalReferencesAndBinaryAssets() {
        val root = Files.createTempDirectory("creative-skills").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, creativeBundleFiles = ::bundle)
            store.seedOnce()
            val ids = LocalCreativeSkillCatalog.entries.map { it.id }
            assertEquals(ids.toSet(), LocalCreativeSkillCatalog.bundleRevisions.keys)
            assertEquals(setOf("finesse-ui", "finesse-brief", "finesse-term", "frame-smith"), ids.toSet())
            assertTrue(workspace.skills().containsAll(ids))
            assertTrue(store.presets().filter { it.id in ids }.all { it.installed })
            val catalog = workspace.modelSkillCatalog(limit = 100)
            ids.forEach { id ->
                val source = bundle(id)
                val folder = File(root, ".dsh/skills/$id")
                assertEquals(source.size + 1, folder.walkTopDown().count {
                    it.isFile && !it.name.startsWith(".bundled-")
                })
                assertTrue(catalog.contains(id))
                assertTrue(workspace.readModelSkill(id).contains("display-name:"))
                assertArrayEquals(source.getValue("SKILL.md"),
                    File(folder, "UPSTREAM-SKILL.md").readBytes())
                source.filterKeys { it != "SKILL.md" }.forEach { (path, bytes) ->
                    assertArrayEquals(bytes, File(folder, path).readBytes())
                }
            }
            val rule = workspace.readSkillSnapshot("finesse-ui")
            assertTrue(workspace.readSkillResource(
                "finesse-ui", "references/product-ui.md", rule.revision,
            ).contains("技能专项资料"))
            assertTrue(File(root,
                ".dsh/skills/finesse-ui/examples/apple/media/product-study-10.jpg"
            ).length() > 100_000)
        } finally { root.deleteRecursively() }
    }

    @Test fun upgradePreservesUserChangesDisablingAndRemoval() {
        val root = Files.createTempDirectory("creative-skill-upgrade").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, creativeBundleFiles = ::bundle)
            store.seedOnce()
            val changed = ".dsh/skills/finesse-term/data/web.json"
            workspace.write(changed, workspace.readRaw(changed) + "\n用户术语\n")
            val customized = store.readDocument("finesse-brief") + "\n个人规则\n"
            store.updateDocument("finesse-brief", customized)
            store.setModelInvocable("frame-smith", enabled = false)
            store.remove("finesse-ui")
            store.seedOnce()
            assertEquals(customized.trimEnd() + "\n", store.readDocument("finesse-brief"))
            assertTrue(workspace.readRaw(changed).contains("用户术语"))
            assertFalse(workspace.skills().contains("finesse-ui"))
            assertFalse(workspace.skillMetadata("frame-smith").modelInvocable)
            store.install("finesse-ui")
            assertTrue(workspace.skills().contains("finesse-ui"))
        } finally { root.deleteRecursively() }
    }

    @Test fun missingBundleFailsBeforePublishingSkillAndSeedMarker() {
        val root = Files.createTempDirectory("creative-skill-missing").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, creativeBundleFiles = { mapOf("LICENSE" to byteArrayOf(1)) })
            assertThrows(IllegalArgumentException::class.java) { store.seedOnce() }
            assertFalse(workspace.skills().contains("finesse-ui"))
            assertFalse(File(root, ".dsh/skills/.creative-seed-v1").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun userOwnedCollidingSkillIsNotOverwritten() {
        val root = Files.createTempDirectory("creative-skill-collision").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val userContent = buildLocalSkillDocument("finesse-ui", "用户独立的技能", "用户自己的规则")
            workspace.write(".dsh/skills/finesse-ui/SKILL.md", userContent)
            val store = LocalSkillStore(workspace, creativeBundleFiles = ::bundle)
            store.seedOnce()
            assertEquals(userContent, store.readDocument("finesse-ui"))
            assertFalse(File(root, ".dsh/skills/finesse-ui/.bundled-baseline-sha256").exists())
            store.seedOnce()
            assertEquals(userContent, store.readDocument("finesse-ui"))
        } finally { root.deleteRecursively() }
    }

    @Test fun unchangedAppReleaseDoesNotReloadLargeAssetTrees() {
        val root = Files.createTempDirectory("creative-skill-fast-startup").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val reads = mutableMapOf<String, Int>()
            val store = LocalSkillStore(workspace, creativeBundleFiles = { id ->
                reads[id] = reads.getOrDefault(id, 0) + 1
                bundle(id)
            })
            store.seedOnce()
            assertEquals(4, reads.values.sum())
            store.seedOnce()
            assertEquals(4, reads.values.sum())
        } finally { root.deleteRecursively() }
    }

    @Test fun chineseNamesSummariesAndGuidesAreVisibleToTheModelAndSkillCenter() {
        val root = Files.createTempDirectory("creative-skills-chinese").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val store = LocalSkillStore(workspace, creativeBundleFiles = ::bundle)
            store.seedOnce()
            for (preset in LocalCreativeSkillCatalog.entries) {
                val registered = store.installed().single { it.name == preset.id }
                assertEquals(preset.title, registered.displayName)
                assertTrue(registered.description.startsWith("适用范围："))
                assertTrue(registered.description.contains("使用方式："))
                val source = workspace.readModelSkill(preset.id)
                assertTrue(source.contains("display-name: \"${preset.title}\""))
                assertTrue(source.contains("## 中文使用指南"))
                assertTrue(source.contains("**适用范围：**"))
                assertTrue(source.contains("**使用方式：**"))
                assertTrue(source.contains("**使用边界：**"))
                assertTrue(workspace.modelSkillCatalog(limit = 100).contains(preset.id))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun updatingOnlyChineseGuideDoesNotReloadAssetsOrOverrideUserSettings() {
        val root = Files.createTempDirectory("creative-skills-guide-upgrade").toFile()
        try {
            val workspace = LocalWorkspace(root)
            val reads = mutableMapOf<String, Int>()
            fun load(id: String): Map<String, ByteArray> {
                reads[id] = reads.getOrDefault(id, 0) + 1
                return bundle(id)
            }
            val store = LocalSkillStore(workspace, creativeBundleFiles = ::load)
            store.seedOnce()
            assertEquals(4, reads.values.sum())

            val preset = LocalCreativeSkillCatalog.entries.first { it.id == "finesse-ui" }
            val modified = preset.copy(
                title = "界面视觉与交互设计（升级）",
                description = "适用范围：界面设计与审查。使用方式：提供目标页面与需求。",
                instructions = preset.instructions + "\n新增中文指南条目。",
            )
            val original = File(root, ".dsh/skills/finesse-ui/UPSTREAM-SKILL.md").readBytes()
            store.setModelInvocable(preset.id, false)
            LocalCreativeSkillBundle(workspace, ::load).refresh(modified)
            assertEquals(4, reads.values.sum())
            assertEquals(modified.title, store.installed().single { it.name == preset.id }.displayName)
            assertTrue(store.readDocument(preset.id).contains("新增中文指南条目"))
            assertFalse(workspace.skillMetadata(preset.id).modelInvocable)
            assertArrayEquals(original, File(root, ".dsh/skills/finesse-ui/UPSTREAM-SKILL.md").readBytes())
            LocalCreativeSkillBundle(workspace, ::load).refresh(modified)
            assertEquals(4, reads.values.sum())

            store.setModelInvocable(preset.id, true)
            store.updateDocument(preset.id, store.readDocument(preset.id) + "\n用户自己的说明\n")
            val next = modified.copy(title = "又一版名称")
            LocalCreativeSkillBundle(workspace, ::load).refresh(next)
            assertTrue(store.readDocument(preset.id).contains("用户自己的说明"))
            assertEquals(modified.title, store.installed().single { it.name == preset.id }.displayName)
        } finally { root.deleteRecursively() }
    }
}
