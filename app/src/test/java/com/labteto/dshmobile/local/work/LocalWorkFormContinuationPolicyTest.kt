package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import java.io.File

class LocalWorkFormContinuationPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun persistedFormBlocksGoalAfterRestartUntilRealUserMessageIsAccepted() {
        val file = File(temporary.root, "session.events.jsonl")
        val goal = LocalGoal("实现功能", id = "goal-42", revision = 2L)
        val log = LocalSessionEventLog(file, Json, sessionId = "s")
        assertFalse(hasOutstandingWorkReplyForm(log))
        log.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
            put("source_message_id", "user-1")
        })
        assertTrue(hasOutstandingWorkReplyForm(log))
        assertTrue(hasOutstandingWorkReplyForm(log, goal.copy(revision = goal.revision + 1)))
        assertTrue(hasOutstandingWorkReplyForm(log))
        // Automatic continuation and a tool result do not constitute a user submission.
        log.append("tool/result", buildJsonObject { put("id", "tool-1") })
        log.append(com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent("queued", emptyList()))
        assertTrue(hasOutstandingWorkReplyForm(log))
        log.close()
        val reopened = LocalSessionEventLog(file, Json, sessionId = "s")
        assertTrue(hasOutstandingWorkReplyForm(reopened))
        reopened.append(com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent("queued", emptyList(),
                listOf(QueuedAgentInput(content = "真实表单答案", id = "submitted-1"))))
        assertFalse(hasOutstandingWorkReplyForm(reopened))
        reopened.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
        })
        assertTrue(hasOutstandingWorkReplyForm(reopened))
        reopened.append("user/message", buildJsonObject { put("content", "用户继续") })
        assertFalse(hasOutstandingWorkReplyForm(reopened))
        reopened.close()
    }
    @Test fun queuedRetryAndCrashRecoveryContinuationCannotPassUnsubmittedForm() {
        val log = LocalSessionEventLog(File(temporary.root, "resume.events.jsonl"), Json, sessionId = "s")
        val goal = LocalGoal("待用户决策", id = "g", revision = 1L)
        val retry = com.labteto.dshmobile.harness.agent.AgentContinuationRef(
            "original", 1L, "WORK_BUDGET_EXHAUSTED", 1, "user-1",
        )
        val input = QueuedAgentInput("内部续跑", id = "internal", kind =
            com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.WORK_CONTINUATION,
            continuation = retry)
        val work = LocalWorkState(goal = goal)
        assertFalse(shouldDiscardWorkGoalContinuation(input, emptyList(), work, log))
        log.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
        })
        assertTrue(shouldDiscardWorkGoalContinuation(input, emptyList(), work, log))
        assertFalse(isCurrentWorkGoalContinuation(retry, work, log))
        log.append("user/message", buildJsonObject { put("content", "用户填写完成") })
        assertFalse(shouldDiscardWorkGoalContinuation(input, emptyList(), work, log))
        assertTrue(isCurrentWorkGoalContinuation(retry, work, log))
        log.close()
    }

    @Test fun pendingFormStopsGoalAutoContinuationUntilUserExplicitlySubmits() {
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NEEDS_EXECUTION, awaitingSubmission = true))
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.READY_FOR_SETTLEMENT, awaitingSubmission = true))
        assertTrue(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NEEDS_EXECUTION, awaitingSubmission = false))
        assertTrue(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.READY_FOR_SETTLEMENT, awaitingSubmission = false))
        assertFalse(shouldAutoContinueWorkGoalAfterForm(
            LocalWorkGoalDisposition.NO_GOAL, awaitingSubmission = false))
    }
}
