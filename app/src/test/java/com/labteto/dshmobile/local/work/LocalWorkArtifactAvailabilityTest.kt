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
}
