package com.labteto.dshmobile.local.work

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalTeamWaitSemanticsTest {
    private fun member(status: String) = LocalTeamWaitMemberState(
        id = "member-1", name = "verifier", activity = status,
    )

    @Test fun reportsRunningToIdleAsMemberTurnTransitionNotTaskCompletion() {
        val from = LocalTeamWaitSnapshot(11, listOf(member("running")))
        val to = LocalTeamWaitSnapshot(11, listOf(member("idle")))
        val result = renderLocalTeamWaitTransition(from, to)
        assertTrue(result.contains("member-1(verifier):running->idle"))
        assertTrue(result.contains("task_completion=unverified"))
        assertFalse(result.contains("->completed"))
    }

    @Test fun distinguishesEventOnlyChangesAndMemberDeparture() {
        val old = LocalTeamWaitSnapshot(5, listOf(member("idle")))
        val eventOnly = LocalTeamWaitSnapshot(6, listOf(member("idle")))
        assertTrue(renderLocalTeamWaitTransition(old, eventOnly).contains("member_changes=none"))
        val removed = LocalTeamWaitSnapshot(7, emptyList())
        val description = renderLocalTeamWaitTransition(old, removed)
        assertTrue(description.contains("idle->absent"))
        assertTrue(description.contains("member_count=0"))
    }

    @Test fun preservesStableIdentityWhenNewTurnStarts() {
        val previous = LocalTeamWaitSnapshot(12, listOf(member("idle")))
        val current = LocalTeamWaitSnapshot(13, listOf(member("running")))
        assertTrue(renderLocalTeamWaitTransition(previous, current)
            .contains("member-1(verifier):idle->running"))
    }
}
