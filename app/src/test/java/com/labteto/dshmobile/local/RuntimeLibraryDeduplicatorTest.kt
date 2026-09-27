package com.labteto.dshmobile.local

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLibraryDeduplicatorTest {
    @Test
    fun identicalLibrariesShareStorageWhileDifferentVersionsRemainIndependent() {
        val root = Files.createTempDirectory("runtime-dedup").toFile()
        try {
            fun library(runtime: String, content: ByteArray): File {
                val version = File(root, "$runtime/v1/arm64-v8a")
                version.mkdirs()
                File(version, ".ready").writeText("v1")
                return File(version, "lib/libicudata.so").apply {
                    parentFile.mkdirs()
                    writeBytes(content)
                }
            }
            val original = ByteArray(100_000) { (it % 211).toByte() }
            val node = library("node", original)
            val git = library("git", original)
            val different = library("python", ByteArray(100_000) { (it % 197).toByte() })

            RuntimeLibraryDeduplicator.deduplicate(root, "arm64-v8a")

            assertTrue(Files.isSameFile(node.toPath(), git.toPath()))
            assertFalse(Files.isSameFile(node.toPath(), different.toPath()))
            assertArrayEquals(original, git.readBytes())
            assertTrue(node.delete())
            assertArrayEquals(original, git.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
