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
    fun recoveryPagePlusHeaderFitsTheWorkInlineAllowance() {
        val store = LocalToolOutputStore(File(temporary.root, "tool-output"))
        val content = "prefix-" + "😀汉字".repeat(4_000) + "-suffix"

        assertTrue(store.store("session-page", "call-page", content) != null)
        val page = store.read(
            "session-page",
            "call-page",
            startByte = 0,
            maxBytes = LocalToolOutputStore.MAX_READ_BYTES,
        )
        val pageBytes = page.toByteArray(Charsets.UTF_8).size

        // 修复前：正文按 4 KiB 交付，连同 header 超出 Work 内联额度，下游再次截断时
        // next_byte 已前移到模型看不到的位置，被裁掉的字节会被静默跳过。
        assertTrue(
            "整段响应 $pageBytes 字节必须落在 Work 内联额度 $WORK_TOOL_INLINE_BYTES 字节内",
            pageBytes <= WORK_TOOL_INLINE_BYTES,
        )
    }

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
    fun defaultRecoveryPageStaysNearFourKiB() {
        val store = LocalToolOutputStore(File(temporary.root, "tool-output-default"))
        val content = "x".repeat(20_000)
        assertTrue(store.store("session-default", "call-default", content) != null)

        val first = store.read("session-default", "call-default")
        val nextByte = Regex("start_byte 设为 (\\d+)").find(first)
            ?.groupValues?.get(1)?.toInt()
            ?: error("missing continuation offset")

        assertTrue(nextByte in 1..LocalToolOutputStore.DEFAULT_READ_BYTES)
        assertTrue(first.contains("后续仍有内容"))
    }

    @Test
    fun deletingSessionRemovesItsPrivateOutputs() {
        val root = File(temporary.root, "tool-output")
        val store = LocalToolOutputStore(root)
        store.store("session-a", "call-1", "secret")

        store.deleteSession("session-a")

        assertTrue(store.read("session-a", "call-1").startsWith("未找到"))
    }

    @Test
    fun boundsSpillBytesPerSession() {
        val root = File(temporary.root, "tool-output")
        val store = LocalToolOutputStore(
            root = root,
            maxOutputBytes = 32,
            maxFilesPerSession = 10,
            maxSessionBytes = 12,
            maxGlobalBytes = 128,
        )

        store.store("session-a", "call-1", "12345678")
        store.store("session-a", "call-2", "abcdefgh")

        val retainedBytes = root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        assertTrue(retainedBytes <= 12L)
        assertTrue(store.read("session-a", "call-2").contains("abcdefgh"))
    }

    @Test
    fun boundsSpillBytesAcrossSessions() {
        val root = File(temporary.root, "tool-output")
        val store = LocalToolOutputStore(
            root = root,
            maxOutputBytes = 32,
            maxFilesPerSession = 10,
            maxSessionBytes = 64,
            maxGlobalBytes = 12,
        )

        store.store("session-a", "call-1", "12345678")
        store.store("session-b", "call-2", "abcdefgh")

        val retainedBytes = root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        assertTrue(retainedBytes <= 12L)
        assertTrue(store.read("session-b", "call-2").contains("abcdefgh"))
    }

    @Test
    fun returnsNullWhenSessionBudgetCannotRetainNewestItem() {
        val store = LocalToolOutputStore(
            root = File(temporary.root, "tool-output"),
            maxOutputBytes = 32,
            maxFilesPerSession = 10,
            maxSessionBytes = 4,
            maxGlobalBytes = 128,
        )

        assertTrue(store.store("session-a", "call-1", "12345678") == null)
        assertTrue(store.read("session-a", "call-1").startsWith("未找到"))
        assertTrue(store.store("session-b", "call-2", "abcd") != null)
    }

    @Test
    fun refusesSingleSpillAboveItemLimit() {
        val store = LocalToolOutputStore(
            root = File(temporary.root, "tool-output"),
            maxOutputBytes = 4,
            maxFilesPerSession = 10,
            maxSessionBytes = 64,
            maxGlobalBytes = 128,
        )

        assertTrue(store.store("session-a", "call-1", "12345") == null)
    }

    @Test
    fun rechecksGlobalBytesAfterTransientDeleteFailure() {
        val root = File(temporary.root, "tool-output")
        var failFirstDelete = true
        val store = LocalToolOutputStore(
            root = root,
            maxOutputBytes = 32,
            maxFilesPerSession = 10,
            maxSessionBytes = 64,
            maxGlobalBytes = 10,
            deleteFile = { file ->
                if (failFirstDelete) {
                    failFirstDelete = false
                    false
                } else {
                    file.delete()
                }
            },
        )

        store.store("session-a", "call-1", "123456")
        store.store("session-b", "call-2", "abcdef")
        store.store("session-c", "call-3", "x")

        val retainedBytes = root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
        assertTrue(retainedBytes <= 10L)
    }

}
