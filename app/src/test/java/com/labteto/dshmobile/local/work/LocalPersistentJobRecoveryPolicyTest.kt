package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersistentJobRecoveryPolicyTest {
    private val json = Json

    private fun snapshot(
        status: String = "interrupted",
        owner: String? = "session-a",
        payload: String? = """{"session_id":"session-a"}""",
    ) = JobSnapshot(
        id = "job-a",
        label = "受保护的恢复记录",
        status = status,
        ownerId = owner,
        resumeKind = "subagent_readonly",
        resumePayload = payload,
    )

    @Test fun otherSessionMustNeverReclassifyKnownOwnerEvenWithBrokenPayload() {
        val broken = snapshot(payload = "{broken")
        assertFalse(isJobRecoveryOwnedBySession(broken, "session-b", json))
        assertTrue(isJobRecoveryOwnedBySession(broken, "session-a", json))
    }

    @Test fun ownerlessLegacyResumeMustHaveVerifiedSessionIdentity() {
        assertTrue(isJobRecoveryOwnedBySession(snapshot(owner = null), "session-a", json))
        assertFalse(isJobRecoveryOwnedBySession(snapshot(owner = null), "session-b", json))
        assertFalse(isJobRecoveryOwnedBySession(snapshot(owner = null, payload = "{broken"), "session-a", json))
        assertFalse(isJobRecoveryOwnedBySession(snapshot(owner = null, payload = null), "session-a", json))
    }

    @Test fun interruptedExternalOutcomeMustNotBecomeDefiniteFailure() {
        assertTrue(mustPreserveUnknownRecoveryOutcome(snapshot(status = "interrupted")))
        assertFalse(mustPreserveUnknownRecoveryOutcome(snapshot(status = "waiting_slice")))
        assertFalse(mustPreserveUnknownRecoveryOutcome(snapshot(status = "dormant")))
    }
}
