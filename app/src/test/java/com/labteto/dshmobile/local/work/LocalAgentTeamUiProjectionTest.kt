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
        assertEquals("idle", completed.members.single().activity)
        assertTrue(completed.members.single().awaitingReview)
        assertTrue(completed.members.single().canReceiveMessage)
        assertEquals(0, completed.completedMemberCount)
    }

    @Test
    fun standaloneChildAppearsInTheExistingWorkCollaborationSurface() {
        val standalone = LocalJobInfo("job-child-1", "子代理：代码核验", "running", isAgent = true)
        val teamMember = LocalJobInfo("job-team-1", "子代理：团队助手", "running", isAgent = true)
        val otherProcess = LocalJobInfo("job-terminal", "终端", "running", isAgent = false)
        val view = LocalAgentTeamUiState().withJobs(listOf(standalone, teamMember, otherProcess))
        assertEquals(listOf("job-child-1"), view.standaloneAgents.map { it.id })
        assertEquals("代码核验", view.standaloneAgents.single().label)
        assertTrue(view.visible)
        val resumed = view.withJobs(listOf(standalone.copy(status = "interrupted"), teamMember))
        assertEquals("interrupted", resumed.standaloneAgents.single().status)
        assertEquals(1, resumed.standaloneAgents.size)
    }

    @Test
    fun teamAndStandaloneRemainSeparateViewsOfOneJobOwner() {
        val original = LocalAgentTeamUiState(members = listOf(member("active").copy(jobId = "job-team-2")))
        val view = original.withJobs(listOf(
            LocalJobInfo("job-team-2", "团队成员", "running", isAgent = true),
            LocalJobInfo("child-2", "子代理：检索", "dormant", isAgent = true),
        ))
        assertEquals("running", view.members.single().activity)
        assertEquals(listOf("child-2"), view.standaloneAgents.map { it.id })
    }

    private fun member(phase: String) = LocalAgentTeamMemberUiState(
        id = "member", jobId = "job", name = "助手", description = "核验资料",
        phase = phase, activity = "waiting",
    )
}
