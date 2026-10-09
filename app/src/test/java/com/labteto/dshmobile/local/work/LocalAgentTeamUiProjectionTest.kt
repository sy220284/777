package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentTeamUiProjectionTest {
    @Test
    fun staleRunningJobCannotReactivateInactiveMemberInUi() {
        val phases = listOf("created", "disabled", "dismissed", "failed", "provisioning")
        val members = phases.map { phase ->
            member(phase).copy(id = phase, jobId = phase)
        }
        val projected = LocalAgentTeamUiState(members = members).withJobs(
            members.map { LocalJobInfo(it.jobId, it.name, "running", isAgent = true) },
        )
        assertEquals(phases, projected.members.map { it.activity })
        assertEquals(0, projected.runningMemberCount)
        projected.members.forEach { assertFalse(it.canReceiveMessage) }
    }

    @Test
    fun activeMemberTracksStoppingAndFreshResultReview() {
        val team = LocalAgentTeamUiState(members = listOf(member("active").copy(hasCurrentTaskResult = true)))
        val stopping = team.withJobs(listOf(LocalJobInfo("job", "助手", "stopping", isAgent = true)))
        assertEquals("stopping", stopping.members.single().activity)
        assertEquals(1, stopping.stoppingMemberCount)
        assertEquals(0, stopping.runningMemberCount)
        assertFalse(stopping.members.single().awaitingReview)
        val completed = stopping.withJobs(listOf(LocalJobInfo("job", "助手", "completed", isAgent = true)))
        assertTrue(completed.members.single().awaitingReview)
        assertTrue(completed.members.single().canReceiveMessage)
        assertEquals(0, completed.completedMemberCount)
    }

    private fun member(phase: String) = LocalAgentTeamMemberUiState(
        id = "member", jobId = "job", name = "助手", description = "核验资料",
        phase = phase, activity = "waiting",
    )
}
