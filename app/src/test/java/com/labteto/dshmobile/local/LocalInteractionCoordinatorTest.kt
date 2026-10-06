package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.interaction.LOCAL_QUESTION_CANCELLED_RESPONSE
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalInteractionCoordinator
import com.labteto.dshmobile.local.interaction.LocalInteractionStatePort
import com.labteto.dshmobile.local.interaction.LocalQuestion
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInteractionCoordinatorTest {
    private class TestState : LocalInteractionStatePort {
        var approval: LocalApproval? = null
        var question: LocalQuestion? = null
        var deviceLease: Boolean = false

        override fun pendingApproval(): LocalApproval? = approval
        override fun setPendingApproval(approval: LocalApproval?) { this.approval = approval }
        override fun clearPendingApproval(callId: String) { if (approval?.callId == callId) approval = null }
        override fun setPendingQuestion(question: LocalQuestion?) { this.question = question }
        override fun clearPendingQuestion(callId: String) { if (question?.callId == callId) question = null }
        override fun clearPendingInteractions() { approval = null; question = null }
        override fun deviceApprovalLeaseEnabled(): Boolean = deviceLease
        override fun setDeviceApprovalLease(enabled: Boolean) { deviceLease = enabled }
    }

    @Test
    fun staleApprovalIdCannotResolveCurrentInteraction() = runTest {
        val state = TestState()
        val coordinator = LocalInteractionCoordinator(state)
        val approval = LocalApproval(
            callId = "approval-1",
            toolName = "write",
            summary = "write",
            arguments = "{}",
            access = "workspace_write",
        )

        val result = async { coordinator.awaitApproval(approval) }
        runCurrent()

        assertEquals("approval-1", state.approval?.callId)
        assertFalse(coordinator.answerApproval("stale-id", true))
        assertTrue(coordinator.answerApproval("approval-1", true))
        assertTrue(result.await())
        assertNull(state.approval)
    }

    @Test
    fun concurrentInteractionsAreSerializedInsteadOfOverwritingState() = runTest {
        val state = TestState()
        val coordinator = LocalInteractionCoordinator(state)
        val first = LocalApproval(
            callId = "first",
            toolName = "write",
            summary = "first",
            arguments = "{}",
            access = "workspace_write",
        )
        val second = LocalQuestion("second", "继续吗？")

        val approval = async { coordinator.awaitApproval(first) }
        runCurrent()
        val question = async { coordinator.awaitQuestion(second) }
        runCurrent()

        assertEquals("first", state.approval?.callId)
        assertNull(state.question)

        assertTrue(coordinator.answerApproval("first", true))
        assertTrue(approval.await())
        runCurrent()

        assertEquals("second", state.question?.callId)
        assertTrue(coordinator.answerQuestion("second", "继续"))
        assertEquals("继续", question.await())
        assertNull(state.question)
    }

    @Test
    fun cancelAllInvalidatesInteractionsAlreadyQueuedBehindCurrentOne() = runTest {
        val state = TestState()
        val coordinator = LocalInteractionCoordinator(state)
        val approval = async {
            coordinator.awaitApproval(
                LocalApproval(
                    callId = "approval",
                    toolName = "write",
                    summary = "write",
                    arguments = "{}",
                    access = "workspace_write",
                ),
            )
        }
        runCurrent()
        val queuedQuestion = async {
            runCatching {
                coordinator.awaitQuestion(LocalQuestion("queued", "继续吗？"))
            }
        }
        runCurrent()

        coordinator.cancelAll()
        assertFalse(approval.await())
        runCurrent()

        assertTrue(queuedQuestion.await().isFailure)
        assertNull(state.approval)
        assertNull(state.question)
    }

    @Test
    fun cancellingQuestionUsesStableModelVisibleSemantic() = runTest {
        val state = TestState()
        val coordinator = LocalInteractionCoordinator(state)
        val question = async {
            coordinator.awaitQuestion(LocalQuestion("question-1", "请选择"))
        }
        runCurrent()

        assertTrue(coordinator.cancelQuestion("question-1"))
        assertEquals(LOCAL_QUESTION_CANCELLED_RESPONSE, question.await())
        assertNull(state.question)
    }

    @Test
    fun staleApprovalFromCancelledGenerationCannotResolveReplacementApproval() = runTest {
        val state = TestState()
        val coordinator = LocalInteractionCoordinator(state)
        val old = async {
            coordinator.awaitApproval(
                LocalApproval(
                    callId = "old",
                    toolName = "write",
                    summary = "old",
                    arguments = "{}",
                    access = "workspace_write",
                ),
            )
        }
        runCurrent()
        coordinator.cancelAll()
        assertFalse(old.await())

        val replacement = async {
            coordinator.awaitApproval(
                LocalApproval(
                    callId = "replacement",
                    toolName = "write",
                    summary = "replacement",
                    arguments = "{}",
                    access = "workspace_write",
                ),
            )
        }
        runCurrent()

        assertEquals("replacement", state.approval?.callId)
        assertFalse(coordinator.answerApproval("old", true))
        assertTrue(coordinator.answerApproval("replacement", true))
        assertTrue(replacement.await())
        assertNull(state.approval)
    }

}
