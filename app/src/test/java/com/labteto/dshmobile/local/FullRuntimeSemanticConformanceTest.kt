package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentInputQueue
import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import com.labteto.dshmobile.harness.agent.AgentModelRoute
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.agent.AgentToolView
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-layer invariants for the Android-native Harness.
 *
 * These tests deliberately compose the persistence codecs, run-scoped policy and core Harness
 * primitives instead of testing one helper in isolation. The official fixture suite separately
 * covers the bare AgentLoop event grammar.
 */
class FullRuntimeSemanticConformanceTest {
    @Test
    fun queuedInputCrashRecoveryPreservesExecutionAuthority() {
        val queued = QueuedAgentInput(
            id = "q-1",
            content = "继续检查",
            memoryInput = "继续检查",
            modelMessage = message("user", "继续检查"),
        )
        val durable = encodeLocalAgentInboxEvent(
            action = "queued",
            pending = listOf(queued),
            affected = listOf(queued),
        )
        val recovered = decodeLocalAgentInboxPending(durable).orEmpty()

        assertEquals(listOf("q-1"), recovered.map(QueuedAgentInput::id))
        val queue = AgentInputQueue()
        queue.restore(recovered)
        assertEquals("继续检查", queue.poll()?.content)
    }

    @Test
    fun modelRouteEventIsSessionAuthorityAfterSnapshotLag() {
        val oldRoute = AgentModelRoute(
            provider = "openai-compatible",
            baseUrl = "https://example.com/v1",
            model = "old",
            protocol = AgentModelProtocol.OPENAI_CHAT,
        )
        val newRoute = AgentModelRoute(
            provider = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            protocol = AgentModelProtocol.ANTHROPIC_MESSAGES,
        )
        val event = LocalSessionEventLog.Event(
            sequence = 7,
            type = LOCAL_MODEL_ROUTE_EVENT_TYPE,
            createdAt = 1,
            data = encodeLocalModelRouteEvent(newRoute),
        )

        assertEquals(
            newRoute,
            recoverSessionModelRoute(
                snapshot = LocalHarnessSession(id = "s", modelRoute = oldRoute),
                latestRouteEvent = event,
                fallbackBaseUrl = "https://fallback.invalid/v1",
                fallbackModel = "fallback",
            ),
        )
    }

    @Test
    fun compactionTransactionCanRefineWithoutGrowingContext() {
        val history = buildList {
            add(message("system", "系统"))
            repeat(20) { index ->
                add(message(if (index % 2 == 0) "user" else "assistant", "历史-$index " + "x".repeat(1_500)))
            }
            add(message("user", "当前任务"))
        }
        val compactor = LocalHistoryCompactor()
        val candidate = compactor.compact(
            history = history,
            budget = LocalHistoryBudget(
                maxHistoryChars = 8_000,
                tailChars = 3_000,
                maxSummaryChars = 4_000,
                maxToolResultChars = 4_000,
            ),
            summaryMode = LocalHistorySummaryMode.WORK,
        ) ?: error("expected compaction")

        assertTrue(candidate.omittedHistory.isNotEmpty())
        val semantic = sanitizeSemanticCompactionSummary(
            """
            目标与需求：继续当前任务
            约束与边界：保留已有系统规则
            关键决定与阶段结论：已完成较早步骤
            失败尝试与风险：无
            未完成事项：完成当前任务
            其他阶段进展：历史已压缩
            """.trimIndent(),
        )
        assertNotNull(semantic)
        val refined = compactor.replaceSummary(candidate, requireNotNull(semantic))
        assertNotNull(refined)
        assertTrue(requireNotNull(refined).estimatedTokensAfter <= candidate.estimatedTokensAfter)
    }

    @Test
    fun providerRetryDelayOverridesLocalBackoff() = runTest {
        val waits = mutableListOf<Long>()
        val error = IOException("429")
        var attempts = 0
        val executor = AgentRequestExecutor(
            maxAttempts = 2,
            retryable = { it === error },
            providerRetryDelayMillis = { failure, _ -> if (failure === error) 11_000L else null },
            backoffMillis = { 100L },
            sleeper = { waits += it },
        )

        executor.execute { attempt ->
            attempts += 1
            if (attempt == 1) throw error
            "ok"
        }

        assertEquals(2, attempts)
        assertEquals(listOf(11_000L), waits)
    }

    @Test
    fun continuableSubagentCheckpointPreservesIdentityAndConversation() {
        val history = listOf(
            message("system", "子智能体"),
            message("user", "第一轮"),
            message("assistant", "第一轮完成"),
        )
        val encoded = encodeSubagentContinuationCheckpoint(
            agentId = "job-child",
            model = "deepseek-flash",
            maxSteps = 32,
            allowMutation = false,
            virtualScreen = false,
            history = history,
            compactor = LocalHistoryCompactor(),
        )
        val restored = decodeSubagentContinuationCheckpoint(encoded)
            ?: error("expected child checkpoint")

        assertEquals("job-child", restored.agentId)
        assertEquals(history, restored.history)
        assertFalse(restored.allowMutation)
    }

    @Test
    fun chatAndWorkShareRuntimeButKeepProductCapabilityBoundary() {
        val chat = localAgentRunPolicy(LocalUsageMode.CHAT)
        val work = localAgentRunPolicy(LocalUsageMode.WORK)

        assertFalse(chat.toolsEnabled)
        assertFalse(chat.allowToolExecution)
        assertTrue(work.toolsEnabled)
        assertTrue(work.allowToolExecution)
        assertFalse(LocalToolPolicy.canAutoApproveCommand("git status"))
    }

    @Test
    fun dynamicToolViewIsRunLocalAndCannotLeakToSibling() {
        val parent = AgentToolView(setOf("read", "capability_search"))
        val child = AgentToolView(setOf("read", "android_tap"))
        val sibling = AgentToolView(setOf("read"))

        assertTrue(parent.allows("capability_search"))
        assertFalse(parent.allows("android_tap"))
        assertTrue(child.allows("android_tap"))
        assertFalse(sibling.allows("android_tap"))
    }

    @Test
    fun interruptedToolRecoveryNeverGuessesSideEffects() {
        val started = SessionRecovery.interruptedToolResult(
            callId = "call-1",
            name = "write",
            step = 2,
            started = true,
        )
        val notStarted = SessionRecovery.interruptedToolResult(
            callId = "call-2",
            name = "read",
            step = 2,
            started = false,
        )

        assertEquals(SessionRecovery.TOOL_OUTCOME_UNKNOWN, started.code)
        assertEquals(SessionRecovery.TOOL_NOT_STARTED, notStarted.code)
        assertFalse(started.modelContent.isBlank())
        assertFalse(notStarted.modelContent.isBlank())
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
