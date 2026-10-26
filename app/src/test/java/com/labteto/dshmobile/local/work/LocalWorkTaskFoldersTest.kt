package com.labteto.dshmobile.local.work

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkTaskFoldersTest {
    @Test fun createsIndependentFoldersAndPreservesExistingFiles() {
        val root = Files.createTempDirectory("task-folders").toFile()
        try {
            val old = root.resolve("old/file.md").apply { parentFile!!.mkdirs(); writeText("kept") }
            val paths = ensureLocalWorkTaskFolders(root, "修复文件加载 / UI", "12345678-aaaa-aaaa-aaaa-123456789abc")
            assertEquals("任务/修复文件加载-UI-12345678", paths.work)
            assertEquals("交付成果/修复文件加载-UI-12345678", paths.deliverables)
            assertTrue(root.resolve(paths.work).isDirectory)
            assertTrue(root.resolve(paths.deliverables).isDirectory)
            assertEquals("kept", old.readText())
            assertEquals(paths, ensureLocalWorkTaskFolders(root, "修复文件加载 / UI", "12345678-aaaa-aaaa-aaaa-123456789abc"))
        } finally { root.deleteRecursively() }
    }
}
