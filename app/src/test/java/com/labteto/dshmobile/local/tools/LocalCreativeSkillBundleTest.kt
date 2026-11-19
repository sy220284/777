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
}
