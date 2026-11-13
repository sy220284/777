package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalTeamSpawnAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun member(phase: LocalTeamMemberPhase) = LocalTeamMemberSnapshot(
        id = "member-1", jobId = "job-team-1", name = "worker",
        description = "", provider = "local-subagent",
        context = LocalTeamMemberContext.FRESH, phase = phase,
        provisionalNewJob = if (phase == LocalTeamMemberPhase.PROVISIONING) true else null,
    )

    private fun put(log: LocalSessionEventLog, session: String, phase: LocalTeamMemberPhase) {
        log.append(LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            with(LocalAgentTeamEventCodec) { member(phase).toEvent(session) })
    }

    @Test fun modelExecutionCannotBeginBeforeMemberIsDurablyCommitted() = runBlocking {
        val session = "admission-wait"
        val log = LocalSessionEventLog(File(temporary.root, "$session.jsonl"), Json)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = AtomicBoolean(false)
        try {
            put(log, session, LocalTeamMemberPhase.PROVISIONING)
            val jobs = LocalJobManager(scope, onChanged = {})
            val started = jobs.startPersistentResult(
                label = "子代理：worker", resumeKind = "subagent_readonly",
                resumePayload = "{}", ownerSessionId = session,
                continuable = true, requestedId = "job-team-1",
            ) { jobId, _ ->
                awaitTeamMemberSpawnCommit(log, session, jobId, 3_000L)
                entered.set(true)
                "ready"
            }
            assertTrue(started.accepted)
            delay(120L)
            assertFalse(entered.get())
            assertEquals("running", jobs.snapshots().single().status)
            put(log, session, LocalTeamMemberPhase.ACTIVE)
            withTimeout(2_000L) {
                while (jobs.snapshots().single().status != "dormant") delay(10L)
            }
            assertTrue(entered.get())
            assertEquals("dormant", jobs.snapshots().single().status)
        } finally {
            scope.cancel()
        }
    }

    @Test fun failedAndUncommittedMembersNeverReceiveModelAdmission() = runBlocking {
        val session = "admission-rejected"
        val log = LocalSessionEventLog(File(temporary.root, "$session.jsonl"), Json)
        put(log, session, LocalTeamMemberPhase.PROVISIONING)
        put(log, session, LocalTeamMemberPhase.FAILED)
        val failure = runCatching {
            awaitTeamMemberSpawnCommit(log, session, "job-team-1", 150L)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("TEAM_SPAWN_NOT_COMMITTED"))
        assertTrue(runCatching {
            awaitTeamMemberSpawnCommit(log, "foreign-session", "job-team-1", 80L)
        }.isFailure)
    }
}
