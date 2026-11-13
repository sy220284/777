package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.file.Files
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
