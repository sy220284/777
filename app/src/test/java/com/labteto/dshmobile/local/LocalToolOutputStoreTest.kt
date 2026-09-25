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
    fun storesAndPagesSingleLineOutputByUtf8ByteRange() {
        val store = LocalToolOutputStore(File(temporary.root, "tool-output"))
        val content = "prefix-" + "😀汉字".repeat(8_000) + "-suffix"

        assertTrue(store.store("session-a", "call/奇怪:id", content) != null)
        val first = store.read(
            "session-a",
            "call/奇怪:id",
            startByte = 0,
            maxBytes = 4_096,
        )
        val nextByte = Regex("start_byte 设为 (\\d+)").find(first)
            ?.groupValues?.get(1)?.toInt()
            ?: error("missing continuation offset")
        val next = store.read(
            "session-a",
            "call/奇怪:id",
            startByte = nextByte,
            maxBytes = 4_096,
        )
        val tailStart = content.toByteArray(Charsets.UTF_8).size - 1_024
        val tail = store.read(
            "session-a",
            "call/奇怪:id",
            startByte = tailStart,
            maxBytes = 2_048,
        )

        assertTrue(first.contains("prefix-"))
        assertTrue(first.contains("后续仍有内容"))
        assertTrue(nextByte > 0)
        assertFalse(next.contains("prefix-"))
        assertTrue(tail.contains("-suffix"))
        assertFalse(first.contains("\uFFFD"))
        assertFalse(next.contains("\uFFFD"))
        assertFalse(tail.contains("\uFFFD"))
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
