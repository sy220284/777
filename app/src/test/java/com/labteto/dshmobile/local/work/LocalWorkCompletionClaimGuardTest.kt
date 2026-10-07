package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalCanonicalMessage
import com.labteto.dshmobile.local.model.LocalCanonicalRole
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkCompletionClaimGuardTest {
    @Test
    fun completionClaimIsDiagnosedWhenRuntimeStateStillHasOpenWork() {
        val state = LocalHarnessState(
            usageMode = LocalUsageMode.WORK,
            work = LocalWorkState(
                goal = LocalGoal("完成重构", status = "blocked"),
                todos = listOf(
                    LocalTodoItem("补测试", "pending"),
                    LocalTodoItem("已处理", "completed"),
                ),
            ),
        )
        val result = LocalWorkCompletionClaimGuard.inspect(
            "全部完成，可以交付。",
            state.work,
        )
        assertTrue(result.changed)
        assertTrue(result.text.startsWith("当前尚未完成："))
        assertTrue(result.text.contains("仍有 1 项任务未完成"))
        assertTrue(result.text.contains("目标仍处于阻塞状态"))
        assertFalse(result.text.contains("全部完成"))
        assertTrue(result.findings.contains("完成声明与未完成任务清单冲突"))
        assertTrue(result.findings.contains("完成声明与阻塞目标状态冲突"))
    }

    @Test
    fun teamOpenWorkBlocksFalseGlobalCompletionClaim() {
        val state = LocalWorkState(
            team = LocalAgentTeamUiState(
                members = listOf(
                    LocalAgentTeamMemberUiState(
                        id = "member-1",
                        jobId = "job-team-1",
                        name = "reviewer",
                        description = "审查",
                        phase = "active",
                        activity = "running",
                    ),
                ),
                tasks = listOf(
                    LocalAgentTeamTaskUiState(
                        id = "task-1",
                        subject = "回归测试",
                        description = "",
                        status = "in_progress",
                        ownerName = "reviewer",
                    ),
                ),
            ),
        )

        val result = LocalWorkCompletionClaimGuard.inspect("全部完成，可以交付。", state)

        assertTrue(result.changed)
        assertTrue(result.findings.contains("完成声明与未完成 Agent Team 任务冲突"))
        assertTrue(result.findings.contains("完成声明与仍在运行的 Agent Team 成员冲突"))
        assertTrue(result.text.contains("Agent Team 仍有 1 项任务未完成"))
        assertTrue(result.text.contains("仍有 1 个智能体在执行"))
    }

    @Test
    fun pendingTeamMailboxBlocksFalseGlobalCompletionClaim() {
        val state = LocalWorkState(
            team = LocalAgentTeamUiState(
                members = listOf(
                    LocalAgentTeamMemberUiState(
                        id = "member-1",
                        jobId = "job-team-1",
                        name = "reviewer",
                        description = "审查",
                        phase = "active",
                        activity = "dormant",
                        pendingMessageCount = 2,
                    ),
                ),
            ),
        )

        val result = LocalWorkCompletionClaimGuard.inspect("全部完成，可以交付。", state)

        assertTrue(result.changed)
        assertTrue(result.findings.contains("完成声明与待投递 Agent Team 消息冲突"))
        assertTrue(result.text.contains("仍有 2 条 Team 消息待投递"))
    }

    @Test
    fun localProgressClaimIsNotMistakenForWholeTaskCompletion() {
        val state = LocalHarnessState(
            usageMode = LocalUsageMode.WORK,
            work = LocalWorkState(
                todos = listOf(LocalTodoItem("继续测试", "pending")),
            ),
        )
        val result = LocalWorkCompletionClaimGuard.inspect(
            "代码修改已完成，测试仍在运行。",
            state.work,
        )

        assertFalse(result.changed)
        assertTrue(result.findings.isEmpty())
        assertEquals("代码修改已完成，测试仍在运行。", result.text)
    }

    @Test
    fun deliveryGuardReplacesFalseCompletionAcrossVisibleAndCanonicalReply() {
        val root = createTempDir(prefix = "work-completion-guard-")
        try {
            val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
            val state = LocalHarnessState(
                usageMode = LocalUsageMode.WORK,
                work = LocalWorkState(
                    goal = LocalGoal("完成发布", status = "active"),
                    todos = listOf(LocalTodoItem("跑完整 CI", "in_progress")),
                ),
            )
            val reply = LocalModelReply(
                message = buildJsonObject {
                    put("role", "assistant")
                    put("content", "任务完成，可以交付。")
                },
                content = "任务完成，可以交付。",
                reasoning = null,
                toolCalls = emptyList(),
                canonicalMessage = LocalCanonicalMessage(
                    role = LocalCanonicalRole.ASSISTANT,
                    content = listOf(LocalCanonicalContent.Text("任务完成，可以交付。")),
                ),
            )

            val guarded = guardWorkCompletionDelivery(reply, state, log)

            assertTrue(guarded.content.orEmpty().startsWith("当前尚未完成："))
            assertTrue(guarded.content.orEmpty().contains("仍有 1 项任务未完成"))
            assertFalse(guarded.content.orEmpty().contains("可以交付"))
            assertEquals(guarded.content, guarded.message["content"]?.jsonPrimitive?.contentOrNull)
            val canonicalText = guarded.canonicalMessage?.content
                ?.filterIsInstance<LocalCanonicalContent.Text>()
                ?.singleOrNull()
                ?.text
            assertEquals(guarded.content, canonicalText)
            val event = log.snapshot().last()
            assertEquals("work/output-quality", event.type)
            assertEquals(true, event.data["delivery_blocked"]?.jsonPrimitive?.content?.toBoolean())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun negativeCompletionStatementDoesNotProduceFalseFinding() {
        val state = LocalHarnessState(
            usageMode = LocalUsageMode.WORK,
            work = LocalWorkState(
                todos = listOf(LocalTodoItem("继续处理", "pending")),
            ),
        )
        val result = LocalWorkCompletionClaimGuard.inspect(
            "尚未完成，还要继续处理。",
            state.work,
        )
        assertTrue(result.findings.isEmpty())
    }
    @Test
    fun formattedGlobalClaimDoesNotEraseLinksOrHideBehindUnrelatedNegation() {
        val text = "尚未完成测试。**全部都已完成**。成果：[文件](sandbox:/report.md)"
        val corrected = LocalWorkCompletionClaimGuard.correctGlobalCompletionClaims(text)
        assertFalse(corrected.contains("全部都已完成"))
        assertTrue(corrected.contains("尚未完成测试"))
        assertTrue(corrected.contains("[文件](sandbox:/report.md)"))
    }

    @Test
    fun codeAndArtifactNamesArePreserved() {
        val text = "```text\n全部完成\n```\n[全部完成](sandbox:/report.md)\n代码修改已完成，测试仍在运行。"
        assertEquals(text, LocalWorkCompletionClaimGuard.correctGlobalCompletionClaims(text))
    }
}
