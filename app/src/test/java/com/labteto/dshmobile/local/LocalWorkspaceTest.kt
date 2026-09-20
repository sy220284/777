package com.labteto.dshmobile.local

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalWorkspaceTest {
    private lateinit var root: java.io.File
    private lateinit var workspace: LocalWorkspace

    @Before
    fun setUp() {
        root = Files.createTempDirectory("local-harness-workspace").toFile()
        workspace = LocalWorkspace(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun writeReadListAndSearchStayInsideWorkspace() {
        workspace.write("notes/one.txt", "alpha\nbeta\ngamma")

        assertTrue(workspace.read("notes/one.txt", 2, 3).contains("2: beta"))
        assertTrue(workspace.list("notes").contains("notes/one.txt"))
        assertTrue(workspace.search("GAMMA").contains("notes/one.txt:3: gamma"))
    }

    @Test
    fun traversalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            workspace.write("../escape.txt", "no")
        }
    }
}
