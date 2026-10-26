package com.labteto.dshmobile.local.tools

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArchiveReaderTest {
    @Test fun zipListsEveryPageAndReadsUnicodeTextByCursor() {
        val dir = Files.createTempDirectory("archive-zip-").toFile()
        try {
            val file = File(dir, "sources.zip")
            val code = "中文注释/hello\n".repeat(5000)
            ZipOutputStream(file.outputStream()).use { archive ->
                for (i in 0 until 233) {
                    archive.putNextEntry(ZipEntry("src/File" + i.toString().padStart(3, '0') + ".kt"))
                    archive.write(if (i == 17) code.toByteArray(StandardCharsets.UTF_8) else "val n = $i\n".toByteArray())
                    archive.closeEntry()
                }
                archive.putNextEntry(ZipEntry("../escape.kt"))
                archive.write("untrusted".toByteArray())
                archive.closeEntry()
            }
            val reader = LocalArchiveReader(dir)
            val page1 = reader.list(file.name, 0, 200)
            val page2 = reader.list(file.name, 200, 200)
            assertTrue(page1.contains("匹配文件总数：233"))
            assertTrue(page1.contains("next_cursor=200"))
            assertTrue(page2.contains("src/File232.kt"))
            assertTrue(page2.contains("next_cursor=none"))
            assertFalse(page1.contains("../escape.kt"))
            val read1 = reader.read(file.name, "src/File017.kt", 0L, 2000)
            val read2 = reader.read(file.name, "src/File017.kt", 2000L, 2000)
            assertTrue(read1.contains("complete=false"))
            assertTrue(read1.contains("next_char=2000"))
            assertTrue(read2.contains("start_char=2000"))
            assertTrue(read2.contains("中文注释"))
            assertThrows(IllegalArgumentException::class.java) {
                reader.read(file.name, "../escape.kt")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun contentAddressedGzipTarScansPastNineMegabyteEntry() {
        val dir = Files.createTempDirectory("archive-targz-").toFile()
        try {
            // Importer retains only the final .gz extension for <sha>.gz.
            val file = File(dir, "d34db33f.gz")
            GZIPOutputStream(file.outputStream()).use { zipped ->
                val chunk = ByteArray(16 * 1024) { 'a'.code.toByte() }
                writeTarHeader(zipped, "binary/large.bin", 9L * 1024L * 1024L)
                repeat(576) { zipped.write(chunk) }
                val code = "fun hello() = 42\n".toByteArray()
                writeTarHeader(zipped, "src/main.kt", code.size.toLong())
                zipped.write(code)
                zipped.write(ByteArray(512 - code.size))
                zipped.write(ByteArray(1024))
            }
            val reader = LocalArchiveReader(dir)
            val inventory = reader.list(file.name)
            assertTrue(inventory.contains("匹配文件总数：2"))
            assertTrue(inventory.contains("src/main.kt"))
            assertTrue(inventory.contains("next_cursor=none"))
            val result = reader.read(file.name, "src/main.kt")
            assertTrue(result.contains("fun hello() = 42"))
            assertTrue(result.contains("complete=true"))
            assertTrue(reader.read(file.name, "binary/large.bin").contains("二进制"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun archivePathsStayWithinWorkspace() {
        val dir = Files.createTempDirectory("archive-boundary-").toFile()
        try {
            val reader = LocalArchiveReader(dir)
            assertThrows(IllegalArgumentException::class.java) { reader.list("../outside.zip") }
            assertThrows(IllegalArgumentException::class.java) { reader.list("/absolute.zip") }
        } finally { dir.deleteRecursively() }
    }

    private fun writeTarHeader(target: java.io.OutputStream, name: String, length: Long) {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        (length.toString(8).padStart(11, '0') + "\u0000")
            .toByteArray().copyInto(header, destinationOffset = 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, destinationOffset = 257)
        target.write(header)
    }
}
