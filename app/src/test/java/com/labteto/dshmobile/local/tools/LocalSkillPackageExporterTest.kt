package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillPackageExporterTest {
    private val document = """
        ---
        name: share-example
        description: 分享测试技能
        ---
        # 规则
        按来源整理事实。
    """.trimIndent() + "\n"

    @Test fun exportedBundleRoundTripsRulesAndSupportingFiles() {
        val source = Files.createTempDirectory("skill-export-source").toFile()
        val target = Files.createTempDirectory("skill-export-target").toFile()
        try {
            val origin = LocalWorkspace(source)
            LocalSkillPackageImporter(origin).install("SKILL.md", document.toByteArray())
            origin.write(".dsh/skills/share-example/references/source.md", "参考文献")
            origin.write(".dsh/skills/share-example/scripts/check.py", "print('safe')")
            val bytes = LocalSkillPackageExporter(origin).export("share-example")
            assertArrayEquals(bytes, LocalSkillPackageExporter(origin).export("share-example"))
            val restored = LocalWorkspace(target)
            assertEquals("share-example", LocalSkillPackageImporter(restored).install("shared.zip", bytes))
            assertEquals(document, restored.readRaw(".dsh/skills/share-example/SKILL.md"))
            assertEquals("参考文献", restored.readRaw(".dsh/skills/share-example/references/source.md"))
            assertEquals("print('safe')", restored.readRaw(".dsh/skills/share-example/scripts/check.py"))
        } finally {
            source.deleteRecursively()
            target.deleteRecursively()
        }
    }

    @Test fun missingSkillAndSymlinkCannotBeShared() {
        val root = Files.createTempDirectory("skill-export-links").toFile()
        val external = Files.createTempFile("skill-export-outside", ".txt")
        try {
            val workspace = LocalWorkspace(root)
            assertThrows(IllegalArgumentException::class.java) {
                LocalSkillPackageExporter(workspace).export("share-example")
            }
            LocalSkillPackageImporter(workspace).install("SKILL.md", document.toByteArray())
            val link = root.resolve(".dsh/skills/share-example/references/secret.txt").toPath()
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, external)
            assertThrows(IllegalArgumentException::class.java) {
                LocalSkillPackageExporter(workspace).export("share-example")
            }
        } finally {
            root.deleteRecursively()
            Files.deleteIfExists(external)
        }
    }

    @Test fun largeSkillsExportAndImportWithoutLegacyLimits() {
        val root = Files.createTempDirectory("skill-export-large").toFile()
        val target = Files.createTempDirectory("skill-export-large-target").toFile()
        try {
            val workspace = LocalWorkspace(root)
            LocalSkillPackageImporter(workspace).install("SKILL.md", document.toByteArray())
            val rules = document + "\n" + "规则资料".repeat(32_000)
            workspace.write(".dsh/skills/share-example/SKILL.md", rules)
            val payload = ByteArray(9 * 1024 * 1024).also { java.util.Random(33).nextBytes(it) }
            val asset = root.resolve(".dsh/skills/share-example/references/asset.bin")
            Files.createDirectories(asset.parentFile.toPath())
            Files.write(asset.toPath(), payload)
            val exported = Files.createTempFile("skill-export", ".zip").toFile()
            try {
                LocalSkillPackageExporter(workspace).exportToFile("share-example", exported)
                assertTrue(exported.length() > 8L * 1024 * 1024)
                val restored = LocalWorkspace(target)
                assertEquals("share-example", exported.inputStream().use {
                    LocalSkillPackageImporter(restored).install("share.zip", it)
                })
                assertEquals(rules, restored.readModelSkill("share-example"))
                assertEquals(payload.size.toLong(),
                    target.resolve(".dsh/skills/share-example/references/asset.bin").length())
            } finally {
                exported.delete()
            }
        } finally {
            root.deleteRecursively()
            target.deleteRecursively()
        }
    }

}
