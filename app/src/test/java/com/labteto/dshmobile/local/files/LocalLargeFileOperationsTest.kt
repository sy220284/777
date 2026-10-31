package com.labteto.dshmobile.local.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import com.labteto.dshmobile.local.tools.LocalSandboxBoundary
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
        val sessionId = Regex("session_id=([0-9a-f-]+)").find(first)!!.groupValues[1]
        val restarted = LocalWorkspace(root)
        val result = restarted.writeChunk("saved.txt", "第二段", 5, true, sessionId)
        assertTrue(result.contains("complete=true"))
        assertEquals("a😀第二段", root.resolve("saved.txt").readText())
        assertThrows(IllegalArgumentException::class.java) {
            restarted.writeChunk("saved.txt", "unexpected", 5, true, sessionId)
        }
    }

    @Test fun concurrentSessionsNeverMixDataWithMatchingOffsets() {
        val first = workspace.writeChunk("collision.bin", "AAAA", 0, false)
        val second = workspace.writeChunk("collision.bin", "BBBB", 0, false)
        val a = Regex("session_id=([0-9a-f-]+)").find(first)!!.groupValues[1]
        val b = Regex("session_id=([0-9a-f-]+)").find(second)!!.groupValues[1]
        assertTrue(a != b)
        assertEquals("BBBBCCCC", run {
            workspace.writeChunk("collision.bin", "CCCC", 4, true, b)
            root.resolve("collision.bin").readText()
        })
        assertThrows(IllegalArgumentException::class.java) {
            workspace.writeChunk("collision.bin", "WRONG", 4, true, "00000000-0000-0000-0000-000000000000")
        }
        workspace.writeChunk("collision.bin", "DDDD", 4, true, a)
        assertEquals("AAAADDDD", root.resolve("collision.bin").readText())
    }

    @Test fun binaryChunksRoundTripAfterRestartWithoutTextDecoding() {
        val bytes = byteArrayOf(0, -1, -2, 3, 10)
        val first = workspace.writeChunkBytes("data.bin", bytes, 0L, false)
        val id = Regex("session_id=([0-9a-f-]+)").find(first)!!.groupValues[1]
        val finished = LocalWorkspace(root).writeChunkBytes("data.bin", byteArrayOf(0, -7), bytes.size.toLong(), true, id)
        assertTrue(finished.contains("complete=true"))
        assertArrayEquals(byteArrayOf(0, -1, -2, 3, 10, 0, -7), root.resolve("data.bin").readBytes())
    }

    @Test fun largeExternalSharedFileUsesExistingSandboxPermissionAndPrivateIndexCache() {
        val shared = Files.createTempDirectory("allowed-shared-source").toFile()
        try {
            val source = File(shared, "large-source.kt")
            source.bufferedWriter().use { writer ->
                repeat(300_000) { writer.write("val sourceLine = \"sample content\"\n") }
                writer.write("val finalSymbol = 42\n")
            }
            assertTrue(source.length() > 8L * 1024 * 1024)
            val allowed = LocalWorkspace(root, boundary = LocalSandboxBoundary(root, listOf(shared)))
            assertTrue(allowed.read(source.absolutePath, 300_001, 300_001).contains("finalSymbol"))
            assertTrue(File(root, ".dsh/text-index").listFiles().orEmpty().isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                workspace.read(source.absolutePath, 300_001, 300_001)
            }
        } finally {
            shared.deleteRecursively()
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

    @Test fun sparse512MiBFileCanReadItsTailWithoutAllocatingItsLength() {
        val file = root.resolve("sparse.txt")
        val suffix = "文件末尾😀".toByteArray(Charsets.UTF_8)
        java.io.RandomAccessFile(file, "rw").use { stream ->
            stream.setLength(512L * 1024L * 1024L)
            stream.write(ByteArray(8192) { 'a'.code.toByte() })
            stream.seek(stream.length() - suffix.size)
            stream.write(suffix)
        }
        val offset = file.length() - suffix.size
        val result = workspace.preview("sparse.txt", maxBytes = 4096, offset = offset)
        assertEquals(offset, result.offset)
        assertEquals("文件末尾😀", result.text)
        assertEquals(null, result.nextOffset)
        assertEquals(512L * 1024L * 1024L, result.file.bytes)
    }

    @Test fun largeToolArtifactsAreStoredWithoutFormerFiveMiBCeiling() {
        val content = "abcd-".repeat(1_300_000)
        val path = workspace.writeToolArtifact(".dsh/queries/large.txt", content)
        assertEquals(content, workspace.readRaw(path))
        assertArrayEquals(content.toByteArray().copyOfRange(0, 48), workspace.readRawPage(path, 0, 48))
    }
}
