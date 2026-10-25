package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAgentTeamRoleCodecTest {
    @Test fun workspaceRepositorySurvivesRecoveryAndCannotChangeOnReenable() {
        val original = member().copy(workspaceRepository = "projects/app")
        val recovered = LocalAgentTeamEventCodec.decodeMember(original.toEvent("session-role"))
        assertEquals("projects/app", recovered.workspaceRepository)
        val disabled = recovered.copy(phase = LocalTeamMemberPhase.DISABLED)
        LocalAgentTeamReducer.validateMemberTransition(listOf(recovered), disabled)
        assertThrows(IllegalArgumentException::class.java) {
            LocalAgentTeamReducer.validateMemberTransition(listOf(disabled), disabled.copy(
                phase = LocalTeamMemberPhase.PROVISIONING, workspaceRepository = "other",
            ))
        }
        assertNull(LocalAgentTeamEventCodec.decodeMember(member().toEvent("session-role")).workspaceRepository)
    }

    @Test fun corruptWorkspaceRepositoryCannotSilentlyRestoreSharedExecution() {
        val durable = member().toEvent("session-role")
        val body = durable.getValue("member") as JsonObject
        val corrupt = JsonObject(durable + ("member" to JsonObject(body + ("workspaceRepository" to JsonPrimitive(1)))))
        assertTrue(runCatching { LocalAgentTeamEventCodec.decodeMember(corrupt) }.isFailure)
    }

    private fun member(role: String = "general") = LocalTeamMemberSnapshot(
        id = "member-role",
        jobId = "job-team-role",
        name = "worker",
        description = "independent verification",
        provider = LocalAgentTeamContract.LOCAL_SUBAGENT_PROVIDER,
        context = LocalTeamMemberContext.FRESH,
        phase = LocalTeamMemberPhase.ACTIVE,
        role = role,
    )

    @Test fun roleSurvivesDurableTeamMemberEvent() {
        val durable = member("verify").toEvent("session-role")
        val restored = LocalAgentTeamEventCodec.decodeMember(durable)
        assertEquals("verify", restored.role)
        assertEquals("job-team-role", restored.jobId)
        assertFalse(
            com.labteto.dshmobile.local.agent.bindLocalAgentRole(
                restored.role, "", parentMutationAllowed = true,
            ).allowMutation,
        )
    }

    @Test fun oldV2EventWithoutRoleDefaultsToGeneral() {
        val durable = member().toEvent("session-role")
        val payload = durable["member"] as JsonObject
        val legacy = JsonObject(durable + ("member" to JsonObject(payload - "role")))
        val restored = LocalAgentTeamEventCodec.decodeMember(legacy)
        assertEquals("general", restored.role)
        assertTrue(
            com.labteto.dshmobile.local.agent.bindLocalAgentRole(
                restored.role, "", parentMutationAllowed = true,
            ).allowMutation,
        )
    }

    @Test fun corruptedPersistedRoleFailsClosed() {
        val durable = member("verify").toEvent("session-role")
        val payload = durable["member"] as JsonObject
        val tampered = JsonObject(durable + ("member" to JsonObject(payload + ("role" to JsonPrimitive("superuser")))))
        assertTrue(runCatching { LocalAgentTeamEventCodec.decodeMember(tampered) }.isFailure)
    }
}
