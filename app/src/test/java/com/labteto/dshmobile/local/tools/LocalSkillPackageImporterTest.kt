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

    @Test fun linkedLocalResourcesAreVerifiedBeforeAnySkillIsPublished() =
        runInWorkspace { importer, workspace, root ->
            val linked = example + "\n[核验脚本](scripts/verify.py)\n[资料](references/spec.md)\n"
            val incomplete = archive(
                "SKILL.md" to linked.toByteArray(),
                "scripts/verify.py" to "print('ok')".toByteArray(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                importer.install("incomplete.zip", incomplete)
            }
            assertTrue(workspace.skills().isEmpty())
            val complete = archive(
                "SKILL.md" to linked.toByteArray(),
                "scripts/verify.py" to "print('ok')".toByteArray(),
                "references/spec.md" to "spec".toByteArray(),
            )
            assertEquals("sample-skill", importer.install("complete.zip", complete))
            assertTrue(root.resolve(".dsh/skills/sample-skill/scripts/verify.py").isFile)
        }

    @Test fun largeReferencedSkillCanBeInstalledWithoutWholeDocumentLinkAllocation() =
        runInWorkspace { importer, workspace, root ->
            val body = example + "\n" + "审计说明".repeat(60_000) +
                "\n[专项规则](references/verified.md)\n"
            val zipped = archive(
                "SKILL.md" to body.toByteArray(),
                "references/verified.md" to "单独查阅的资料".toByteArray(),
            )
            assertEquals("sample-skill", importer.install("large-referenced.zip", zipped))
            assertTrue(workspace.readSkill("sample-skill").contains("审计说明"))
            assertTrue(root.resolve(".dsh/skills/sample-skill/references/verified.md").isFile)
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

    @Test fun importsLargeRulesAndResourcesWithoutFixedCapacityCeilings() =
        runInWorkspace { importer, workspace, root ->
            val extended = example + "\n" + "技能规则".repeat(26_000)
            val resource = ByteArray(9 * 1024 * 1024).also { java.util.Random(12).nextBytes(it) }
            val zipped = archive(
                "SKILL.md" to extended.toByteArray(),
                "references/large.bin" to resource,
            )
            assertTrue(zipped.size > 8 * 1024 * 1024)
            assertEquals("sample-skill", importer.install("large.zip", zipped.inputStream()))
            assertEquals(resource.size.toLong(),
                root.resolve(".dsh/skills/sample-skill/references/large.bin").length())
            assertTrue(workspace.readModelSkill("sample-skill").contains("技能规则"))
        }

    @Test fun supportsSingleRootZipAndRejectsSiblings() = runInWorkspace { importer, workspace, _ ->
        assertThrows(IllegalArgumentException::class.java) {
            importer.install("pkg.zip", archive("bundle/SKILL.md" to example.toByteArray(), "other/data.txt" to byteArrayOf(1)))
        }
        assertEquals("sample-skill", importer.install("pkg.zip", archive("SKILL.md" to example.toByteArray())))
        assertTrue(workspace.skills().contains("sample-skill"))
    }
    @Test fun acceptsSkillWithMoreThanOneHundredAndTwentyEightDirectories() =
        runInWorkspace { importer, workspace, _ ->
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("pkg/SKILL.md"))
                zip.write(example.toByteArray())
                zip.closeEntry()
                repeat(150) { i ->
                    zip.putNextEntry(ZipEntry("pkg/references/directory-$i/note.md"))
                    zip.write("引用".toByteArray())
                    zip.closeEntry()
                }
            }
            assertEquals("sample-skill", importer.install("many-directories.zip", output.toByteArray().inputStream()))
            assertTrue(workspace.skills().contains("sample-skill"))
        }

}
