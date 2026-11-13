package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSkillPackageUpdaterTest {
    private fun doc(name: String, version: String) =
        "---\nname: $name\ndescription: 验证替换和恢复\n---\n# 规则\n$version\n"

    private fun archive(name: String, version: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("SKILL.md"))
            zip.write(doc(name, version).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("references/version.txt"))
            zip.write(version.toByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun replacesAndRestoresWithOneExplicitReversibleRevision() {
        val folder = Files.createTempDirectory("skill-update-roundtrip").toFile()
        try {
            val workspace = LocalWorkspace(folder)
            val importer = LocalSkillPackageImporter(workspace)
            val manager = LocalSkillPackageUpdater(workspace)
            importer.install("first.zip", archive("revision-demo", "第一版"))
            assertFalse(manager.canRollback("revision-demo"))
            assertEquals("revision-demo", manager.replace("revision-demo", archive("revision-demo", "第二版")))
            assertTrue(workspace.readRaw(".dsh/skills/revision-demo/SKILL.md").contains("第二版"))
            assertEquals("第一版", workspace.readRaw(".dsh/skill-rollback/revision-demo/references/version.txt"))
            assertTrue(manager.canRollback("revision-demo"))
            manager.rollback("revision-demo")
            assertTrue(workspace.readRaw(".dsh/skills/revision-demo/SKILL.md").contains("第一版"))
            manager.rollback("revision-demo")
            assertTrue(workspace.readRaw(".dsh/skills/revision-demo/SKILL.md").contains("第二版"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun invalidArchiveOrWrongSkillCannotOverwriteInstalledDirectory() {
        val folder = Files.createTempDirectory("skill-update-invalid").toFile()
        try {
            val workspace = LocalWorkspace(folder)
            LocalSkillPackageImporter(workspace).install("first.zip", archive("revision-demo", "第一版"))
            val manager = LocalSkillPackageUpdater(workspace)
            assertThrows(IllegalArgumentException::class.java) {
                manager.replace("revision-demo", archive("wrong-id", "第二版"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                manager.replace("revision-demo", byteArrayOf(1, 2, 3))
            }
            assertTrue(workspace.readRaw(".dsh/skills/revision-demo/SKILL.md").contains("第一版"))
            assertFalse(manager.canRollback("revision-demo"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun refusesSymlinksInCurrentOrRollbackDirectory() {
        val folder = Files.createTempDirectory("skill-update-symlink").toFile()
        val outside = Files.createTempFile("skill-update-outside", ".txt")
        try {
            val workspace = LocalWorkspace(folder)
            LocalSkillPackageImporter(workspace).install("first.zip", archive("revision-demo", "第一版"))
            val manager = LocalSkillPackageUpdater(workspace)
            val link = folder.resolve(".dsh/skills/revision-demo/link").toPath()
            Files.createSymbolicLink(link, outside)
            assertThrows(IllegalArgumentException::class.java) {
                manager.replace("revision-demo", archive("revision-demo", "第二版"))
            }
            Files.delete(link)
            manager.replace("revision-demo", archive("revision-demo", "第二版"))
            val backupLink = folder.resolve(".dsh/skill-rollback/revision-demo/link").toPath()
            Files.createSymbolicLink(backupLink, outside)
            assertThrows(IllegalArgumentException::class.java) { manager.rollback("revision-demo") }
            assertTrue(workspace.readRaw(".dsh/skills/revision-demo/SKILL.md").contains("第二版"))
        } finally {
            folder.deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }
}
