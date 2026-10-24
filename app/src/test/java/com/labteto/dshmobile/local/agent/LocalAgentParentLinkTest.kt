package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRunIdentity
import com.labteto.dshmobile.harness.agent.AgentRunOwnership
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentParentLinkTest {
    @Test fun liveRunIdentityRoundTripsThroughDurableParentLink() = runBlocking {
        val owners = AgentRunOwnership()
        val identity = AgentRunIdentity("session-a", "work", "run-root")
        owners.claim(identity) {}
        val captured = withContext(owners.executionContext(identity)) {
            captureLocalAgentParentLink("tool-agent")
        }
        val link = requireNotNull(captured)
        assertEquals("session-a", link.sessionId)
        assertEquals("run-root", link.parentRunId)
        assertEquals("work", link.parentSlotId)
        assertEquals("tool-agent", link.parentToolCallId)
        assertEquals(link, decodeLocalAgentParentLink(link.toJson()))

        val spec = buildLocalDelegatedLaunchSpec(
            kind = LocalAgentDelegationKind.FORK, task = "verify",
            instructions = "", modelOverride = null, maxSteps = 12,
            parentCallId = "tool-agent", parentLink = link,
        )
        assertEquals(link, spec.parentLink)
    }

    @Test fun expiredParentCannotClaimAuthorityForNewChild() = runBlocking {
        val owners = AgentRunOwnership()
        val old = AgentRunIdentity("session-a", "work", "old-run")
        owners.claim(old) {}
        owners.claim(old.copy(runId = "new-run")) {}
        assertTrue(runCatching {
            withContext(owners.executionContext(old)) {
                captureLocalAgentParentLink("stale-tool")
            }
        }.isFailure)
    }

    @Test fun oldPayloadWithoutParentLinkRemainsCompatible() {
        assertNull(decodeLocalAgentParentLink(null))
        assertTrue(runCatching {
            decodeLocalAgentParentLink(JsonObject(mapOf("version" to JsonPrimitive(2))))
        }.isFailure)
        assertTrue(runCatching {
            decodeLocalAgentParentLink(JsonObject(mapOf("version" to JsonPrimitive(1))))
        }.isFailure)
    }

    @Test fun missingRunContextDoesNotInventGlobalParent() = runBlocking {
        assertNull(captureLocalAgentParentLink())
    }
}
