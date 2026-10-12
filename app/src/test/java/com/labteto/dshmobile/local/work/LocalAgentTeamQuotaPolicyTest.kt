package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentTeamQuotaPolicyTest {
    private fun member(index: Int, phase: LocalTeamMemberPhase) = LocalTeamMemberSnapshot(
        id = "member-$index", jobId = "job-$index", name = "agent-$index",
        description = "", provider = "local-subagent",
        context = LocalTeamMemberContext.FRESH, phase = phase,
    )

    private fun task(index: Int, status: LocalTeamTaskStatus) = LocalTeamTaskSnapshot(
        id = "task-$index", revision = 1, subject = "task", description = "", status = status,
    )

    @Test fun historicalTerminatedMembersDoNotOccupyLiveSlots() {
        val history = (1..400).map { member(it, LocalTeamMemberPhase.ACTIVE) } +
            member(401, LocalTeamMemberPhase.CREATED) +
            member(402, LocalTeamMemberPhase.PROVISIONING)
        // The 400 completed children are still in the event-backed projection for audit,
        // but only newly reserved capacity should count against a concurrent limit.
        assertEquals(2, activeTeamMemberReservations(history, emptySet()))
        assertEquals(3, activeTeamMemberReservations(history, setOf("job-20")))
        assertTrue(history.size > LocalAgentTeamContract.MAX_TEAM_MEMBER_HISTORY)
    }

    @Test fun completedAndDeletedTasksAreHistoryNotActiveCapacity() {
        val tasks = (1..2_100).map { task(it, LocalTeamTaskStatus.COMPLETED) } +
            task(2_101, LocalTeamTaskStatus.PENDING) +
            task(2_102, LocalTeamTaskStatus.IN_PROGRESS) +
            task(2_103, LocalTeamTaskStatus.DELETED)
        assertEquals(2, activeTeamTaskReservations(tasks))
        assertTrue(tasks.size > LocalAgentTeamContract.MAX_TEAM_TASK_HISTORY)
    }
}
