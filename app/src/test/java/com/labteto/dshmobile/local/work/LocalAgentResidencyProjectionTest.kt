package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import com.labteto.dshmobile.local.agent.LocalAgentParentLink
import com.labteto.dshmobile.local.agent.toJson
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobRegistration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Residency is a pure read of persisted identity. Inspecting a child must never
 * manufacture a Job or replace its recorded parent identity during cold recovery.
 */
class LocalAgentResidencyProjectionTest {
    private val parent = LocalAgentParentLink(
        sessionId = "session-1", parentRunId = "run-parent",
        parentSlotId = "slot-parent", parentToolCallId = "call-1",
    )

    private fun registration(
        status: String = "interrupted",
        owner: String = "session-1",
        payload: String? = buildJsonObject {
            put("session_id", "session-1")
            put("parent_link", parent.toJson())
        }.toString(),
        agent: Boolean = true,
    ): LocalJobRegistration {
        val snapshot = JobSnapshot(
            id = "job-agent-1", label = "子代理：worker",
            status = status, ownerId = owner, continuable = true,
            resumeKind = "subagent_readonly", resumePayload = payload,
        )
        return LocalJobRegistration(
            LocalJobInfo(id = snapshot.id, label = snapshot.label, status = snapshot.status,
                ownerSessionId = snapshot.ownerId, isAgent = agent, continuable = true,
                updatedAt = snapshot.updatedAt),
            snapshot,
        )
    }

    @Test fun coldIdentityRetainsExactParentWithoutStartingModelOrJob() {
        val record = registration()
        val view = projectLocalAgentResidency(record)
        assertEquals(LocalAgentRuntimeResidency.UNLOADED, view.residency)
        assertEquals(record, view.registration)
        assertEquals("run-parent", view.recordedParent?.parentRunId)
        assertEquals("slot-parent", view.recordedParent?.parentSlotId)
        assertEquals("call-1", view.recordedParent?.parentToolCallId)
        assertEquals(LocalAgentRuntimeResidency.ACTIVE_JOB,
            projectLocalAgentResidency(registration(status = "running")).residency)
        assertEquals(LocalAgentRuntimeResidency.UNLOADED,
            projectLocalAgentResidency(registration(status = "dormant")).residency)
        assertEquals(LocalAgentRuntimeResidency.UNLOADED,
            projectLocalAgentResidency(registration(status = "waiting_slice")).residency)
        assertEquals(LocalAgentRuntimeResidency.TERMINAL,
            projectLocalAgentResidency(registration(status = "cancelled")).residency)
    }

    @Test fun historicalAgentWithoutParentRemainsInspectableButHasNoInventedParent() {
        val view = projectLocalAgentResidency(registration(payload = "{}"))
        assertNull(view.recordedParent)
        assertEquals(LocalAgentRuntimeResidency.UNLOADED, view.residency)
    }

    @Test fun foreignParentAndForgedAgentIdentityFailClosed() {
        val foreignLink = parent.copy(sessionId = "session-foreign")
        val foreign = buildJsonObject {
            put("session_id", "session-1")
            put("parent_link", foreignLink.toJson())
        }.toString()
        assertTrue(runCatching {
            projectLocalAgentResidency(registration(payload = foreign))
        }.exceptionOrNull()?.message.orEmpty().contains("AGENT_PARENT_SESSION_MISMATCH"))
        assertTrue(runCatching {
            projectLocalAgentResidency(registration(agent = false))
        }.exceptionOrNull()?.message.orEmpty().contains("AGENT_RESIDENCY_IDENTITY_MISMATCH"))
    }

    @Test fun corruptPersistedParentCannotBeSilentlyReparented() {
        val malformed = buildJsonObject {
            put("parent_link", buildJsonObject { put("version", 101) })
        }.toString()
        assertTrue(runCatching {
            projectLocalAgentResidency(registration(payload = malformed))
        }.isFailure)
        assertTrue(runCatching {
            projectLocalAgentResidency(registration(payload = "not-json"))
        }.isFailure)
    }
}
