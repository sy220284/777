package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatAutomationExecutionBoundaryTest {
    @Test
    fun requireAutomationChatSessionAcceptsDirectChatOnly() {
        val direct = LocalHarnessSession(id = "chat", usageMode = LocalUsageMode.CHAT)

        assertSame(direct, requireAutomationChatSession(direct))
        assertFailureMessage("定时互动绑定的聊天已不存在") { requireAutomationChatSession(null) }
        assertFailureMessage("定时互动只能绑定聊天模式会话") {
            requireAutomationChatSession(LocalHarnessSession(id = "work", usageMode = LocalUsageMode.WORK))
        }
        assertFailureMessage("群聊暂不支持定时角色互动") {
            requireAutomationChatSession(
                direct.withChatSessionDomain(groupChat = LocalGroupChatState(mode = LocalChatMode.GROUP)),
            )
        }
    }

    @Test
    fun staleProactiveReplyIsSkippedAndDurablyExplained() {
        val directory = Files.createTempDirectory("automation-proactive-stale").toFile()
        try {
            val log = LocalSessionEventLog(directory.resolve("events.jsonl"), Json)
            val initial = log.append("user/message", buildJsonObject { put("text", "one") }).sequence

            assertNull(
                rejectStaleAutomationProactiveReply(
                    eventLog = log,
                    expectedUserActivitySequence = initial,
                    sessionId = "session",
                    personaId = "persona",
                ),
            )

            log.append("user/message", buildJsonObject { put("text", "two") })
            val result = requireNotNull(
                rejectStaleAutomationProactiveReply(
                    eventLog = log,
                    expectedUserActivitySequence = initial,
                    sessionId = "session",
                    personaId = "persona",
                ),
            )

            assertEquals(LocalChatAutomationStatus.SKIPPED, result.status)
            assertEquals("session", result.sessionId)
            assertTrue(result.output.contains("新的互动"))
            val event = requireNotNull(log.latest("chat/proactive-skipped"))
            assertEquals("\"persona\"", event.data["persona_id"].toString())
            assertEquals("true", event.data["user_activity_during_generation"].toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun timeoutBudgetAlwaysReturnsPositiveBoundedRemainingTime() {
        val budget = LocalChatAutomationTimeoutBudget(timeoutMillis = 1L, maxMillis = 10_000L)

        val remaining = budget.remainingMillis()

        assertTrue(remaining in 1L..5_000L)
    }

    private fun assertFailureMessage(expected: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(expected, error?.message)
    }
}
