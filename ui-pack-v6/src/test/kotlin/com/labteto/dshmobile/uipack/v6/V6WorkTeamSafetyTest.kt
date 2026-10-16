package com.labteto.dshmobile.uipack.v6

import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.UiWorkTeamMember
import com.labteto.dshmobile.uicontract.UiWorkTeamSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Freeze the reviewed member job IDs; never stop a new child job by accident. */
class V6WorkTeamSafetyTest {
    private val target = UiSessionIdentity("work-session", "activation", 12, null, null)
    private fun member(id: String, job: String) =
        UiWorkTeamMember(id, job, "agent-$id", "running", "tool", null, 0, null)
    private fun snapshot(
        identity: UiSessionIdentity? = target,
        jobs: List<UiWorkTeamMember> = listOf(member("one", "job-1"), member("two", "job-2")),
    ) = UiWorkTeamSnapshot(identity, jobs, false, null)

    @Test fun theReviewedJobSetIsAllowedUnchanged() {
        assertTrue(canStopV6WorkTeam(target, setOf("job-1", "job-2"), snapshot()))
    }

    @Test fun replacementJobOrNewMemberInvalidatesConfirmation() {
        assertFalse(canStopV6WorkTeam(target, setOf("job-1", "job-2"),
            snapshot(jobs = listOf(member("one", "job-1"), member("two", "job-3")))))
        assertFalse(canStopV6WorkTeam(target, setOf("job-1", "job-2"),
            snapshot(jobs = listOf(member("one", "job-1"),
                member("two", "job-2"), member("three", "job-3")))))
    }

    @Test fun sessionTransitionOrEmptySelectionCannotStopJobs() {
        assertFalse(canStopV6WorkTeam(target, emptySet(), snapshot()))
        assertFalse(canStopV6WorkTeam(target, setOf("job-1", "job-2"),
            snapshot(identity = null)))
        assertFalse(canStopV6WorkTeam(target, setOf("job-1", "job-2"),
            snapshot(identity = UiSessionIdentity("other", "different", 12, null, null))))
    }
}
