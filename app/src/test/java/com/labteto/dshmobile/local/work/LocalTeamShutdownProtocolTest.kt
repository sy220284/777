package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTeamShutdownProtocolTest {
    private val session = "lead-session"

    private fun message(kind: LocalTeamMessageKind, sender: String = session) =
        LocalTeamMessageSnapshot(
            id = "team-msg-012345",
            senderId = sender,
            senderName = if (sender == session) "lead" else "peer",
            targetId = "member-1",
            content = "finish handoff first",
            kind = kind,
        )

    @Test fun trustedShutdownRequestPersistsAsTypedDurableMessage() {
        val original = message(LocalTeamMessageKind.SHUTDOWN_REQUEST)
        val restored = LocalAgentTeamEventCodec.decodeMessage(original.toEvent(session))
        assertEquals(original, restored)
        assertEquals(LocalTeamMessageKind.SHUTDOWN_REQUEST, restored.kind)
    }

    @Test fun legacyMessageWithoutKindRemainsOrdinary() {
        val original = message(LocalTeamMessageKind.MESSAGE)
        val durable = original.toEvent(session)
        val payload = durable["message"] as JsonObject
        assertFalse("kind" in payload)
        assertEquals(LocalTeamMessageKind.MESSAGE, LocalAgentTeamEventCodec.decodeMessage(durable).kind)
    }

    @Test fun peerCannotForgePrivilegedShutdownRequestInDurableLog() {
        val forged = message(LocalTeamMessageKind.SHUTDOWN_REQUEST, "member-peer")
        assertTrue(runCatching { LocalAgentTeamEventCodec.decodeMessage(forged.toEvent(session)) }.isFailure)
    }

    @Test fun unknownControlKindFailsClosed() {
        val durable = message(LocalTeamMessageKind.MESSAGE).toEvent(session)
        val inner = durable["message"] as JsonObject
        val corrupted = JsonObject(durable + ("message" to JsonObject(
            inner + ("kind" to JsonPrimitive("escalate"))
        )))
        assertTrue(runCatching { LocalAgentTeamEventCodec.decodeMessage(corrupted) }.isFailure)
    }
}
