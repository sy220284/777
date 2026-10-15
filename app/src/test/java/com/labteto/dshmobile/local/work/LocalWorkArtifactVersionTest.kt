package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkArtifactVersionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun eventTimeHashTracksExactFileVersionAndDetectsLaterEdit() {
        val root = tmp.root
        root.resolve("results").mkdirs()
        val file = root.resolve("results/report.md")
        file.writeText("version one")
        val version = localWorkArtifactFileVersion(root, "write_file",
            "已写入 results/report.md（11 字节）")!!
        assertEquals("results/report.md", version.path)
        assertEquals(64, version.sha256.length)
        assertEquals(version.sha256, localWorkFileSha256(root, version.path))
        file.writeText("version two")
        assertNotEquals(version.sha256, localWorkFileSha256(root, version.path))
    }

    @Test fun unsafeAndUnconfirmedToolResultsCannotManufactureFileVersion() {
        val root = tmp.root
        root.resolve("results.md").writeText("hello")
        assertNull(localWorkArtifactFileVersion(root, "read_file", "已写入 results.md（5 字节）"))
        assertNull(localWorkArtifactFileVersion(root, "write_file", "已写入 ../outside（5 字节）"))
        assertNull(localWorkArtifactFileVersion(root, "write_file", "写入失败：results.md"))
    }

    @Test fun overviewObservesIndependentFileChangesAndNeverInventsAContentHash() {
        val root = tmp.newFolder("observations")
        val file = java.io.File(root, "result.txt").apply { writeText("old") }
        val item = com.labteto.dshmobile.local.presentation.LocalArtifactUiItem("result.txt", "file", "call", 1L)
        val first = observeLocalWorkArtifact(root, item)
        org.junit.Assert.assertEquals(true, first.currentlyAvailable)
        org.junit.Assert.assertNotNull(first.fileObservation)
        org.junit.Assert.assertNull(first.versionNow)
        val checked = first.copy(versionNow = localWorkFileSha256(root, "result.txt"))
        org.junit.Assert.assertEquals(checked.versionNow, observeLocalWorkArtifact(root, checked).versionNow)
        file.writeText("changed without any session event")
        val changed = observeLocalWorkArtifact(root, checked)
        org.junit.Assert.assertNotEquals(first.fileObservation, changed.fileObservation)
        org.junit.Assert.assertNull(changed.versionNow)
        file.delete()
        val deleted = observeLocalWorkArtifact(root, checked)
        org.junit.Assert.assertEquals(false, deleted.currentlyAvailable)
        org.junit.Assert.assertNull(deleted.fileObservation)
        org.junit.Assert.assertNull(deleted.versionNow)
    }
}
