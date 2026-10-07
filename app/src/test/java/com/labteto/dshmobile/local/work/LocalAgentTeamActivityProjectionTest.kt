package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentTeamActivityProjectionTest {
    @Test
    fun derivesRuntimeActivityWithoutChangingDurableMemberPhase() {
        val member = LocalTeamMemberSnapshot(
            id = "m1",
            jobId = "job-1",
            name = "researcher",
            description = "资料核验",
            provider = "local",
            context = LocalTeamMemberContext.FRESH,
            phase = LocalTeamMemberPhase.ACTIVE,
        )
        val team = LocalTeamProjection(
            members = listOf(member),
            tasks = listOf(
                LocalTeamTaskSnapshot(
                    id = "task-1",
                    revision = 1,
                    subject = "核查资料",
                    description = "",
                    status = LocalTeamTaskStatus.IN_PROGRESS,
                    ownerId = "m1",
                ),
            ),
        )

        val projected = projectLocalAgentTeamActivity(
            team,
            jobs = listOf(LocalJobInfo("job-1", "researcher", "running", isAgent = true)),
        )

        assertEquals(LocalTeamMemberPhase.ACTIVE, member.phase)
        assertEquals(LocalTeamMemberActivity.RUNNING, projected.members.single().activity)
        assertEquals("核查资料", projected.members.single().currentTask)
        assertEquals(0, projected.completedTasks)
        assertEquals(1, projected.totalTasks)
    }

    @Test
    fun derivesBlockedAndDormantFromExistingFacts() {
        val team = LocalTeamProjection(
            members = listOf(
                member("m1", "job-1", "researcher"),
                member("m2", "job-2", "writer"),
            ),
            tasks = listOf(
                LocalTeamTaskSnapshot(
                    id = "task-1",
                    revision = 1,
                    subject = "先查资料",
                    description = "",
                    status = LocalTeamTaskStatus.IN_PROGRESS,
                    ownerId = "m1",
                ),
                LocalTeamTaskSnapshot(
                    id = "task-2",
                    revision = 1,
                    subject = "再写报告",
                    description = "",
                    status = LocalTeamTaskStatus.IN_PROGRESS,
                    ownerId = "m2",
                    blockedBy = listOf("task-1"),
                ),
            ),
        )

        val projection = projectLocalAgentTeamActivity(
            team,
            jobs = listOf(
                LocalJobInfo("job-1", "researcher", "dormant", isAgent = true),
                LocalJobInfo("job-2", "writer", "running", isAgent = true),
            ),
        )

        assertEquals(LocalTeamMemberActivity.DORMANT, projection.members[0].activity)
        assertEquals(LocalTeamMemberActivity.BLOCKED, projection.members[1].activity)
    }

    private fun member(id: String, jobId: String, name: String) = LocalTeamMemberSnapshot(
        id = id,
        jobId = jobId,
        name = name,
        description = "",
        provider = "local",
        context = LocalTeamMemberContext.FRESH,
        phase = LocalTeamMemberPhase.ACTIVE,
    )
}
