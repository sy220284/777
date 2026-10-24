package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTeamIdleTaskDispatchTest {
    private fun member(writable: Boolean = false) = LocalTeamMemberSnapshot(
        id = "member-1",
        jobId = "job-team-1",
        name = "worker",
        description = "",
        provider = "local-subagent",
        context = LocalTeamMemberContext.FRESH,
        phase = LocalTeamMemberPhase.ACTIVE,
        mutableToolsEnabled = writable,
    )

    private fun task(
        id: String,
        blockers: List<String> = emptyList(),
        writeScopes: List<String> = emptyList(),
    ) = LocalTeamTaskSnapshot(
        id = id, revision = 1, subject = id, description = "",
        status = LocalTeamTaskStatus.PENDING, blockedBy = blockers,
        writeScopes = writeScopes,
    )

    private fun activity(
        status: LocalTeamMemberActivity = LocalTeamMemberActivity.IDLE,
        pending: Int = 0,
        currentTask: String? = null,
    ) = LocalAgentTeamActivityProjection(
        members = listOf(LocalTeamMemberActivityView(
            memberId = "member-1", memberName = "worker", activity = status,
            pendingMessageCount = pending, currentTaskId = currentTask,
        )),
    )

    @Test fun idleMemberClaimsEarliestReadyTaskOnlyAfterDependenciesSettle() {
        val pending = task("task-2", blockers = listOf("task-1"))
        val prior = task("task-1")
        val state = LocalTeamProjection(members = listOf(member()), tasks = listOf(pending, prior))
        assertEquals("task-1", nextIdleTeamTaskAssignment(state, activity())?.second?.id)
        val done = prior.copy(status = LocalTeamTaskStatus.COMPLETED)
        assertEquals("task-2", nextIdleTeamTaskAssignment(state.copy(tasks = listOf(pending, done)), activity())?.second?.id)
    }

    @Test fun neverPreemptBusyOrMailReceivingMember() {
        val state = LocalTeamProjection(members = listOf(member()), tasks = listOf(task("task-1")))
        assertNull(nextIdleTeamTaskAssignment(state, activity(LocalTeamMemberActivity.RUNNING)))
        assertNull(nextIdleTeamTaskAssignment(state, activity(pending = 1)))
        assertNull(nextIdleTeamTaskAssignment(state, activity(currentTask = "task-7")))
        assertNull(nextIdleTeamTaskAssignment(state.copy(failure = "event corrupt"), activity()))
    }

    @Test fun writableAssignmentsRequireActualGrantAndNonOverlappingScope() {
        val pending = task("task-2", writeScopes = listOf("src"))
        val running = task("task-1", writeScopes = listOf("src/lib")).copy(
            status = LocalTeamTaskStatus.IN_PROGRESS, ownerId = "member-other",
        )
        assertNull(nextIdleTeamTaskAssignment(
            LocalTeamProjection(members = listOf(member()), tasks = listOf(pending)), activity(),
        ))
        assertNull(nextIdleTeamTaskAssignment(
            LocalTeamProjection(members = listOf(member(writable = true)), tasks = listOf(pending, running)),
            activity(),
        ))
        assertNotNull(nextIdleTeamTaskAssignment(
            LocalTeamProjection(members = listOf(member(writable = true)), tasks = listOf(pending)),
            activity(),
        ))
    }


    @Test fun replayRejectsReplacedAutoDispatchIdentityAndOwner() {
        val first = task("task-1")
        val reserved = first.copy(
            revision = 2, status = LocalTeamTaskStatus.IN_PROGRESS,
            ownerId = "member-1", autoDispatchMessageId = "team-msg-1234567890abcdef1234",
        )
        LocalAgentTeamReducer.validateTaskTransition(listOf(first), first, reserved)
        val replaced = reserved.copy(
            revision = 3, autoDispatchMessageId = "team-msg-1234567890abcdef9999",
        )
        val replacedFailure = runCatching {
            LocalAgentTeamReducer.validateTaskTransition(listOf(reserved), reserved, replaced)
        }.exceptionOrNull()
        assertNotNull(replacedFailure)
        assertEquals("TEAM_TASK_AUTO_DISPATCH_REPLACED", replacedFailure?.message)

        val changedOwner = reserved.copy(revision = 3, ownerId = "member-other")
        val ownerFailure = runCatching {
            LocalAgentTeamReducer.validateTaskTransition(listOf(reserved), reserved, changedOwner)
        }.exceptionOrNull()
        assertNotNull(ownerFailure)
        assertEquals("TEAM_TASK_AUTO_OWNER_CHANGED", ownerFailure?.message)
    }

    @Test fun autoDispatchIdentitySurvivesEventRoundTripAndLegacyHasNoIntent() {
        val reserved = task("task-3").copy(
            revision = 2,
            status = LocalTeamTaskStatus.IN_PROGRESS,
            ownerId = "member-1",
            autoDispatchMessageId = "team-msg-1234567890abcdef1234",
        )
        val encoded = with(LocalAgentTeamEventCodec) { reserved.toEvent("session") }
        val decoded = LocalAgentTeamEventCodec.decodeTask(encoded)
        assertEquals(reserved, decoded)

        val legacy = with(LocalAgentTeamEventCodec) {
            reserved.copy(autoDispatchMessageId = null).toEvent("session")
        }
        assertNull(LocalAgentTeamEventCodec.decodeTask(legacy).autoDispatchMessageId)
    }
}
