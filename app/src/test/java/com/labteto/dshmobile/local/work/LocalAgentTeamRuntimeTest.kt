package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.jobs.JobMessageAdmission
import com.labteto.dshmobile.harness.jobs.JobStartResult
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentTeamRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val logs = mutableListOf<LocalSessionEventLog>()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cleanup() {
        logs.forEach(LocalSessionEventLog::close)
        scopes.forEach(CoroutineScope::cancel)
    }

    @Test
    fun rosterRequiresProvisioningBeforeActive() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("team-a", "member-1", "worker", "active"),
        )

        val projection = fixture.runtime.project("team-a")

        assertTrue(projection.members.isEmpty())
        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("PROVISION"))
    }

    @Test
    fun queuedMinusDeliveredDefinesDurableMailbox() {
        val fixture = fixture("team-a")
        appendActiveMember(fixture.log, "team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_QUEUED,
            buildJsonObject {
                put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
                put("teamId", "team-a")
                put("message", buildJsonObject {
                    put("id", "team-msg-1")
                    put("senderId", "team-a")
                    put("senderName", "lead")
                    put("targetId", "member-1")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "检查结果")
                                },
                            ),
                        ),
                    )
                })
            },
        )

        assertEquals(
            listOf("team-msg-1"),
            fixture.runtime.project("team-a").pendingMessages.map { it.id },
        )

        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_DELIVERED,
            buildJsonObject {
                put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
                put("teamId", "team-a")
                put("messageId", "team-msg-1")
                put("targetId", "member-1")
            },
        )

        assertTrue(fixture.runtime.project("team-a").pendingMessages.isEmpty())
    }

    @Test
    fun inheritedTeamIdDoesNotLeakIntoForkProjection() {
        val fixture = fixture("fork-b")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("ancestor-a", "member-old", "old", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("fork-b", "member-new", "new", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("fork-b", "member-new", "new", "active"),
        )

        val projection = fixture.runtime.project("fork-b")

        assertEquals(listOf("member-new"), projection.members.map { it.id })
        assertNull(projection.failure)
    }

    @Test
    fun unsupportedTeamEventVersionFailsProjectionWithoutApplyingPayload() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            buildJsonObject {
                put("version", 99)
                put("teamId", "team-a")
                put("member", buildJsonObject {
                    put("id", "job-1")
                    put("name", "worker")
                    put("description", "")
                    put("provider", "local-subagent")
                    put("context", "fresh")
                    put("phase", "provisioning")
                })
            },
        )

        val projection = fixture.runtime.project("team-a")

        assertTrue(projection.members.isEmpty())
        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("VERSION"))
    }

    @Test
    fun taskDagRejectsCycleAndFreezesAtLastValidState() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-2", 1, blockedBy = listOf("task-1")),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-1", 2, blockedBy = listOf("task-2")),
        )

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("CYCLE"))
        val task1 = projection.tasks.single { it.id == "task-1" }
        assertEquals(1, task1.revision)
        assertTrue(task1.blockedBy.isEmpty())
    }

    private data class Fixture(
        val runtime: LocalAgentTeamRuntime,
        val log: LocalSessionEventLog,
    )

    private fun fixture(sessionId: String): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val jobs = LocalJobManager(scope = scope, onChanged = {})
        val log = LocalSessionEventLog(
            File(temporary.root, "$sessionId.jsonl"),
            Json,
        ).also(logs::add)
        val runtime = LocalAgentTeamRuntime(
            jobs = jobs,
            startTeammate = { _, _, _, _ ->
                JobStartResult(false, null, "测试不启动子代理")
            },
            sendToTeammate = { _, _, _ ->
                JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = false,
                    message = "测试不投递",
                )
            },
            eventLogFor = { log },
            projectionRegistry = SessionProjectionRegistry(),
        )
        return Fixture(runtime, log)
    }

    private fun appendActiveMember(log: LocalSessionEventLog, teamId: String) {
        log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member(teamId, "member-1", "worker", "provisioning"),
        )
        log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member(teamId, "member-1", "worker", "active"),
        )
    }

    private fun member(
        teamId: String,
        id: String,
        name: String,
        phase: String,
    ) = buildJsonObject {
        put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("member", buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", "")
            put("provider", "local-subagent")
            put("context", "fresh")
            put("phase", phase)
        })
    }

    private fun task(
        teamId: String,
        id: String,
        revision: Int,
        blockedBy: List<String>,
    ) = buildJsonObject {
        put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("task", buildJsonObject {
            put("id", id)
            put("revision", revision)
            put("subject", id)
            put("description", "")
            put("status", "pending")
            put("blockedBy", JsonArray(blockedBy.map(::JsonPrimitive)))
            put("writeScopes", JsonArray(emptyList()))
        })
    }
}
