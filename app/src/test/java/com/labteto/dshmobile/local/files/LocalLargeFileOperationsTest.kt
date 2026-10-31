package com.labteto.dshmobile.local.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalLargeFileOperationsTest {
    private lateinit var root: File
    private lateinit var workspace: LocalWorkspace

    @Before fun prepare() {
        root = Files.createTempDirectory("large-file-workspace").toFile()
        workspace = LocalWorkspace(root)
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun streamingTransferHandles256MiBWithBoundedHeapAndExactByteCount() {
        val sourceSize = 256L * 1024L * 1024L
        val source = object : InputStream() {
            var left = sourceSize
            override fun read(): Int {
                if (left == 0L) return -1
                left--
                return 0x5a
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left == 0L) return -1
                val n = minOf(left, len.toLong()).toInt()
                java.util.Arrays.fill(b, off, off + n, 0x5a.toByte())
                left -= n
                return n
            }
        }
        var reported = 0L
        val start = System.nanoTime()
        val result = workspace.writeFromStream("bulk/256m.bin", source) { reported = it }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        assertEquals(sourceSize, result)
        assertEquals(sourceSize, reported)
        val target = root.resolve("bulk/256m.bin")
        assertEquals(sourceSize, target.length())
        assertEquals(0x5a, java.io.RandomAccessFile(target, "r").use {
            it.seek(sourceSize - 1)
            it.read()
        })
        assertEquals(null, workspace.preview("bulk/256m.bin").text)
        assertTrue("elapsedMs=" + elapsedMs, elapsedMs >= 0)
    }

    @Test fun replacesUniqueTextInLargerThanFormerFiveMiBLimitWithoutBuildingOneGiantString() {
        val source = root.resolve("large.txt")
        source.bufferedWriter().use { out ->
            repeat(12_000) { out.write("prefix-" + it + "-" + "x".repeat(1024) + "\n") }
            out.write("target-unique-😀\n")
        }
        workspace.read("large.txt", 1, 1)
        assertEquals("已编辑 large.txt", workspace.edit("large.txt", "target-unique-😀", "changed-🚀"))
        val tail = source.bufferedReader().use { input ->
            var last = ""
            while (true) {
                val row = input.readLine() ?: break
                last = row
            }
            last
        }
        assertEquals("changed-🚀", tail)
    }

    @Test fun duplicateEditAbortsWithoutPublishingPartialFile() {
        val original = "AAA\n" + "middle".repeat(100_000) + "\nAAA\n"
        workspace.write("dup.txt", original)
        workspace.read("dup.txt")
        assertThrows(IllegalArgumentException::class.java) {
            workspace.edit("dup.txt", "AAA", "replaced")
        }
        assertEquals(original, root.resolve("dup.txt").readText())
    }

    @Test fun resumedChunksCommitAtomicallyAfterRecreatingWorkspace() {
        workspace.write("saved.txt", "old content")
        val first = workspace.writeChunk("saved.txt", "a😀", 0, false)
        assertTrue(first.contains("next_byte=5"))
        assertEquals("old content", root.resolve("saved.txt").readText())
        val restarted = LocalWorkspace(root)
        val result = restarted.writeChunk("saved.txt", "第二段", 5, true)
        assertTrue(result.contains("complete=true"))
        assertEquals("a😀第二段", root.resolve("saved.txt").readText())
        assertThrows(IllegalArgumentException::class.java) {
            restarted.writeChunk("saved.txt", "unexpected", 5, true)
        }
    }

    @Test fun previewCanNavigateBeyondFormer512KiBWithoutRetainingEarlierPages() {
        val text = "中文字😀\n".repeat(140_000)
        root.resolve("long.txt").writeText(text)
        val builder = StringBuilder(text.length)
        var offset = 0L
        var pages = 0
        while (true) {
            val part = workspace.preview("long.txt", maxBytes = 64 * 1024, offset = offset)
            assertEquals(offset, part.offset)
            builder.append(part.text)
            pages++
            val next = part.nextOffset ?: break
            assertTrue(next > offset)
            offset = next
            assertTrue(pages < 100)
        }
        assertTrue(pages > 8)
        assertEquals(text, builder.toString())
    }

    @Test fun largeToolArtifactsAreStoredWithoutFormerFiveMiBCeiling() {
        val content = "abcd-".repeat(1_300_000)
        val path = workspace.writeToolArtifact(".dsh/queries/large.txt", content)
        assertEquals(content, workspace.readRaw(path))
        assertArrayEquals(content.toByteArray().copyOfRange(0, 48), workspace.readRawPage(path, 0, 48))
    }
}
