package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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


    @Test fun malformedCompletionProofCannotMasqueradeAsVerifiedTask() {
        val original = task("task-1")
        val completed = original.copy(
            revision = 2, status = LocalTeamTaskStatus.COMPLETED,
            completionResultId = "claimed-without-event",
            completionEvidenceSequence = 42L,
        )
        val err = runCatching {
            LocalAgentTeamReducer.validateTaskTransition(listOf(original), original, completed)
        }.exceptionOrNull()
        // Reducer checks structural shape; actual result ownership is checked by Runtime.
        assertNull(err)
        assertEquals("TEAM_TASK_COMPLETION_EVIDENCE_PAIR_REQUIRED",
            runCatching {
                LocalAgentTeamReducer.validateTaskTransition(listOf(original), original,
                    completed.copy(completionEvidenceSequence = null))
            }.exceptionOrNull()?.message)
        assertEquals("TEAM_TASK_COMPLETION_EVIDENCE_INVALID",
            runCatching {
                LocalAgentTeamReducer.validateTaskTransition(listOf(original), original,
                    completed.copy(completionEvidenceSequence = -2L))
            }.exceptionOrNull()?.message)
        val revised = completed.copy(revision = 3, completionResultId = "different")
        assertEquals("TEAM_TASK_COMPLETION_EVIDENCE_IMMUTABLE",
            runCatching {
                LocalAgentTeamReducer.validateTaskTransition(listOf(completed), completed, revised)
            }.exceptionOrNull()?.message)
        val event = with(LocalAgentTeamEventCodec) { completed.toEvent("session") }
        assertEquals(completed, LocalAgentTeamEventCodec.decodeTask(event))
        val legacy = with(LocalAgentTeamEventCodec) {
            completed.copy(completionResultId = null, completionEvidenceSequence = null).toEvent("session")
        }
        assertNull(LocalAgentTeamEventCodec.decodeTask(legacy).completionResultId)
    }

    @Test fun malformedUserRevisionCannotMasqueradeAsLegacyTask() {
        val clean = with(LocalAgentTeamEventCodec) { task("task-1").toEvent("session") }
        val fields = clean.getValue("task").jsonObject
        val malformed = JsonObject(clean + ("task" to JsonObject(
            fields + ("rootUserRevision" to JsonPrimitive("invalid")),
        )))
        assertEquals("TEAM_TASK_USER_REVISION_INVALID",
            runCatching { LocalAgentTeamEventCodec.decodeTask(malformed) }.exceptionOrNull()?.message)
    }

    @Test fun previousUserTaskCannotAutoRestartAfterNewRootInstruction() {
        val stale = task("task-1").copy(rootUserRevision = 7L)
        val current = task("task-2")
        val team = LocalTeamProjection(members = listOf(member()), tasks = listOf(stale, current))
        assertEquals("task-2", nextIdleTeamTaskAssignment(team, activity(),
            currentRootRevision = 8L)?.second?.id)
        assertEquals("task-1", nextIdleTeamTaskAssignment(
            team.copy(tasks = listOf(stale)), activity(), currentRootRevision = 7L)?.second?.id)
        assertNull(nextIdleTeamTaskAssignment(
            team.copy(tasks = listOf(stale)), activity(), currentRootRevision = 8L))
    }

    @Test fun sameOwnerCannotRenewRootInstructionByEditingTask() {
        val start = task("task-1")
        val accepted = start.copy(revision = 2, status = LocalTeamTaskStatus.IN_PROGRESS,
            ownerId = "member-1", rootUserRevision = 4L)
        LocalAgentTeamReducer.validateTaskTransition(listOf(start), start, accepted)
        val replaced = accepted.copy(revision = 3, subject = "edited", rootUserRevision = 5L)
        val err = runCatching {
            LocalAgentTeamReducer.validateTaskTransition(listOf(accepted), accepted, replaced)
        }.exceptionOrNull()
        assertEquals("TEAM_TASK_ROOT_AUTHORITY_IMMUTABLE", err?.message)
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
            rootUserRevision = 42L,
        )
        val encoded = with(LocalAgentTeamEventCodec) { reserved.toEvent("session") }
        val decoded = LocalAgentTeamEventCodec.decodeTask(encoded)
        assertEquals(reserved, decoded)

        val legacy = with(LocalAgentTeamEventCodec) {
            reserved.copy(autoDispatchMessageId = null, rootUserRevision = null).toEvent("session")
        }
        assertNull(LocalAgentTeamEventCodec.decodeTask(legacy).autoDispatchMessageId)
        assertNull(LocalAgentTeamEventCodec.decodeTask(legacy).rootUserRevision)
    }
}
