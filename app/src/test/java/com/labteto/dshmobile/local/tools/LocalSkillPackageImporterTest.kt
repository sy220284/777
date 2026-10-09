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

class LocalSkillPackageImporterTest {
    private val example = """
        ---
        name: sample-skill
        display-name: "样例技能"
        description: "整理素材"
        ---
        # 样例技能
        按来源整理事实和引用。
    """.trimIndent()

    private fun runInWorkspace(block: (LocalSkillPackageImporter, LocalWorkspace, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("skill-package-test").toFile()
        try {
            val workspace = LocalWorkspace(root)
            block(LocalSkillPackageImporter(workspace), workspace, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return result.toByteArray()
    }

    @Test fun installsMarkdownAndMakesItImmediatelyVisibleToModel() = runInWorkspace { importer, workspace, _ ->
        assertEquals("sample-skill", importer.install("SKILL.md", example.toByteArray()))
        assertTrue(workspace.skills().contains("sample-skill"))
        assertTrue(workspace.readModelSkill("sample-skill").contains("按来源整理事实"))
        assertThrows(IllegalArgumentException::class.java) {
            importer.install("SKILL.md", example.toByteArray())
        }
        assertTrue(workspace.readModelSkill("sample-skill").contains("按来源整理事实"))
    }

    @Test fun zipPreservesSupportingFilesFromOneSkillDirectory() = runInWorkspace { importer, workspace, root ->
        val zipped = archive(
            "skill-package/SKILL.md" to example.toByteArray(),
            "skill-package/references/notes.md" to "参考资料".toByteArray(),
            "skill-package/scripts/check.py" to "print('hello')".toByteArray(),
        )
        assertEquals("sample-skill", importer.install("bundle.zip", zipped))
        assertEquals("参考资料", root.resolve(".dsh/skills/sample-skill/references/notes.md").readText())
        assertTrue(root.resolve(".dsh/skills/sample-skill/scripts/check.py").isFile)
        assertTrue(workspace.skills().contains("sample-skill"))
    }

    @Test fun rejectsTraversalAndDoesNotExposePartialSkill() = runInWorkspace { importer, workspace, root ->
        val zipped = archive(
            "pkg/SKILL.md" to example.toByteArray(),
            "pkg/../../escape.txt" to "escape".toByteArray(),
        )
        assertThrows(IllegalArgumentException::class.java) { importer.install("pkg.zip", zipped) }
        assertFalse(root.resolve("escape.txt").exists())
        assertFalse(workspace.skills().contains("sample-skill"))
    }

    @Test fun rejectsMissingMetadataAndNeverMutatesCatalog() = runInWorkspace { importer, workspace, _ ->
        for (content in listOf("# 普通文档\n没有元数据", "---\nname: SAMPLE\n---\n规则", "---\nname: sample-skill\n---\n规则")) {
            assertThrows(IllegalArgumentException::class.java) { importer.install("SKILL.md", content.toByteArray()) }
        }
        assertTrue(workspace.skills().isEmpty())
    }

    @Test fun rejectsOversizedDocumentAndBombLikeArchive() = runInWorkspace { importer, workspace, _ ->
        val tooLong = example + "x".repeat(24_100)
        assertThrows(IllegalArgumentException::class.java) { importer.install("SKILL.md", tooLong.toByteArray()) }
        val huge = archive("SKILL.md" to example.toByteArray(), "huge.txt" to ByteArray(8 * 1024 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { importer.install("big.zip", huge) }
        assertTrue(workspace.skills().isEmpty())
    }

    @Test fun supportsSingleRootZipAndRejectsSiblings() = runInWorkspace { importer, workspace, _ ->
        assertThrows(IllegalArgumentException::class.java) {
            importer.install("pkg.zip", archive("bundle/SKILL.md" to example.toByteArray(), "other/data.txt" to byteArrayOf(1)))
        }
        assertEquals("sample-skill", importer.install("pkg.zip", archive("SKILL.md" to example.toByteArray())))
        assertTrue(workspace.skills().contains("sample-skill"))
    }
}
