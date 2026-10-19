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
        assertFalse(hasOutstandingWorkReplyForm(log, goal))
        log.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
            put("source_message_id", "user-1")
        })
        assertTrue(hasOutstandingWorkReplyForm(log, goal))
        assertTrue(hasOutstandingWorkReplyForm(log, goal.copy(revision = goal.revision + 1)))
        assertFalse(hasOutstandingWorkReplyForm(log, goal.copy(id = "another")))
        // Automatic continuation and a tool result do not constitute a user submission.
        log.append("tool/result", buildJsonObject { put("id", "tool-1") })
        log.append(com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent("queued", emptyList()))
        assertTrue(hasOutstandingWorkReplyForm(log, goal))
        log.close()
        val reopened = LocalSessionEventLog(file, Json, sessionId = "s")
        assertTrue(hasOutstandingWorkReplyForm(reopened, goal))
        reopened.append(com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent("queued", emptyList(),
                listOf(QueuedAgentInput(content = "真实表单答案"))))
        assertFalse(hasOutstandingWorkReplyForm(reopened, goal))
        reopened.append(WORK_REPLY_FORM_WAIT_EVENT, buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
        })
        assertTrue(hasOutstandingWorkReplyForm(reopened, goal))
        reopened.append("user/message", buildJsonObject { put("content", "用户继续") })
        assertFalse(hasOutstandingWorkReplyForm(reopened, goal))
        reopened.close()
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
