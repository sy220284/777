package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.WORK_TOOL_INLINE_BYTES
import com.labteto.dshmobile.local.model.WORK_TOOL_RECOVERY_PAGE_BYTES
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalToolOutputStoreTest {
    @Test
    fun unsuccessfulDirectoryDeletionMustRemainAnError() {
        val root = temporary.newFolder("delete-error")
        val store = LocalToolOutputStore(root)
        assertTrue(store.store("retained", "receipt", "important evidence") != null)
        val directory = root.listFiles().orEmpty().single { it.isDirectory }
        val path = directory.toPath()
        val permissions = java.nio.file.Files.getPosixFilePermissions(path)
        try {
            java.nio.file.Files.setPosixFilePermissions(path, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
            ))
            assertFalse(java.nio.file.Files.isWritable(path))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                store.deleteSession("retained")
            }
            assertTrue(directory.exists())
        } finally {
            java.nio.file.Files.setPosixFilePermissions(path, permissions)
        }
        assertTrue(store.read("retained", "receipt").contains("important evidence"))
        store.deleteSession("retained")
        assertFalse(directory.exists())
    }

    @Test
    fun successfulSessionCleanupRemovesReceiptsBeforeReportingSuccess() {
        val root = temporary.newFolder("tool-cleanup")
        val store = LocalToolOutputStore(root)
        assertTrue(store.store("session", "receipt", "important evidence") != null)
        val sessionDirectory = root.listFiles().orEmpty().single { it.isDirectory }
        assertTrue(sessionDirectory.exists())
        store.deleteSession("session")
        assertFalse(sessionDirectory.exists())
    }

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
    fun oversizedModelPageRequestStillReturnsAnAccurateNextByte() {
        val store = LocalToolOutputStore(File(temporary.root, "oversized-pages"))
        val content = "甲乙丙🌟".repeat(3_000)
        assertTrue(store.store("session-big", "call-big", content) != null)

        val oversized = store.read("session-big", "call-big", maxBytes = Int.MAX_VALUE)
        val visible = store.read("session-big", "call-big", maxBytes = WORK_TOOL_RECOVERY_PAGE_BYTES)
        assertEquals(visible, oversized)
        val nextByte = Regex("start_byte 设为 (\\d+)").find(oversized)
            ?.groupValues?.get(1)?.toInt() ?: error("missing continuation offset")
        assertTrue(nextByte in 1..WORK_TOOL_RECOVERY_PAGE_BYTES)
        assertFalse(oversized.contains("\uFFFD"))
        assertTrue(store.read("session-big", "call-big", startByte = nextByte).contains("后续仍有内容"))
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
    fun searchesLongUtf8OutputAcrossChunkBoundariesAndRetainsSessionIsolation() {
        val store = LocalToolOutputStore(File(temporary.root, "tool-output"))
        val text = "x".repeat(8190) + "🌟匹配正文" + "y".repeat(9000)
        assertTrue(store.store("session-a", "call-search", text) != null)
        val found = store.search("session-a", "call-search", "🌟匹配正文")
        assertTrue(found.contains("8190"))
        assertTrue(store.read("session-a", "call-search", startByte = 8190).contains("🌟匹配正文"))
        assertTrue(store.search("session-b", "call-search", "🌟匹配正文").startsWith("未找到"))
        assertTrue(store.search("session-a", "call-search", "没有的内容").contains("没有匹配"))
    }

    @Test
    fun longAuditRetainsMoreThanTheOldSixtyFourResultFiles() {
        val store = LocalToolOutputStore(File(temporary.root, "long-audit-results"))
        repeat(72) { index ->
            assertTrue(store.store("audit-session", "call-$index", "evidence-$index") != null)
        }
        assertTrue(store.read("audit-session", "call-0").contains("evidence-0"))
        assertTrue(store.read("audit-session", "call-71").contains("evidence-71"))
    }

    @Test
    fun evictionNeverReportsStaleResultAsRecoverable() {
        val store = LocalToolOutputStore(
            root = File(temporary.root, "tool-output"),
            maxOutputBytes = 512,
            maxFilesPerSession = 1,
            maxSessionBytes = 512,
            maxGlobalBytes = 1024,
        )
        store.store("session-a", "first", "first")
        store.store("session-a", "second", "second")
        assertTrue(store.read("session-a", "first").contains("不可") ||
            store.read("session-a", "first").contains("可能"))
        assertTrue(store.search("session-a", "first", "first").contains("未找到"))
    }

    @Test
    fun activeCheckpointReferenceSurvivesOrdinaryEvictionButNotStorageBudget() {
        val store = LocalToolOutputStore(
            root = File(temporary.root, "tool-output"),
            maxOutputBytes = 128,
            maxFilesPerSession = 2,
            maxSessionBytes = 256,
            maxGlobalBytes = 512,
        )
        store.store("work", "important", "evidence")
        store.retainActiveReferences("work", listOf("important"))
        store.store("work", "ordinary", "temporary")
        store.store("work", "latest", "fresh")
        assertTrue(store.read("work", "important").contains("evidence"))
        assertTrue(store.read("work", "ordinary").startsWith("未找到"))
        assertTrue(store.read("work", "latest").contains("fresh"))
        // Reference priorities survive process recreation because the manifest is private and durable.
        val restored = LocalToolOutputStore(
            root = File(temporary.root, "tool-output"),
            maxOutputBytes = 128,
            maxFilesPerSession = 2,
            maxSessionBytes = 256,
            maxGlobalBytes = 512,
        )
        restored.store("work", "next", "more")
        assertTrue(restored.read("work", "important").contains("evidence"))
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
    fun defaultQuotaRetainsLongLogsBeyondOldSixteenMiBLimit() {
        val store = LocalToolOutputStore(File(temporary.root, "large-output"))
        val content = "prefix-" + "x".repeat(16 * 1024 * 1024) + "-suffix"
        val stored = store.store("large", "long-log", content)
        assertTrue(stored != null)
        assertTrue(stored?.bytes == content.length)
        assertTrue(store.read("large", "long-log", startByte = stored!!.bytes - 2048)
            .contains("-suffix"))
    }

    @Test
    fun rejectedOversizedReplacementKeepsEarlierCompleteEvidence() {
        val store = LocalToolOutputStore(
            root = File(temporary.root, "atomic-output"),
            maxOutputBytes = 8,
            maxFilesPerSession = 5,
            maxSessionBytes = 128,
            maxGlobalBytes = 256,
        )
        assertTrue(store.store("session", "call", "old-ok") != null)
        assertTrue(store.store("session", "call", "exceeds-the-budget") == null)
        assertTrue(store.read("session", "call").contains("old-ok"))
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
