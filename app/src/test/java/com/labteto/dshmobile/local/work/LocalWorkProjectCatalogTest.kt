package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkProjectCatalogTest {
    private fun testCatalog(test: (File, File, LocalWorkProjectCatalog) -> Unit) {
        val home = Files.createTempDirectory("777-work-projects-").toFile()
        val root = File(home, "workspace").apply { mkdirs() }
        val catalogFile = File(home, "projects.json")
        try {
            test(catalogFile, root, LocalWorkProjectCatalog(catalogFile, root, Json))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun completeCreateImportRenameOrderBindingAndRestart() = testCatalog { file, root, catalog ->
        File(root, "repo").mkdirs()
        val project = catalog.create("写作", ".", requestId = "11111111-1111-1111-1111-111111111111")
        val imported = catalog.importExisting("代码", "repo", requestId = "22222222-2222-2222-2222-222222222222",
            initialSessionId = "work-1")
        assertEquals(imported.id, catalog.snapshots.value.sessionProjects["work-1"])
        assertEquals(project, catalog.create("写作", ".", requestId = project.requestId))
        assertTrue(catalog.rename(project.id, "长篇写作"))
        assertTrue(catalog.update(project.id, "长篇写作", "repo"))
        assertTrue(catalog.assign(setOf("work-1"), project.id))
        assertTrue(catalog.move(imported.id, 0))
        val recovered = LocalWorkProjectCatalog(file, root, Json).snapshots.value
        assertEquals(listOf(imported.id, project.id), recovered.projects.map { it.id })
        assertEquals("repo", recovered.projects[1].root)
        assertEquals("长篇写作", recovered.projects[1].name)
        assertEquals(project.id, recovered.sessionProjects["work-1"])
        assertTrue(catalog.delete(project.id))
        val deleted = LocalWorkProjectCatalog(file, root, Json).snapshots.value
        assertEquals(listOf(imported.id), deleted.projects.map { it.id })
        assertTrue(deleted.sessionProjects.isEmpty())
        assertTrue(File(root, "repo").isDirectory)
    }

    @Test fun createsDedicatedLocalFoldersAndKeepsThemWhenRemovingProjects() =
        testCatalog { file, root, catalog ->
            val requestId = "44444444-4444-4444-4444-444444444444"
            val first = catalog.createWithNewFolder("界面/设计", requestId = requestId,
                initialSessionId = "session-1")
            assertTrue(first.root.startsWith("项目/界面-设计-44444444"))
            assertTrue(File(root, first.root).isDirectory)
            assertEquals(first.id, catalog.snapshots.value.sessionProjects["session-1"])
            assertEquals(first, catalog.createWithNewFolder("界面/设计", requestId = requestId))
            val second = catalog.createWithNewFolder("代码", requestId =
                "55555555-5555-5555-5555-555555555555")
            assertTrue(second.root != first.root)
            assertTrue(File(root, second.root).isDirectory)
            val recovered = LocalWorkProjectCatalog(file, root, Json)
            assertEquals(2, recovered.snapshots.value.projects.size)
            assertTrue(catalog.delete(first.id))
            assertTrue(File(root, first.root).isDirectory)
        }

    @Test fun failedNewProjectCatalogCommitCleansTheAllocatedDirectory() =
        testCatalog { file, root, catalog ->
            assertTrue(file.mkdir())
            File(file, "prevent-replace").writeText("existing")
            assertTrue(runCatching {
                catalog.createWithNewFolder("失败项目",
                    requestId = "66666666-6666-6666-6666-666666666666")
            }.isFailure)
            assertTrue(catalog.snapshots.value.projects.isEmpty())
            assertFalse(File(root, "项目/失败项目-66666666").exists())
            assertFalse(File(root, "项目").exists())
        }


    @Test fun actualDirectoryMovePreservesBytesGitAndSessionAcrossRestart() =
        testCatalog { file, root, catalog ->
            val original = File(root, "source").apply { mkdirs() }
            File(original, ".git/objects").mkdirs()
            File(original, ".git/objects/commit").writeText("git-history")
            File(original, "chapter.txt").writeText("第一章")
            File(root, "archive").mkdirs()
            val project = catalog.importExisting("小说", "source",
                initialSessionId = "work-7")
            assertTrue(catalog.moveDirectory(project.id, "source", "archive/novel"))
            assertFalse(original.exists())
            assertEquals("第一章", File(root, "archive/novel/chapter.txt").readText())
            assertEquals("git-history", File(root, "archive/novel/.git/objects/commit").readText())
            val loaded = LocalWorkProjectCatalog(file, root, Json).snapshots.value
            assertEquals("archive/novel", loaded.projects.single().root)
            assertEquals(project.id, loaded.sessionProjects["work-7"])
            assertFalse(File(file.parentFile, file.name + ".moving").exists())
        }

    @Test fun moveRefusesOldConfirmationOverlapsAndExistingDestinations() =
        testCatalog { _, root, catalog ->
            File(root, "repo/a").mkdirs()
            File(root, "repo/b").mkdirs()
            File(root, "output").mkdirs()
            val project = catalog.importExisting("主项目", "repo")
            val child = catalog.importExisting("子项目", "repo/a")
            val revision = catalog.snapshots.value.revision
            assertTrue(runCatching {
                catalog.moveDirectory(project.id, "outdated", "output/repo")
            }.isFailure)
            assertTrue(runCatching {
                catalog.moveDirectory(project.id, "repo", "output/repo")
            }.isFailure)
            assertTrue(runCatching {
                catalog.moveDirectory(child.id, "repo/a", "repo/b")
            }.isFailure)
            assertTrue(runCatching {
                catalog.moveDirectory(child.id, "repo/a", "repo/a/sub")
            }.isFailure)
            assertTrue(runCatching {
                catalog.moveDirectory(child.id, "repo/a", "output/new-repo")
            }.isSuccess)
            assertTrue(runCatching {
                catalog.moveDirectory(project.id, "repo", "output")
            }.isFailure)
            assertEquals(revision + 1, catalog.snapshots.value.revision)
            assertTrue(File(root, "repo/a").isDirectory)
            assertTrue(File(root, "repo/b").isDirectory)
        }

    @Test fun failedCatalogCommitRollsRealDirectoryBackWithoutLosingFiles() =
        testCatalog { file, root, catalog ->
            File(root, "source").mkdirs()
            File(root, "source/important.txt").writeText("retained")
            val project = catalog.importExisting("案例", "source")
            val previous = file.readBytes()
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            File(file, "prevent-replace").writeText("block")
            assertTrue(runCatching {
                catalog.moveDirectory(project.id, "source", "target")
            }.isFailure)
            assertEquals("retained", File(root, "source/important.txt").readText())
            assertFalse(File(root, "target").exists())
            assertEquals("source", catalog.snapshots.value.projects.single().root)
            assertFalse(File(file.parentFile, file.name + ".moving").exists())
            assertTrue(file.deleteRecursively())
            file.writeBytes(previous)
            assertEquals("source", LocalWorkProjectCatalog(file, root, Json)
                .snapshots.value.projects.single().root)
        }

    @Test fun restartRecoversInterruptedRenameAndRecognizesCompletedCommit() =
        testCatalog { file, root, catalog ->
            File(root, "source").mkdirs()
            File(root, "source/record.txt").writeText("durable")
            val project = catalog.importExisting("恢复", "source")
            val revision = catalog.snapshots.value.revision
            val journal = File(file.parentFile, file.name + ".moving")
            fun intent() = """{"version":1,"projectId":"${project.id}","from":"source","to":"target","revision":$revision}"""
            journal.writeText(intent())
            Files.move(File(root, "source").toPath(), File(root, "target").toPath(),
                StandardCopyOption.ATOMIC_MOVE)
            val recovered = LocalWorkProjectCatalog(file, root, Json)
            assertEquals("source", recovered.snapshots.value.projects.single().root)
            assertEquals("durable", File(root, "source/record.txt").readText())
            assertFalse(journal.exists())
            assertTrue(recovered.moveDirectory(project.id, "source", "target"))
            journal.writeText(intent())
            val completed = LocalWorkProjectCatalog(file, root, Json)
            assertEquals("target", completed.snapshots.value.projects.single().root)
            assertTrue(File(root, "target/record.txt").isFile)
            assertFalse(journal.exists())
        }

    @Test fun ambiguousRecoveryPreservesBothDirectoriesAndReportsError() =
        testCatalog { file, root, catalog ->
            File(root, "source").mkdirs()
            File(root, "target").mkdirs()
            val project = catalog.importExisting("冲突", "source")
            File(file.parentFile, file.name + ".moving").writeText(
                """{"version":1,"projectId":"${project.id}","from":"source","to":"target","revision":${catalog.snapshots.value.revision}}"""
            )
            assertTrue(runCatching { LocalWorkProjectCatalog(file, root, Json) }.isFailure)
            assertTrue(File(root, "source").isDirectory)
            assertTrue(File(root, "target").isDirectory)
            assertTrue(File(file.parentFile, file.name + ".moving").exists())
        }

    @Test fun rejectsTraversalOrDuplicateIdWithoutChangingProject() = testCatalog { file, root, catalog ->
        val child = File(root, "source").apply { mkdirs() }
        val first = catalog.create("first", "source", requestId = "33333333-3333-3333-3333-333333333333")
        val revision = catalog.snapshots.value.revision
        assertTrue(runCatching { catalog.create("second", "../outside") }.isFailure)
        assertTrue(runCatching { catalog.create("second", child.absolutePath) }.isFailure)
        assertTrue(runCatching { catalog.create("second", "missing") }.isFailure)
        assertTrue(runCatching { catalog.create("second", ".",
            requestId = first.requestId) }.isFailure)
        assertTrue(runCatching { catalog.rename(first.id, "\n") }.isFailure)
        assertTrue(runCatching { catalog.assign(setOf("../bad"), first.id) }.isFailure)
        assertTrue(runCatching { catalog.assign(setOf("a"), "missing") }.isFailure)
        assertEquals(revision, catalog.snapshots.value.revision)
        assertEquals(revision, LocalWorkProjectCatalog(file, root, Json).snapshots.value.revision)
    }

    @Test fun durableFailureNeverPublishesPartialState() = testCatalog { file, _, catalog ->
        val p = catalog.create("saved", ".")
        val old = catalog.snapshots.value
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "block").writeText("x")
        assertTrue(runCatching { catalog.rename(p.id, "not-saved") }.isFailure)
        assertEquals(old, catalog.snapshots.value)
    }

    @Test fun corruptedCatalogNeverTurnsIntoEmptyProjects() = testCatalog { file, root, _ ->
        file.writeText("""{"version":3,"projects":[],"sessionProjects":[]}""")
        assertTrue(runCatching { LocalWorkProjectCatalog(file, root, Json) }.isFailure)
        file.writeText("""{"version":1,"projects":[{"id":"wrong","name":"a","root":".","createdAtMs":0,"updatedAtMs":0,"requestId":"wrong"}],"sessionProjects":{}}""")
        assertTrue(runCatching { LocalWorkProjectCatalog(file, root, Json) }.isFailure)
    }

    @Test fun finishedSessionDeletionDetachesWithoutRemovingProjects() = testCatalog { file, root, store ->
        val project = store.create("项目", ".")
        store.assign(setOf("retained", "deleted"), project.id)
        assertTrue(store.forgetDeletedSessions(setOf("deleted")))
        val fresh = LocalWorkProjectCatalog(file, root, Json).snapshots.value
        assertEquals(project.id, fresh.sessionProjects["retained"])
        assertFalse(fresh.sessionProjects.containsKey("deleted"))
        assertFalse(store.forgetDeletedSessions(setOf("missing")))
    }
}
