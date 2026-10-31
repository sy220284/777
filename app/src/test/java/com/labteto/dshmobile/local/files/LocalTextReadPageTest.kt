package com.labteto.dshmobile.local.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTextReadPageTest {
    @Test fun jumpsAcrossManyLinesWithoutMaterializingEntireFile() {
        val root = kotlin.io.path.createTempDirectory("large-text-page").toFile()
        try {
            val file = File(root, "large.log")
            file.bufferedWriter().use { out ->
                repeat(300_000) { index ->
                    out.write("line=")
                    out.write(index.toString())
                    out.write("; ")
                    out.write("x".repeat(24))
                    out.newLine()
                }
            }
            assertTrue(file.length() > 10L * 1024 * 1024)
            val page = readLocalTextPage(file, 299_998, 300_000, 0)
            assertTrue(page.contains("299998: line=299997; "))
            assertTrue(page.contains("300000: line=299999; "))
            assertFalse(page.contains("line=100; "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun longLineCursorPreservesExactUtf16OffsetAndContinuation() {
        val root = kotlin.io.path.createTempDirectory("long-line-page").toFile()
        try {
            val file = File(root, "long.txt")
            val line = "x".repeat(2 * 1024 * 1024) + "😀结束"
            file.writeText(line + "\nnext")
            val page = readLocalTextPage(file, 1, 1, 2 * 1024 * 1024)
            assertTrue(page.startsWith("1: 😀结束"))
            assertFalse(page.contains("next"))
            val next = readLocalTextPage(file, 2, 2, 0)
            assertTrue(next.startsWith("2: next"))
        } finally {
            root.deleteRecursively()
        }
    }
}
