package com.labteto.dshmobile.local.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkArtifactAvailabilityTest {
    @Test fun checksPresentMovedAndEscapingFilesAtReadTime() {
        val root = Files.createTempDirectory("work-artifact-check").toFile()
        val outside = Files.createTempFile("external-artifact", ".txt").toFile()
        try {
            val created = File(root, "reports/final.txt")
            created.parentFile.mkdirs()
            created.writeText("complete")
            assertTrue(localWorkArtifactFileAvailable(root, "reports/final.txt"))
            assertFalse(localWorkArtifactFileAvailable(root, "reports/unknown.txt"))
            assertFalse(localWorkArtifactFileAvailable(root, "../${outside.name}"))
            assertFalse(localWorkArtifactFileAvailable(root, "."))
            created.delete()
            assertFalse(localWorkArtifactFileAvailable(root, "reports/final.txt"))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun symlinkCannotEscapeWorkspaceEvenWhenTargetExists() {
        val root = Files.createTempDirectory("artifact-symlink-root-").toFile()
        val outside = Files.createTempFile("artifact-private-", ".txt").toFile()
        try {
            outside.writeText("private")
            val link = File(root, "external.txt").toPath()
            Files.createSymbolicLink(link, outside.toPath())
            assertFalse(localWorkArtifactFileAvailable(root, "external.txt"))
            assertFalse(localWorkArtifactFileAvailable(root, "../${outside.name}"))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }
}
