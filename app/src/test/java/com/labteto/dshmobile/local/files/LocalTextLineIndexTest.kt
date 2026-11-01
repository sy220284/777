package com.labteto.dshmobile.local.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextLineIndexTest {
    @Test fun indexedLatePageMatchesSequentialUtf8ReadAndReusesDiskCheckpoint() {
        val root = Files.createTempDirectory("indexed-text").toFile()
        try {
            val target = root.resolve("large.txt")
            target.bufferedWriter(Charsets.UTF_8).use { out ->
                repeat(580_000) { n ->
                    out.write("第${n}行😀：")
                    out.write("abc中文".repeat(3))
                    out.write("\n")
                }
            }
            assertTrue(target.length() > 8L * 1024L * 1024L)
            val workspace = LocalWorkspace(root)
            val startLine = 543_217
            val expected = readLocalTextPage(target, startLine, startLine + 15, 0)
            val first = workspace.read("large.txt", startLine, startLine + 15)
            assertEquals(expected, first)
            val indexFiles = root.resolve(".dsh/text-index").listFiles().orEmpty()
            assertEquals(1, indexFiles.size)
            val index = indexFiles.single()
            val indexedBytes = index.readBytes()
            val second = workspace.read("large.txt", startLine + 1, startLine + 12)
            assertEquals(readLocalTextPage(target, startLine + 1, startLine + 12, 0), second)
            assertTrue(indexedBytes.contentEquals(index.readBytes()))
            val startChar = 5
            assertEquals(
                readLocalTextPage(target, startLine + 12, startLine + 15, startChar),
                workspace.read("large.txt", startLine + 12, startLine + 15, startChar),
            )
            target.appendText("文件新增尾行\n")
            assertEquals(readLocalTextPage(target, 580_001, 580_002, 0),
                workspace.read("large.txt", 580_001, 580_002, 0))
            assertTrue(!indexedBytes.contentEquals(index.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }
}
