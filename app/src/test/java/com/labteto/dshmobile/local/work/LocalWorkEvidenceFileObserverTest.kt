package com.labteto.dshmobile.local.work

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkEvidenceFileObserverTest {
    @get:Rule val files = TemporaryFolder()
    private fun item(path: String) = LocalRequirementEvidenceLink(
        0, "报告", path, 1L, "call", "a".repeat(64), 2L,
    )

    @Test fun onlyActualWorkspaceFileParentsCanBeObserved() {
        val root = files.newFolder("project")
        val reports = File(root, "reports").apply { mkdirs() }
        File(reports, "one.md").writeText("version")
        File(reports, "two.md").writeText("second")
        val outside = files.newFolder("other")
        File(outside, "secret.md").writeText("not ours")
        val selection = workEvidenceWatchTargets(root, listOf(
            item("reports/one.md"), item("reports/two.md"),
            item("../other/secret.md"), item("/tmp/not-in-workspace"),
        ))
        assertEquals(setOf("one.md", "two.md"), selection.getValue(reports.canonicalFile))
        assertEquals(setOf("reports"), selection.getValue(root.canonicalFile))
        assertEquals(2, selection.size)
        assertFalse(selection.containsKey(outside.canonicalFile))
    }

    @Test fun reCreatedNestedDirectoriesCanBeObservedWithoutPolling() {
        val root = files.newFolder("nested-ancestor")
        val intermediate = File(root, "reports").apply { mkdirs() }
        val nested = File(intermediate, "build").apply { mkdirs() }
        val target = listOf(item("reports/build/result.txt"))
        assertEquals(
            mapOf(root.canonicalFile to setOf("reports"),
                intermediate.canonicalFile to setOf("build"),
                nested.canonicalFile to setOf("result.txt")),
            workEvidenceWatchTargets(root, target),
        )
        nested.delete()
        assertEquals(
            mapOf(root.canonicalFile to setOf("reports"),
                intermediate.canonicalFile to setOf("build")),
            workEvidenceWatchTargets(root, target),
        )
        nested.mkdir()
        assertTrue(workEvidenceWatchTargets(root, target).containsKey(nested.canonicalFile))
    }

    @Test fun missingOrCrossBoundaryArtifactIsNotRegisteredForExternalNotifications() {
        val root = files.newFolder("project2")
        val external = files.newFolder("private-target")
        val outside = File(external, "target.md").apply { writeText("private") }
        val link = File(root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), external.toPath())
        val targets = workEvidenceWatchTargets(root,
            listOf(item("link/target.md"), item("missing/target.md")))
        assertEquals(mapOf(root.canonicalFile to setOf("missing")), targets)
        assertTrue(outside.isFile)
    }
}
