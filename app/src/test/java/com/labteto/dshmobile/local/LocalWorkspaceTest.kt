package com.labteto.dshmobile.local

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalWorkspaceTest {
    private lateinit var root: java.io.File
    private var external: java.io.File? = null
    private lateinit var workspace: LocalWorkspace

    @Before
    fun setUp() {
        root = Files.createTempDirectory("local-harness-workspace").toFile()
        workspace = LocalWorkspace(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        external?.deleteRecursively()
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

    @Test
    fun editRequiresUniqueObservationAndGlobFindsFiles() {
        workspace.write("src/one.kt", "val before = 1\n")
        workspace.write("src/two.txt", "before before\n")

        assertEquals("已编辑 src/one.kt", workspace.edit("src/one.kt", "before", "after"))
        assertTrue(workspace.read("src/one.kt").contains("after"))
        assertTrue(workspace.glob("**/*.kt").contains("src/one.kt"))
        assertThrows(IllegalArgumentException::class.java) {
            workspace.edit("src/two.txt", "before", "after")
        }
    }

    @Test
    fun recursiveDiscoveryDoesNotFollowSymlinksOutsideWorkspace() {
        external = Files.createTempDirectory("local-harness-external").toFile().apply {
            resolve("secret.txt").writeText("outside-secret")
        }
        Files.createSymbolicLink(root.toPath().resolve("escape"), external!!.toPath())

        assertTrue(!workspace.list().contains("secret.txt"))
        assertTrue(!workspace.glob("**/*.txt").contains("secret.txt"))
        assertEquals("未找到匹配内容", workspace.search("outside-secret"))
    }
}
