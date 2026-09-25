package com.labteto.dshmobile.local

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInteractionCoordinatorTest {
    @Test
    fun staleApprovalIdCannotResolveCurrentInteraction() = runTest {
        val state = MutableStateFlow(LocalHarnessState())
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

        assertEquals("approval-1", state.value.pendingApproval?.callId)
        assertFalse(coordinator.answerApproval("stale-id", true))
        assertTrue(coordinator.answerApproval("approval-1", true))
        assertTrue(result.await())
        assertNull(state.value.pendingApproval)
    }

    @Test
    fun concurrentInteractionsAreSerializedInsteadOfOverwritingState() = runTest {
        val state = MutableStateFlow(LocalHarnessState())
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

        assertEquals("first", state.value.pendingApproval?.callId)
        assertNull(state.value.pendingQuestion)

        assertTrue(coordinator.answerApproval("first", true))
        assertTrue(approval.await())
        runCurrent()

        assertEquals("second", state.value.pendingQuestion?.callId)
        assertTrue(coordinator.answerQuestion("second", "继续"))
        assertEquals("继续", question.await())
        assertNull(state.value.pendingQuestion)
    }

    @Test
    fun cancelAllInvalidatesInteractionsAlreadyQueuedBehindCurrentOne() = runTest {
        val state = MutableStateFlow(LocalHarnessState())
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
        assertNull(state.value.pendingApproval)
        assertNull(state.value.pendingQuestion)
    }

    @Test
    fun cancellingQuestionUsesStableModelVisibleSemantic() = runTest {
        val state = MutableStateFlow(LocalHarnessState())
        val coordinator = LocalInteractionCoordinator(state)
        val question = async {
            coordinator.awaitQuestion(LocalQuestion("question-1", "请选择"))
        }
        runCurrent()

        assertTrue(coordinator.cancelQuestion("question-1"))
        assertEquals(LOCAL_QUESTION_CANCELLED_RESPONSE, question.await())
        assertNull(state.value.pendingQuestion)
    }
}
