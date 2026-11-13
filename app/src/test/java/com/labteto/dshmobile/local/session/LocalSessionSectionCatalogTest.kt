package com.labteto.dshmobile.local.session

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionSectionCatalogTest {
    private fun withCatalog(block: (File, LocalSessionSectionCatalog) -> Unit) {
        val root = Files.createTempDirectory("777-session-sections-").toFile()
        try {
            val file = File(root, "sections.json")
            block(file, LocalSessionSectionCatalog(file, Json))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun createAssignRenameReorderAndRestartRetainExactMembership() = withCatalog { file, store ->
        val first = store.create("开发")
        val second = store.create("写作")
        assertTrue(store.assign(setOf("session-a", "session-b"), first.id))
        assertTrue(store.assign(setOf("session-b"), second.id))
        assertTrue(store.rename(first.id, "代码"))
        assertTrue(store.move(second.id, 0))
        val after = LocalSessionSectionCatalog(file, Json).snapshots.value
        assertEquals(listOf(second.id, first.id), after.sections.map { it.id })
        assertEquals("代码", after.sections[1].title)
        assertEquals(first.id, after.assignments["session-a"])
        assertEquals(second.id, after.assignments["session-b"])
        assertEquals(6L, after.revision)
        assertFalse(store.assign(setOf("session-b"), second.id))
        assertEquals(6L, store.snapshots.value.revision)
    }

    @Test fun deletingASectionDetachesSessionsWithoutRemovingOtherSections() = withCatalog { file, store ->
        val one = store.create("第一组")
        val two = store.create("第二组")
        store.assign(setOf("s1", "s2"), one.id)
        store.assign(setOf("s3"), two.id)
        assertTrue(store.delete(one.id))
        val after = LocalSessionSectionCatalog(file, Json).snapshots.value
        assertEquals(listOf(two), after.sections)
        assertEquals(mapOf("s3" to two.id), after.assignments)
        assertFalse(store.delete(one.id))
        assertTrue(store.assign(setOf("s3"), null))
        assertTrue(store.snapshots.value.assignments.isEmpty())
    }

    @Test fun invalidTargetsAndIdsNeverRewriteTheDurableCatalog() = withCatalog { file, store ->
        val section = store.create("安全")
        val revision = store.snapshots.value.revision
        assertTrue(runCatching { store.assign(setOf("../escape"), section.id) }.isFailure)
        assertTrue(runCatching { store.assign(setOf("s1"), "missing-section") }.isFailure)
        assertTrue(runCatching { store.rename(section.id, "\n") }.isFailure)
        assertTrue(runCatching { store.move(section.id, 5) }.isFailure)
        assertEquals(revision, LocalSessionSectionCatalog(file, Json).snapshots.value.revision)
    }

    @Test fun corruptOrFutureVersionIsNotSilentlyReset() = withCatalog { file, _ ->
        file.writeText("""{"version":99,"sections":[],"assignments":[]}""")
        assertTrue(runCatching { LocalSessionSectionCatalog(file, Json) }.isFailure)
        file.writeText("""{"version":1,"sections":[{"id":"bad-id","title":"X"}],"assignments":{}}""")
        assertTrue(runCatching { LocalSessionSectionCatalog(file, Json) }.isFailure)
    }

    @Test fun failedDiskCommitDoesNotPublishAnUnwrittenRevision() = withCatalog { file, store ->
        val original = store.create("初始")
        val prior = store.snapshots.value
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "not-empty").writeText("blocking")
        assertTrue(runCatching { store.rename(original.id, "不应提交") }.isFailure)
        assertEquals(prior, store.snapshots.value)
    }

    @Test fun onlyCommittedSessionDeletionClearsMembership() = withCatalog { file, store ->
        val section = store.create("项目")
        store.assign(setOf("survivor", "deleted"), section.id)
        store.forgetDeletedSessions(setOf("deleted"))
        val after = LocalSessionSectionCatalog(file, Json).snapshots.value
        assertEquals(mapOf("survivor" to section.id), after.assignments)
        assertFalse(store.forgetDeletedSessions(setOf("unknown")))
    }
}
