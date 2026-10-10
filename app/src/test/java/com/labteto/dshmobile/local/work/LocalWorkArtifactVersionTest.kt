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
}
