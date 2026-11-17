package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalSessionRepository
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionCoordinator
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalWorkAutomationSessionTest {
    @Test fun newIdentityIsReadableAfterColdReopenBeforeExecutionStarts() = runTest {
        val root = kotlin.io.path.createTempDirectory("automation-identity-").toFile()
        try {
            val repository = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            val session = resolveLocalWorkAutomationSession(null, "执行任务", coordinator(repository), hasEvents = { false })
            val reopened = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            assertEquals(session.id, reopened.read(session.id)?.id)
        } finally { root.deleteRecursively() }
    }
    @Test fun missingSnapshotWithExistingEventsCannotSilentlyAcquireANewIdentity() = runTest {
        val root = kotlin.io.path.createTempDirectory("automation-orphan-").toFile()
        try {
            val repository = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            val result = runCatching { resolveLocalWorkAutomationSession("old-run", "执行任务", coordinator(repository),
                hasEvents = { true }, recoverInterrupted = true) }
            assertTrue("已有执行事实不能换会话重发", result.isFailure)
            assertTrue(repository.summaries().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun existingChatSessionCannotBeReplacedByAWorkSession() = runTest {
        val root = kotlin.io.path.createTempDirectory("automation-chat-").toFile()
        try {
            val repository = LocalSessionRepository(root, Json, backgroundScope, {}, {})
            repository.writeNow(LocalHarnessSession(id = "chat", usageMode = LocalUsageMode.CHAT))
            val result = runCatching { resolveLocalWorkAutomationSession("chat", "执行任务", coordinator(repository),
                hasEvents = { false }) }
            assertTrue(result.isFailure)
            assertEquals(listOf("chat"), repository.summaries().map { it.id })
        } finally { root.deleteRecursively() }
    }
    private fun coordinator(repository: LocalSessionRepository) = LocalSessionCoordinator(
        repository, eventLogFor = { error("本测试不读取消息投影") },
        runtimeWindowMessages = 32, domainCodecs = emptyList(),
    )
}
