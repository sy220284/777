package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSessionInteractionStoreTest {
    @Test
    fun backgroundSessionApprovalCannotOverwriteVisibleSessionApproval() {
        val store = PendingSessionInteractionStore()
        store.installApproval(approval("session-a", "approval-a"))
        store.installApproval(approval("session-b", "approval-b"))

        assertEquals("approval-a", store.approvalForSession("session-a")?.approvalId)
        assertEquals("approval-b", store.approvalForSession("session-b")?.approvalId)
    }

    @Test
    fun lateApprovalSettlementCannotRemoveReplacementForSameSession() {
        val store = PendingSessionInteractionStore()
        store.installApproval(approval("session-a", "approval-old"))
        store.installApproval(approval("session-a", "approval-new"))

        val removed = store.forgetApproval("approval-old")!!

        assertTrue(removed.sessionStillPending)
        assertEquals("approval-new", store.approvalForSession("session-a")?.approvalId)
        assertNull(store.approvalForEvent("approval-old"))
        assertEquals("approval-new", store.approvalForEvent("approval-new")?.approvalId)
    }

    @Test
    fun backgroundSessionQuestionCannotOverwriteVisibleSessionQuestion() {
        val store = PendingSessionInteractionStore()
        store.installQuestions(PendingQuestions("session-a", "question-a", emptyList()))
        store.installQuestions(PendingQuestions("session-b", "question-b", emptyList()))

        assertEquals("question-a", store.questionsForSession("session-a")?.rpcId)
        assertEquals("question-b", store.questionsForSession("session-b")?.rpcId)
    }

    @Test
    fun lateQuestionSettlementCannotRemoveReplacementForSameSession() {
        val store = PendingSessionInteractionStore()
        store.installQuestions(PendingQuestions("session-a", "question-old", emptyList()))
        store.installQuestions(PendingQuestions("session-a", "question-new", emptyList()))

        assertNull(store.questionSessionForEvent("question-old"))
        assertFalse(store.forgetQuestions("session-a", "question-old"))
        assertEquals("question-new", store.questionsForSession("session-a")?.rpcId)
        assertTrue(store.forgetQuestions("session-a", "question-new"))
        assertNull(store.questionsForSession("session-a"))
    }

    @Test
    fun retiringGenerationClearsAllSessionCardsTogether() {
        val store = PendingSessionInteractionStore()
        store.installApproval(approval("session-a", "approval-a"))
        store.installQuestions(PendingQuestions("session-b", "question-b", emptyList()))

        assertEquals(setOf("session-a", "session-b"), store.clear())
        assertNull(store.approvalForSession("session-a"))
        assertNull(store.questionsForSession("session-b"))
        assertNull(store.approvalForEvent("approval-a"))
        assertNull(store.questionSessionForEvent("question-b"))
    }

    private fun approval(sessionId: String, eventId: String) = PendingApproval(
        sessionId = sessionId,
        approvalId = eventId,
        rpcId = eventId,
        toolName = "write",
        reason = null,
    )

    @Test
    fun permissionChangesStayOwnedByTheirSessions() {
        val store = PendingSessionInteractionStore()
        store.installPermission("session-a", "safe")
        store.installPermission("session-b", "full")

        assertEquals("safe", store.permissionForSession("session-a"))
        assertEquals("full", store.permissionForSession("session-b"))
    }

    @Test
    fun latePermissionFailureCannotClearNewerPermissionForSameSession() {
        val store = PendingSessionInteractionStore()
        store.installPermission("session-a", "safe")
        store.installPermission("session-a", "full")

        assertFalse(store.forgetPermission("session-a", "safe"))
        assertEquals("full", store.permissionForSession("session-a"))
        assertTrue(store.forgetPermission("session-a", "full"))
        assertNull(store.permissionForSession("session-a"))
    }

}
