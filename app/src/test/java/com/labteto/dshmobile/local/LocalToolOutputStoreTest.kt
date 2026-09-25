package com.labteto.dshmobile.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalToolOutputStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun storesAndPagesOutputByOriginalCallId() {
        val store = LocalToolOutputStore(File(temporary.root, "tool-output"))
        val content = (1..900).joinToString("\n") { "line-$it" }

        assertTrue(store.store("session-a", "call/奇怪:id", content) != null)
        val first = store.read("session-a", "call/奇怪:id", startLine = 1, endLine = 400)
        val next = store.read("session-a", "call/奇怪:id", startLine = 401, endLine = 800)

        assertTrue(first.contains("line-1"))
        assertTrue(first.contains("后续仍有内容"))
        assertTrue(next.contains("line-401"))
        assertFalse(next.contains("line-1\n"))
    }

    @Test
    fun deletingSessionRemovesItsPrivateOutputs() {
        val root = File(temporary.root, "tool-output")
        val store = LocalToolOutputStore(root)
        store.store("session-a", "call-1", "secret")

        store.deleteSession("session-a")

        assertTrue(store.read("session-a", "call-1").startsWith("未找到"))
    }
}
