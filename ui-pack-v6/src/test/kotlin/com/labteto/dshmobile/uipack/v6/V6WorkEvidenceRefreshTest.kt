package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWorkEvidenceSnapshot
import com.labteto.dshmobile.uicontract.UiWorkRequirement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI selection is only a read projection; changes cannot authorize a stale Work link. */
class V6WorkEvidenceRefreshTest {
    private val identity = UiSessionIdentity("session", "activation", 1L, null, null)

    private fun snapshot(goal: String?, requirement: String = "报告",
        session: UiSessionIdentity = identity,
    ) = UiWorkEvidenceSnapshot(session,
        listOf(UiWorkRequirement(0, requirement, "pending")),
        emptyList(), emptyList(), goal)

    @Test fun goalOrTaskOrActivationChangeRevokesOldSelection() {
        val before = snapshot("goal-a")
        assertTrue(v6EvidenceSelectionObsolete(before, snapshot("goal-b")))
        assertTrue(v6EvidenceSelectionObsolete(before, snapshot("goal-a", requirement = "新任务")))
        assertTrue(v6EvidenceSelectionObsolete(before, snapshot("goal-a",
            session = identity.copy(contextGeneration = 2L))))
    }

    @Test fun identicalProjectionAndFirstReadDoNotInvalidateSelection() {
        val before = snapshot("goal-a")
        assertFalse(v6EvidenceSelectionObsolete(before, before.copy()))
        assertFalse(v6EvidenceSelectionObsolete(null, before))
    }
}
