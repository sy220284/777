package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTeamStartRecoveryTest {
    private fun job(status: String, pending: Int = 0) =
        LocalJobInfo(id = "team-agent", label = "team", status = status, pendingMessageCount = pending)

    @Test
    fun cancelledUncommittedStartRestoresCreatedMember() {
        assertEquals(
            LocalTeamMemberPhase.CREATED,
            recoverTeamStartPhase(LocalTeamMemberPhase.CREATED, null, false, true),
        )
    }

    @Test
    fun cancelledStartWithDurableChildCannotClaimFailure() {
        assertEquals(
            LocalTeamMemberPhase.ACTIVE,
            recoverTeamStartPhase(LocalTeamMemberPhase.CREATED, job("running"), false, true),
        )
    }

    @Test
    fun disabledMemberRemainsDisabledUntilResumeIsObserved() {
        assertEquals(
            LocalTeamMemberPhase.DISABLED,
            recoverTeamStartPhase(LocalTeamMemberPhase.DISABLED, job("dormant"), false, true),
        )
        assertEquals(
            LocalTeamMemberPhase.ACTIVE,
            recoverTeamStartPhase(LocalTeamMemberPhase.DISABLED, job("dormant", pending = 1), false, true),
        )
        assertEquals(
            LocalTeamMemberPhase.ACTIVE,
            recoverTeamStartPhase(LocalTeamMemberPhase.DISABLED, job("dormant"), true, true),
        )
    }

    @Test
    fun failedBackgroundChildIsNotReportedActive() {
        assertEquals(
            LocalTeamMemberPhase.FAILED,
            recoverTeamStartPhase(LocalTeamMemberPhase.CREATED, job("killed"), true, true),
        )
    }
}
