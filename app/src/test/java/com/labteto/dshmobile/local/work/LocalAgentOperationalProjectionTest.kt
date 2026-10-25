package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.toJson
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentOperationalProjectionTest {
    private fun job(id: String, owner: String, status: String = "running",
        agent: Boolean = true, inbox: Int = 0, timestamp: Long = 1L,
    ) = LocalJobInfo(id = id, label = "private-job", status = status,
        ownerSessionId = owner, isAgent = agent, continuable = agent,
        pendingMessageCount = inbox, updatedAt = timestamp)

    @Test fun topologyNeverLeaksForeignSessionOrNonAgentJobs() {
        val roster = renderLocalAgentLiveRoster("root", listOf(
            job("mine", "root", "running", inbox = 2),
            job("foreign-secret", "other", "running", timestamp = 5L),
            job("shell-job", "root", "running", agent = false),
        ), 10)
        assertTrue(roster.contains("owned=1"))
        assertTrue(roster.contains("job=mine status=running"))
        assertTrue(roster.contains("inbox_pending=2"))
        assertFalse(roster.contains("foreign-secret"))
        assertFalse(roster.contains("shell-job"))
        assertFalse(roster.contains("private-job"))
    }

    @Test fun boundedListMarksPartialAndSeparatesJobStateFromAcceptedResult() {
        val roster = renderLocalAgentLiveRoster("root", listOf(
            job("done", "root", "completed", timestamp = 1L),
            job("active", "root", "running", timestamp = 2L),
        ), 1)
        assertTrue(roster.contains("owned=2 displayed=1 partial=true"))
        assertTrue(roster.contains("running:1"))
        assertTrue(roster.contains("completed:1"))
        assertTrue(roster.contains("job_status_is_not_goal_acceptance=true"))
        assertTrue(roster.contains("job=active"))
        assertFalse(roster.contains("job=done"))
    }

    @Test fun malformedSessionOrDisplayBudgetCannotBroadenInspection() {
        assertTrue(runCatching {
            renderLocalAgentLiveRoster("", listOf(job("foreign", "other")), 5)
        }.isFailure)
        assertTrue(runCatching {
            renderLocalAgentLiveRoster("root", emptyList(), 500)
        }.isFailure)
    }
    private fun persistedChild(
        id: String,
        owner: String = "root",
        payload: String? = buildJsonObject {
            put("parent_link", LocalAgentParentLink(
                sessionId = "root",
                parentRunId = "parent-run",
                parentSlotId = "parent-slot",
            ).toJson())
        }.toString(),
    ): LocalJobRegistration {
        val snapshot = JobSnapshot(
            id = id, label = "子代理：worker", ownerId = owner,
            status = "interrupted", continuable = true,
            resumeKind = "subagent_readonly", resumePayload = payload,
        )
        return LocalJobRegistration(
            LocalJobInfo(
                id = id, label = "子代理：worker", ownerSessionId = owner,
                status = "interrupted", isAgent = true, continuable = true,
                updatedAt = snapshot.updatedAt,
            ), snapshot,
        )
    }

    @Test fun durableRosterProjectsColdParentIdentityWithoutStartingAnAgent() {
        val out = renderLocalAgentDurableLineage(
            "root", listOf(
                persistedChild("child-1"),
                persistedChild("foreign-secret", owner = "other"),
            ), 10,
        )
        assertTrue(out.contains("owned=1"))
        assertTrue(out.contains("job=child-1 residency=unloaded"))
        assertTrue(out.contains("parent_run=parent-run"))
        assertTrue(out.contains("parent_slot=parent-slot"))
        assertTrue(out.contains("parent_live=unverified"))
        assertFalse(out.contains("foreign-secret"))
    }

    @Test fun damagedOrLegacyParentNeverBecomesTrustedNewIdentity() {
        val out = renderLocalAgentDurableLineage("root", listOf(
            persistedChild("corrupt", payload = "{not json}"),
            persistedChild("legacy", payload = "{}"),
        ), 10)
        assertTrue(out.contains("job=corrupt lineage=unverifiable"))
        assertTrue(out.contains("persisted_registration_invalid=true"))
        assertTrue(out.contains("job=legacy residency=unloaded parent=legacy_or_root_unknown"))
        assertFalse(out.contains("parent_live=true"))
        assertTrue(runCatching {
            renderLocalAgentDurableLineage("root", emptyList(), 0)
        }.isFailure)
    }

}
