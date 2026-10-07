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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
    fun productionProjectionMatchesOfficialAgentTeamGolden() {
        val official = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("official-semantic/advanced.json"),
        ).bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject["agentTeam"]!!.jsonObject
        }
        val fixture = fixture("777-agent-team")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("777-agent-team", "member-worker", "worker", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("777-agent-team", "member-worker", "worker", "active"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                teamId = "777-agent-team",
                id = "task-1",
                revision = 1,
                blockedBy = emptyList(),
                subject = "first",
                writeScopes = listOf("src"),
            ),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                teamId = "777-agent-team",
                id = "task-2",
                revision = 1,
                blockedBy = listOf("task-1"),
                subject = "second",
                writeScopes = listOf("src/feature"),
            ),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_QUEUED,
            teamMessage("777-agent-team", "team-msg-1", "member-worker", "continue"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_DELIVERED,
            teamDelivered("777-agent-team", "team-msg-1", "member-worker"),
        )

        val projection = fixture.runtime.project("777-agent-team")
        val native = buildJsonObject {
            put("stateVersion", LocalAgentTeamRuntime.OFFICIAL_TEAM_PROJECTION_STATE_VERSION)
            put("asOfSequence", projection.asOfSequence)
            put(
                "members",
                JsonArray(
                    projection.members.map { member ->
                        buildJsonObject {
                            put("id", member.id)
                            put("name", member.name)
                            put("description", member.description)
                            put("provider", member.provider)
                            put("context", member.context.name.lowercase())
                            put("phase", member.phase.name.lowercase())
                            member.error?.let { put("error", it) }
                        }
                    },
                ),
            )
            put(
                "tasks",
                JsonArray(
                    projection.tasks
                        .filter { it.status != LocalTeamTaskStatus.DELETED }
                        .map { task ->
                            buildJsonObject {
                                put("id", task.id)
                                put("revision", task.revision)
                                put("subject", task.subject)
                                put("description", task.description)
                                put("status", task.status.name.lowercase())
                                task.ownerId?.let { put("ownerId", it) }
                                put("blockedBy", JsonArray(task.blockedBy.map(::JsonPrimitive)))
                                put("writeScopes", JsonArray(task.writeScopes.map(::JsonPrimitive)))
                            }
                        },
                ),
            )
            put(
                "pendingMessageIds",
                JsonArray(projection.pendingMessages.map { JsonPrimitive(it.id) }),
            )
            put("failure", projection.failure?.let(::JsonPrimitive) ?: JsonNull)
        }

        assertEquals(official, native)
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
            teamMessage(
                teamId = "team-a",
                id = "team-msg-1",
                targetId = "member-1",
                text = "检查结果",
            ),
        )

        assertEquals(
            listOf("team-msg-1"),
            fixture.runtime.project("team-a").pendingMessages.map { it.id },
        )

        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_DELIVERED,
            teamDelivered("team-a", "team-msg-1", "member-1"),
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
    fun completedTaskCanReopenAndBeReassignedToActiveMember() {
        val fixture = fixture("team-a")
        appendActiveMember(fixture.log, "team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                "team-a",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
            ),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                "team-a",
                "task-1",
                3,
                blockedBy = emptyList(),
                status = "completed",
                ownerId = "member-1",
            ),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                "team-a",
                "task-1",
                4,
                blockedBy = emptyList(),
                status = "pending",
            ),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task(
                "team-a",
                "task-1",
                5,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
            ),
        )

        val projection = fixture.runtime.project("team-a")

        assertNull(projection.failure)
        val task = projection.tasks.single()
        assertEquals(5, task.revision)
        assertEquals(LocalTeamTaskStatus.IN_PROGRESS, task.status)
        assertEquals("member-1", task.ownerId)
    }

    @Test
    fun failedTeammateNameCannotBeReused() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("team-a", "member-1", "reviewer", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("team-a", "member-1", "reviewer", "failed"),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("team-a", "member-2", "reviewer", "provisioning"),
        )

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("NAME_CONFLICT"))
        assertEquals(listOf("member-1"), projection.members.map { it.id })
    }

    @Test
    fun persistedTaskWithDuplicateBlockerFailsClosed() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_TASK_EVENT,
            task("team-a", "task-2", 1, blockedBy = listOf("task-1", "task-1")),
        )

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("BLOCKER_DUPLICATE"))
        assertEquals(listOf("task-1"), projection.tasks.map { it.id })
    }

    @Test
    fun duplicateQueuedMessageFailsClosed() {
        val fixture = fixture("team-a")
        appendActiveMember(fixture.log, "team-a")
        val queued = teamMessage(
            teamId = "team-a",
            id = "team-msg-1",
            targetId = "member-1",
            text = "检查结果",
        )
        fixture.log.append(LocalAgentTeamRuntime.TEAM_MESSAGE_QUEUED, queued)
        fixture.log.append(LocalAgentTeamRuntime.TEAM_MESSAGE_QUEUED, queued)

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("QUEUED_TWICE"))
        assertEquals(listOf("team-msg-1"), projection.pendingMessages.map { it.id })
    }

    @Test
    fun deliveryBeforeQueueFailsClosed() {
        val fixture = fixture("team-a")
        appendActiveMember(fixture.log, "team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MESSAGE_DELIVERED,
            teamDelivered("team-a", "team-msg-missing", "member-1"),
        )

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("BEFORE_QUEUE"))
        assertTrue(projection.pendingMessages.isEmpty())
    }

    @Test
    fun provisioningWithoutPersistedChildRecoversAsFailed() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamRuntime.TEAM_MEMBER_EVENT,
            member("team-a", "member-missing", "reviewer", "provisioning"),
        )

        fixture.runtime.recoverMailbox("team-a")

        val projection = fixture.runtime.project("team-a")
        assertNull(projection.failure)
        val member = projection.members.single()
        assertEquals(LocalTeamMemberPhase.FAILED, member.phase)
        assertTrue(member.error.orEmpty().contains("CHILD_MISSING"))
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
            startTeammate = { _, _, _, _, _ ->
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

    private fun teamMessage(
        teamId: String,
        id: String,
        targetId: String,
        text: String,
    ) = buildJsonObject {
        put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("message", buildJsonObject {
            put("id", id)
            put("senderId", teamId)
            put("senderName", "lead")
            put("targetId", targetId)
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    ),
                ),
            )
        })
    }

    private fun teamDelivered(
        teamId: String,
        messageId: String,
        targetId: String,
    ) = buildJsonObject {
        put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("messageId", messageId)
        put("targetId", targetId)
    }

    private fun task(
        teamId: String,
        id: String,
        revision: Int,
        blockedBy: List<String>,
        status: String = "pending",
        ownerId: String? = null,
        subject: String = id,
        writeScopes: List<String> = emptyList(),
    ) = buildJsonObject {
        put("version", LocalAgentTeamRuntime.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("task", buildJsonObject {
            put("id", id)
            put("revision", revision)
            put("subject", subject)
            put("description", "")
            put("status", status)
            ownerId?.let { put("ownerId", it) }
            put("blockedBy", JsonArray(blockedBy.map(::JsonPrimitive)))
            put("writeScopes", JsonArray(writeScopes.map(::JsonPrimitive)))
        })
    }
}
