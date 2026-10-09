package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamContract

import com.labteto.dshmobile.harness.jobs.JobMessageAdmission
import com.labteto.dshmobile.harness.jobs.JobStartResult
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    fun teamMemberChineseNameAndExtensionsSurviveEventRoundTrip() {
        val id = "member-display-test"
        val member = LocalTeamMemberSnapshot(
            id = id,
            jobId = LocalAgentTeamEventCodec.teamJobId(id),
            name = "web-verifier",
            description = "联网验证",
            provider = "local",
            context = LocalTeamMemberContext.FRESH,
            phase = LocalTeamMemberPhase.CREATED,
            displayName = "联网验证员",
            mutableToolsEnabled = true,
            grantedExtensions = setOf("github_search", "mcp_read"),
        )
        val event = with(LocalAgentTeamEventCodec) { member.toEvent("test-team") }
        val decoded = LocalAgentTeamEventCodec.decodeMember(event)
        assertEquals(member, decoded)
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
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(
                "777-agent-team",
                "member-worker",
                "worker",
                "provisioning",
                description = "research",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(
                "777-agent-team",
                "member-worker",
                "worker",
                "active",
                description = "research",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage("777-agent-team", "team-msg-1", "member-worker", "continue"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
            teamDelivered("777-agent-team", "team-msg-1", "member-worker"),
        )

        val projection = fixture.runtime.project("777-agent-team")
        val native = buildJsonObject {
            put("stateVersion", LocalAgentTeamContract.OFFICIAL_TEAM_PROJECTION_STATE_VERSION)
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
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
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
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
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
            LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
            teamDelivered("team-a", "team-msg-1", "member-1"),
        )

        assertTrue(fixture.runtime.project("team-a").pendingMessages.isEmpty())
    }

    @Test
    fun inheritedTeamIdDoesNotLeakIntoForkProjection() {
        val fixture = fixture("fork-b")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("ancestor-a", "member-old", "old", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("fork-b", "member-new", "new", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-a",
                "task-1",
                4,
                blockedBy = emptyList(),
                status = "pending",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-a", "member-1", "reviewer", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-a", "member-1", "reviewer", "failed"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
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
        fixture.log.append(LocalAgentTeamContract.TEAM_MESSAGE_QUEUED, queued)
        fixture.log.append(LocalAgentTeamContract.TEAM_MESSAGE_QUEUED, queued)

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
            LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
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
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
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
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-a", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-a", "task-2", 1, blockedBy = listOf("task-1")),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-a", "task-1", 2, blockedBy = listOf("task-2")),
        )

        val projection = fixture.runtime.project("team-a")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("CYCLE"))
        val task1 = projection.tasks.single { it.id == "task-1" }
        assertEquals(1, task1.revision)
        assertTrue(task1.blockedBy.isEmpty())
    }


    @Test
    fun unsupportedTeamEventVersionFailsProjectionWithoutApplyingPayload() {
        val fixture = fixture("team-a")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
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
    fun taskRevisionGapIsRejectedAndLastValidRevisionSurvives() {
        val fixture = fixture("team-revision")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-revision", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-revision", "task-1", 3, blockedBy = emptyList()),
        )

        val projection = fixture.runtime.project("team-revision")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("REVISION"))
        assertEquals(1, projection.tasks.single { it.id == "task-1" }.revision)
    }

    @Test
    fun rosterAndPendingMailboxRebuildFromSameEventLogAfterRuntimeRecreation() {
        val fixture = fixture("team-restart")
        appendActiveMember(fixture.log, "team-restart")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            buildJsonObject {
                put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
                put("teamId", "team-restart")
                put("message", buildJsonObject {
                    put("id", "team-msg-restart")
                    put("senderId", "team-restart")
                    put("senderName", "lead")
                    put("targetId", "member-1")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "重启后继续投递")
                                },
                            ),
                        ),
                    )
                })
            },
        )

        val recreated = LocalAgentTeamRuntime(
            jobs = LocalJobManager(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add),
                onChanged = {},
            ),
            startTeammate = { _, _, _, _, _, _, _, _, _ ->
                JobStartResult(false, null, "重建测试不启动子代理")
            },
            sendToTeammate = { _, _, _ ->
                JobMessageAdmission(
                    accepted = false,
                    duplicate = false,
                    requiresResume = false,
                    message = "重建测试不投递",
                )
            },
            eventLogFor = { fixture.log },
            projectionRegistry = SessionProjectionRegistry(),
        )

        val projection = recreated.project("team-restart")

        assertEquals(listOf("member-1"), projection.members.map { it.id })
        assertEquals(listOf("team-msg-restart"), projection.pendingMessages.map { it.id })
        assertNull(projection.failure)
        assertEquals(fixture.runtime.uiState("team-restart").activities, recreated.uiState("team-restart").activities)
    }

    @Test
    fun uiProjectionExposesAssistantsCurrentWorkProgressAndDependencies() {
        val fixture = fixture("team-ui")
        appendActiveMember(fixture.log, "team-ui")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-ui",
                "task-1",
                1,
                blockedBy = emptyList(),
                subject = "检查持久化恢复",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-ui",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
                subject = "检查持久化恢复",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-ui",
                "task-2",
                1,
                blockedBy = listOf("task-1"),
                subject = "回归测试",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(
                teamId = "team-ui",
                id = "team-msg-ui",
                targetId = "member-1",
                text = "补充检查边界",
            ),
        )

        val ui = fixture.runtime.uiState("team-ui")

        assertTrue(ui.visible)
        assertTrue(ui.activities.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        assertEquals(ui.activities, fixture.runtime.uiState("team-ui").activities)
        assertEquals("queued", ui.activities.last().status)
        assertEquals("in_progress", ui.activities.first { it.title == "检查持久化恢复" && it.status == "in_progress" }.status)
        assertEquals(1, ui.members.size)
        assertEquals("检查持久化恢复", ui.members.single().currentTask)
        assertEquals(1, ui.members.single().pendingMessageCount)
        assertEquals(2, ui.tasks.size)
        assertEquals(
            listOf("检查持久化恢复"),
            ui.tasks.single { it.id == "task-2" }.blockedByTitles,
        )
        assertTrue(!ui.tasks.single { it.id == "task-2" }.ready)
        assertEquals(1, ui.blockedTaskCount)
        assertEquals(1, ui.pendingMessageCount)
    }

    @Test
    fun projectionRejectsTwoConcurrentTasksOwnedBySameMember() {
        val fixture = fixture("team-member-busy")
        appendActiveMember(fixture.log, "team-member-busy")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-member-busy", "task-1", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-member-busy",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-member-busy", "task-2", 1, blockedBy = emptyList()),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-member-busy",
                "task-2",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
            ),
        )

        val projection = fixture.runtime.project("team-member-busy")

        assertNotNull(projection.failure)
        assertTrue(projection.failure!!.contains("TEAM_MEMBER_TASK_BUSY"))
        assertEquals(LocalTeamTaskStatus.IN_PROGRESS, projection.tasks.single { it.id == "task-1" }.status)
        assertEquals(LocalTeamTaskStatus.PENDING, projection.tasks.single { it.id == "task-2" }.status)
    }

    @Test
    fun memberLifecycleSupportsCreateDisableRestartAndDismiss() {
        val fixture = fixture("team-lifecycle")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-lifecycle", "member-1", "worker", "created"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-lifecycle", "member-1", "worker", "disabled"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-lifecycle", "member-1", "worker", "provisioning"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-lifecycle", "member-1", "worker", "active"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-lifecycle", "member-1", "worker", "dismissed"),
        )

        val projection = fixture.runtime.project("team-lifecycle")

        assertNull(projection.failure)
        assertEquals(LocalTeamMemberPhase.DISMISSED, projection.members.single().phase)
    }

    @Test
    fun terminalCheckpointBecomesFirstClassAgentMessageAndCompletedProgress() {
        val fixture = fixture("team-messages")
        appendActiveMember(fixture.log, "team-messages")
        fixture.jobs.startPersistent(
            label = "子代理：worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerSessionId = "team-messages",
            continuable = true,
            requestedId = "job-team-1",
        ) { _, _ -> "完成审查" }
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 4,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "完成审查",
            ),
        )

        val messages = fixture.runtime.agentMessages("team-messages", "worker", 20)
        val ui = fixture.runtime.uiState("team-messages")

        assertEquals(1, messages.size)
        assertEquals("完成审查", messages.single().content)
        assertEquals(100, ui.members.single().progressPercent)
        assertEquals(1, ui.members.single().resultMessageCount)
    }

    @Test
    fun completedMemberResultDoesNotCompleteTaskWithoutLeadVerification() {
        val fixture = fixture("team-auto-complete")
        appendActiveMember(fixture.log, "team-auto-complete")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-auto-complete", "task-1", 1, blockedBy = emptyList(), subject = "审查"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-auto-complete",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
                subject = "审查",
            ),
        )
        fixture.jobs.startPersistent(
            label = "子代理：worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerSessionId = "team-auto-complete",
            continuable = true,
            requestedId = "job-team-1",
        ) { _, _ -> "已完成" }
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 3,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 3,
                terminalOutput = "缺少文件，请补充",
            ),
        )

        fixture.runtime.recoverMailbox("team-auto-complete")

        val task = fixture.runtime.project("team-auto-complete").tasks.single()
        assertEquals(LocalTeamTaskStatus.IN_PROGRESS, task.status)
        assertEquals(2, task.revision)
        assertEquals("member-1", task.ownerId)
        val displayed = fixture.runtime.uiState("team-auto-complete")
        assertTrue(displayed.members.single().awaitingReview)
        assertEquals(95, displayed.members.single().progressPercent)
        assertEquals(1, displayed.returnedMemberCount)
        assertEquals(0, displayed.completedTaskCount)
    }

    @Test
    fun activeMemberWithMissingChildFailsAndReleasesOwnedTask() {
        val fixture = fixture("team-missing-child")
        appendActiveMember(fixture.log, "team-missing-child")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-missing-child", "task-1", 1, blockedBy = emptyList(), subject = "检查"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-missing-child",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
                subject = "检查",
            ),
        )

        fixture.runtime.recoverMailbox("team-missing-child")

        val projection = fixture.runtime.project("team-missing-child")
        assertEquals(LocalTeamMemberPhase.FAILED, projection.members.single().phase)
        assertEquals("TEAM_MEMBER_CHILD_MISSING", projection.members.single().error)
        assertEquals(LocalTeamTaskStatus.PENDING, projection.tasks.single().status)
        assertNull(projection.tasks.single().ownerId)
    }

    @Test
    fun failedMemberReleasesOwnedTaskAndMovesToFailedPhase() {
        val fixture = fixture("team-auto-release")
        appendActiveMember(fixture.log, "team-auto-release")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task("team-auto-release", "task-1", 1, blockedBy = emptyList(), subject = "检查"),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(
                "team-auto-release",
                "task-1",
                2,
                blockedBy = emptyList(),
                status = "in_progress",
                ownerId = "member-1",
                subject = "检查",
            ),
        )
        fixture.jobs.startPersistent(
            label = "子代理：worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerSessionId = "team-auto-release",
            continuable = true,
            requestedId = "job-team-1",
        ) { _, _ -> error("worker failed") }

        fixture.runtime.recoverMailbox("team-auto-release")

        val projection = fixture.runtime.project("team-auto-release")
        val task = projection.tasks.single()
        assertEquals(LocalTeamTaskStatus.PENDING, task.status)
        assertEquals(3, task.revision)
        assertNull(task.ownerId)
        assertEquals(LocalTeamMemberPhase.FAILED, projection.members.single().phase)
    }

    @Test
    fun agentMessagesUsesDurableSequenceAsCursor() {
        val fixture = fixture("team-message-cursor")
        appendActiveMember(fixture.log, "team-message-cursor")
        val first = fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "第一条结果",
            ),
        )
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 2,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "第二条结果",
            ),
        )

        val messages = fixture.runtime.agentMessages(
            sessionId = "team-message-cursor",
            targetName = "worker",
            limit = 20,
            afterSequence = first.sequence,
        )

        assertEquals(listOf("第二条结果"), messages.map { it.content })
        assertTrue(messages.single().sequence > first.sequence)
    }

    @Test
    fun agentMessageCursorReturnsEarliestUnreadResultWithoutSkippingConcurrentResults() {
        val fixture = fixture("team-message-order")
        appendActiveMember(fixture.log, "team-message-order")
        val baseline = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "结果一",
            ),
        )
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 2,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "结果二",
            ),
        )

        val first = fixture.runtime.agentMessages(
            sessionId = "team-message-order",
            targetName = "worker",
            limit = 1,
            afterSequence = baseline,
        ).single()
        val second = fixture.runtime.agentMessages(
            sessionId = "team-message-order",
            targetName = "worker",
            limit = 1,
            afterSequence = first.sequence,
        ).single()

        assertEquals("结果一", first.content)
        assertEquals("结果二", second.content)
        assertTrue(second.sequence > first.sequence)
    }

    @Test
    fun agentMessageCursorScansPastLargeUnrelatedEventBacklog() {
        val fixture = fixture("team-message-deep-scan")
        appendActiveMember(fixture.log, "team-message-deep-scan")
        val baseline = fixture.log.latestSequence()

        repeat(3_000) { index ->
            fixture.log.append(
                "test/noise",
                buildJsonObject {
                    put("index", index)
                },
            )
        }
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 7,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "深层结果仍可找到",
            ),
        )

        runBlocking { fixture.runtime.awaitProjection("team-message-deep-scan") }
        val messages = fixture.runtime.agentMessages(
            sessionId = "team-message-deep-scan",
            targetName = "worker",
            limit = 1,
            afterSequence = baseline,
        )

        assertEquals(1, messages.size)
        assertEquals("深层结果仍可找到", messages.single().content)
    }

    @Test
    fun coldProjectionCompletionResumesMailboxRecoveryWithoutAnotherUserAction() {
        val session = "team-cold-mailbox"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val recovered = CountDownLatch(1)
        lateinit var fixture: Fixture
        val refreshQueue = LocalTeamRefreshQueue(scope) { id, reconcile ->
            if (reconcile) fixture.runtime.recoverMailbox(id)
            recovered.countDown()
        }
        fixture = fixture(session, projectionScope = scope,
            onProjectionReady = { refreshQueue.schedule(it, reconcile = true) })
        appendActiveMember(fixture.log, session)
        startLiveChild(fixture, session)
        repeat(2_000) { fixture.log.append("test/noise", buildJsonObject { put("i", it) }) }
        fixture.log.append(LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(session, "legacy-cold", "member-1", "文".repeat(4_001)))
        assertTrue(fixture.runtime.uiState(session).rebuilding)
        assertTrue("冷投影完成后必须主动恢复邮箱", recovered.await(5, TimeUnit.SECONDS))
        val state = fixture.runtime.project(session)
        assertTrue(state.pendingMessages.isEmpty())
        assertTrue("legacy-cold" in state.discardedMessageIds)
    }

    @Test
    fun oversizedHistoricalMessageIsExplicitlyDiscardedWithRetryGuidance() {
        val session = "team-legacy-oversized"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        startLiveChild(fixture, session)
        fixture.log.append(LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(session, "legacy-long", "member-1", "文".repeat(4_001)))
        fixture.runtime.recoverMailbox(session)
        val state = fixture.runtime.project(session)
        assertTrue(state.pendingMessages.isEmpty())
        assertTrue("legacy-long" in state.discardedMessageIds)
        assertTrue(state.activities.any { it.status == "failed" && it.title.contains("请拆分") })
        assertTrue(state.deliveredMessageIds.isEmpty())
    }

    @Test
    fun discardedMailboxMessageLeavesPendingSetAndCannotLaterDeliver() {
        val fixture = fixture("team-message-discard")
        appendActiveMember(fixture.log, "team-message-discard")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(
                teamId = "team-message-discard",
                id = "team-msg-discard",
                targetId = "member-1",
                text = "无需继续处理",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED,
            teamDiscarded(
                teamId = "team-message-discard",
                messageId = "team-msg-discard",
                targetId = "member-1",
            ),
        )

        val settled = fixture.runtime.project("team-message-discard")
        assertTrue(settled.pendingMessages.isEmpty())
        assertEquals(listOf("team-msg-discard"), settled.discardedMessageIds)

        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED,
            teamDelivered("team-message-discard", "team-msg-discard", "member-1"),
        )

        val invalid = fixture.runtime.project("team-message-discard")
        assertNotNull(invalid.failure)
        assertTrue(invalid.failure!!.contains("AFTER_DISCARD"))
    }

    @Test
    fun agentMessagesSupportsDurableSequenceCursorWithoutMissingExistingResult() {
        val fixture = fixture("team-message-cursor")
        appendActiveMember(fixture.log, "team-message-cursor")
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第一条结果",
            ),
        )
        val firstSequence = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 2,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第二条结果",
            ),
        )

        val messages = fixture.runtime.agentMessages(
            sessionId = "team-message-cursor",
            targetName = "worker",
            limit = 20,
            afterSequence = firstSequence,
        )

        assertEquals(1, messages.size)
        assertEquals("第二条结果", messages.single().content)
        assertTrue(messages.single().sequence > firstSequence)
    }

    @Test
    fun agentMessagesCursorReturnsEarliestBatchWithoutSkippingBacklog() {
        val fixture = fixture("team-message-backlog")
        appendActiveMember(fixture.log, "team-message-backlog")
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第一条结果",
            ),
        )
        val firstSequence = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 2,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第二条结果",
            ),
        )
        val secondSequence = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 3,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第三条结果",
            ),
        )

        val messages = fixture.runtime.agentMessages(
            sessionId = "team-message-backlog",
            targetName = "worker",
            limit = 1,
            afterSequence = firstSequence,
        )

        assertEquals(1, messages.size)
        assertEquals(secondSequence, messages.single().sequence)
        assertEquals("第二条结果", messages.single().content)
    }

    @Test
    fun discardedMailboxMessageIsDurablySettledAndCannotRemainPending() {
        val fixture = fixture("team-discard-message")
        appendActiveMember(fixture.log, "team-discard-message")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(
                teamId = "team-discard-message",
                id = "team-msg-discard",
                targetId = "member-1",
                text = "成员解雇前未投递",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED,
            teamDiscarded(
                teamId = "team-discard-message",
                messageId = "team-msg-discard",
                targetId = "member-1",
            ),
        )

        val projection = fixture.runtime.project("team-discard-message")

        assertNull(projection.failure)
        assertTrue(projection.pendingMessages.isEmpty())
        assertEquals(listOf("team-msg-discard"), projection.discardedMessageIds)
        assertTrue(projection.deliveredMessageIds.isEmpty())
    }

    @Test
    fun waitForMessageReturnsEarliestResultAfterCursorWithoutSkippingLaterResult() = runBlocking {
        val sessionId = "team-wait-cursor"
        val fixture = fixture(sessionId)
        appendActiveMember(fixture.log, sessionId)
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第一条结果",
            ),
        )
        val firstSequence = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 2,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第二条结果",
            ),
        )
        val secondSequence = fixture.log.latestSequence()
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 3,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                softStepLimit = 4,
                terminalOutput = "第三条结果",
            ),
        )
        val binding = LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = LocalHarnessState(
                sessionId = sessionId,
                usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = sessionId,
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = sessionId,
                maxPendingInputs = 8,
            ),
            eventLog = fixture.log,
        )

        val result = requireNotNull(
            fixture.runtime.execute(
                LocalToolCall(
                    id = "call-wait",
                    name = "team_wait_for_message",
                    arguments = buildJsonObject {
                        put("target", "worker")
                        put("after_sequence", firstSequence)
                        put("timeout_ms", 1_000)
                    },
                    rawArguments = "{}",
                ),
                binding,
            ),
        )

        assertTrue(result.contains("第二条结果"))
        assertTrue(!result.contains("第三条结果"))
        assertTrue(result.contains("next_cursor=" + secondSequence))
    }

    @Test
    fun waitWithoutCursorIgnoresHistoricalResultAndReturnsNextNewResult() = runBlocking {
        val sessionId = "team-wait-future"
        val fixture = fixture(sessionId)
        appendActiveMember(fixture.log, sessionId)
        fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint(
                backgroundJobId = "job-team-1",
                agentId = localPersistentSubagentId("job-team-1"),
                step = 1,
                history = emptyList(),
                claimedMessageIds = emptySet(),
                terminalOutput = "历史结果",
            ),
        )
        val binding = LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = LocalHarnessState(
                sessionId = sessionId,
                usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = sessionId,
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = sessionId,
                maxPendingInputs = 8,
            ),
            eventLog = fixture.log,
        )
        val producer = launch {
            delay(50)
            fixture.log.append(
                LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
                encodeLocalSubagentHistoryCheckpoint(
                    backgroundJobId = "job-team-1",
                    agentId = localPersistentSubagentId("job-team-1"),
                    step = 2,
                    history = emptyList(),
                    claimedMessageIds = emptySet(),
                    terminalOutput = "新结果",
                ),
            )
        }

        val result = requireNotNull(
            fixture.runtime.execute(
                LocalToolCall(
                    id = "call-wait-future",
                    name = "team_wait_for_message",
                    arguments = buildJsonObject {
                        put("target", "worker")
                        put("timeout_ms", 1_000)
                    },
                    rawArguments = "{}",
                ),
                binding,
            ),
        )
        producer.join()

        assertTrue(result.contains("新结果"))
        assertTrue(!result.contains("历史结果"))
    }

    @Test
    fun dismissedMemberFreesCurrentRosterSlotForReplacement() = runBlocking {
        val sessionId = "team-roster-capacity"
        val fixture = fixture(sessionId)
        val binding = LocalWorkRunBinding(
            sessionId = sessionId,
            initialState = LocalHarnessState(
                sessionId = sessionId,
                usageMode = LocalUsageMode.WORK,
            ).toLocalWorkRunState(),
            sessionBase = LocalHarnessSession(
                id = sessionId,
                usageMode = LocalUsageMode.WORK,
            ),
            runHandle = LocalAgentRunHandle(
                initialSessionId = sessionId,
                maxPendingInputs = 8,
            ),
            eventLog = fixture.log,
        )

        repeat(16) { index ->
            requireNotNull(
                fixture.runtime.execute(
                    LocalToolCall(
                        id = "call-create-" + index,
                        name = "team_create_member",
                        arguments = buildJsonObject {
                            put("name", "worker-" + index)
                        },
                        rawArguments = "{}",
                    ),
                    binding,
                ),
            )
        }

        val overflow = runCatching {
            fixture.runtime.execute(
                LocalToolCall(
                    id = "call-overflow",
                    name = "team_create_member",
                    arguments = buildJsonObject {
                        put("name", "worker-overflow")
                    },
                    rawArguments = "{}",
                ),
                binding,
            )
        }.exceptionOrNull()
        assertNotNull(overflow)
        assertTrue(overflow!!.message.orEmpty().contains("TEAM_MEMBER_LIMIT"))

        requireNotNull(
            fixture.runtime.execute(
                LocalToolCall(
                    id = "call-dismiss",
                    name = "team_dismiss_member",
                    arguments = buildJsonObject {
                        put("target", "worker-0")
                    },
                    rawArguments = "{}",
                ),
                binding,
            ),
        )
        val replacement = requireNotNull(
            fixture.runtime.execute(
                LocalToolCall(
                    id = "call-replacement",
                    name = "team_create_member",
                    arguments = buildJsonObject {
                        put("name", "worker-replacement")
                    },
                    rawArguments = "{}",
                ),
                binding,
            ),
        )

        assertTrue(replacement.contains("已创建"))
        assertEquals(
            16,
            fixture.runtime.project(sessionId).members.count {
                it.phase != LocalTeamMemberPhase.DISMISSED
            },
        )
    }

    @Test
    fun failedMemberPendingMailboxIsDiscardedDuringRecovery() {
        val fixture = fixture("team-failed-mailbox")
        appendActiveMember(fixture.log, "team-failed-mailbox")
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MESSAGE_QUEUED,
            teamMessage(
                teamId = "team-failed-mailbox",
                id = "team-msg-failed",
                targetId = "member-1",
                text = "稍后继续",
            ),
        )
        fixture.log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member("team-failed-mailbox", "member-1", "worker", "failed"),
        )

        fixture.runtime.recoverMailbox("team-failed-mailbox")

        val projection = fixture.runtime.project("team-failed-mailbox")
        assertTrue(projection.pendingMessages.isEmpty())
        assertEquals(listOf("team-msg-failed"), projection.discardedMessageIds)
    }

    @Test
    fun liveSendAndRecoveryCommitDeliveryOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val session = "team-send-race"
        val fixture = fixture(session) { _, _, _ ->
            attempts.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            JobMessageAdmission(true, false, false, "accepted")
        }
        appendActiveMember(fixture.log, session)
        startLiveChild(fixture, session)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val send = executor.submit<String> { fixture.runtime.sendUiMessage(session, "member-1", "继续检查") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val recovery = executor.submit { fixture.runtime.recoverMailbox(session) }
            release.countDown()
            send.get(5, TimeUnit.SECONDS)
            recovery.get(5, TimeUnit.SECONDS)
            val projection = fixture.runtime.project(session)
            assertNull(projection.failure)
            assertEquals(1, attempts.get())
            assertEquals(1, projection.deliveredMessageIds.size)
            assertTrue(projection.pendingMessages.isEmpty())
            assertEquals(1, fixture.log.snapshot().count { it.type == LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun duplicateCheckpointDoesNotRepeatResultAcrossCursorAndSameTextNewActivationRemainsVisible() {
        val session = "team-result-identity"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        fun checkpoint(id: String, first: Boolean) = fixture.log.append(
            LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint("job-team-1", "sa-team-1", 1, emptyList(), emptySet(),
                terminalOutput = "检查完成", resultId = id, resultFirst = first),
        )
        val first = checkpoint("result-1", true)
        checkpoint("result-1", false)
        checkpoint("result-2", true)
        assertEquals(listOf("result-1", "result-2"), fixture.runtime.agentMessages(session, "worker", 20).map { it.id })
        assertEquals(listOf("result-2"), fixture.runtime.agentMessages(session, "worker", 20, first.sequence).map { it.id })
    }

    @Test
    fun scanBudgetReturnsContinuationCursorInsteadOfSkippingUnscannedResult() {
        val session = "team-scan-budget"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        val baseline = fixture.log.latestSequence()
        repeat(5_200) { fixture.log.append("test/noise", buildJsonObject { put("i", it) }) }
        fixture.log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint("job-team-1", "sa-team-1", 1, emptyList(), emptySet(),
                terminalOutput = "末尾结果", resultId = "result-end"))
        runBlocking { fixture.runtime.awaitProjection(session) }
        val first = fixture.runtime.scanAgentMessages(session, "worker", 1, baseline)
        assertTrue(first.messages.isEmpty())
        assertTrue(first.hasMore)
        assertTrue(first.nextCursor < fixture.log.latestSequence())
        val second = fixture.runtime.scanAgentMessages(session, "worker", 1, first.nextCursor)
        assertEquals("result-end", second.messages.single().id)
    }

    private fun teamBinding(fixture: Fixture, session: String) = LocalWorkRunBinding(
        sessionId = session,
        initialState = LocalHarnessState(sessionId = session, usageMode = LocalUsageMode.WORK).toLocalWorkRunState(),
        sessionBase = LocalHarnessSession(id = session, usageMode = LocalUsageMode.WORK),
        runHandle = LocalAgentRunHandle(initialSessionId = session, maxPendingInputs = 8),
        eventLog = fixture.log,
    )

    @Test
    fun overlappingWriteScopesCannotBeClaimedByConcurrentTasks() = runBlocking {
        val session = "team-scope-lock"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        fixture.log.append(LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(session, "member-2", "writer2", "provisioning"))
        fixture.log.append(LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(session, "member-2", "writer2", "active"))
        startLiveChild(fixture, session)
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(session, "task-1", 1, emptyList(), writeScopes = listOf("app/src")))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(session, "task-1", 2, emptyList(), status = "in_progress",
                ownerId = "member-1", writeScopes = listOf("app/src")))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(session, "task-2", 1, emptyList(), writeScopes = listOf("app/src/main")))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(session, "task-3", 1, emptyList(), writeScopes = listOf("docs")))
        val binding = teamBinding(fixture, session)
        suspend fun claim(id: String) = fixture.runtime.execute(
            LocalToolCall("claim-$id", "team_task_update", buildJsonObject {
                put("task_id", id)
                put("expected_revision", 1)
                put("action", "claim")
                put("owner", "writer2")
            }, "{}"), binding,
        )
        val conflict = runCatching { claim("task-2") }.exceptionOrNull()
        assertNotNull(conflict)
        assertTrue(conflict!!.message.orEmpty().contains("TEAM_WRITE_SCOPE_CONFLICT"))
        claim("task-3")
        val tasks = fixture.runtime.project(session).tasks.associateBy { it.id }
        assertEquals(LocalTeamTaskStatus.PENDING, tasks["task-2"]?.status)
        assertEquals(1, tasks["task-2"]?.revision)
        assertEquals(LocalTeamTaskStatus.IN_PROGRESS, tasks["task-3"]?.status)
        assertNull(fixture.runtime.project(session).failure)
    }

    @Test
    fun competingClaimsHaveOneWinnerAndPreserveHealthyProjection() {
        val session = "team-claim-race"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        startLiveChild(fixture, session)
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT,
            task(session, "task-1", 1, emptyList(), subject = "审查"))
        val binding = teamBinding(fixture, session)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims = (1..2).map { index -> executor.submit<Boolean> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                runCatching { runBlocking {
                    fixture.runtime.execute(LocalToolCall("claim-$index", "team_task_update", buildJsonObject {
                        put("task_id", "task-1"); put("expected_revision", 1); put("action", "claim"); put("owner", "worker")
                    }, "{}"), binding)
                } }.isSuccess
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
            val state = fixture.runtime.project(session)
            assertNull(state.failure)
            assertEquals(2, state.tasks.single().revision)
            assertEquals(LocalTeamTaskStatus.IN_PROGRESS, state.tasks.single().status)
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test
    fun completionRequiresFreshOwnedResultAndUnlocksDependencyOnlyAfterVerification() = runBlocking {
        val session = "team-verified-complete"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        startLiveChild(fixture, session)
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT, task(session, "task-1", 1, emptyList()))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT, task(session, "task-1", 2, emptyList(),
            status = "in_progress", ownerId = "member-1"))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT, task(session, "task-2", 1, listOf("task-1")))
        val binding = teamBinding(fixture, session)
        suspend fun complete(result: String?) = fixture.runtime.execute(LocalToolCall("complete", "team_task_update", buildJsonObject {
            put("task_id", "task-1"); put("expected_revision", 2); put("action", "complete")
            result?.let { put("result_id", it) }
        }, "{}"), binding)
        assertTrue(runCatching { complete(null) }.isFailure)
        assertTrue(runCatching { complete("absent") }.isFailure)
        assertEquals(LocalTeamTaskStatus.IN_PROGRESS, fixture.runtime.project(session).tasks.first().status)
        fixture.log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint("job-team-1", "sa-team-1", 1, emptyList(), emptySet(),
                terminalOutput = "可核验结果", resultId = "verified"))
        complete("verified")
        val state = fixture.runtime.project(session)
        assertNull(state.failure)
        assertEquals(LocalTeamTaskStatus.COMPLETED, state.tasks.first { it.id == "task-1" }.status)
        assertEquals(3, state.tasks.first { it.id == "task-1" }.revision)
    }

    @Test
    fun uiPendingMessagesIncludesDurableChildInbox() {
        val fixture = fixture("team-durable-inbox")
        appendActiveMember(fixture.log, "team-durable-inbox")
        fixture.jobs.startPersistent(
            label = "子代理：worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerSessionId = "team-durable-inbox",
            continuable = true,
            requestedId = "job-team-1",
        ) { _, _ -> awaitCancellation() }
        fixture.jobs.send(
            id = "job-team-1",
            message = "待处理追加消息",
            ownerSessionId = "team-durable-inbox",
        )

        val ui = fixture.runtime.uiState("team-durable-inbox")

        assertEquals(1, ui.members.single().pendingMessageCount)
        assertEquals(1, ui.pendingMessageCount)
    }

    @Test
    fun activityHistoryIsBoundedAndRepeatedProjectionDoesNotDuplicateEvents() {
        val fixture = fixture("team-activity-limit")
        repeat(70) { index ->
            fixture.log.append(
                LocalAgentTeamContract.TEAM_TASK_EVENT,
                task("team-activity-limit", "task-${index + 1}", 1, blockedBy = emptyList()),
            )
        }
        val ui = fixture.runtime.uiState("team-activity-limit")
        assertEquals(70, ui.tasks.size)
        assertEquals(64, ui.activities.size)
        assertEquals(64, ui.activities.map { it.sequence }.distinct().size)
        assertEquals(ui.activities, fixture.runtime.uiState("team-activity-limit").activities)
        assertNull(ui.failure)
    }

    @Test
    fun historicalResultAndItsLaterRecoveryCheckpointCannotVerifyNewTask() = runBlocking {
        val session = "team-stale-result"
        val fixture = fixture(session)
        appendActiveMember(fixture.log, session)
        fixture.jobs.startPersistent(
            label = "子代理：worker", resumeKind = "subagent_readonly", resumePayload = "{}",
            ownerSessionId = session, continuable = true, requestedId = "job-team-1",
        ) { _, _ -> "旧任务结果" }
        fun legacyCheckpoint() = fixture.log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint("job-team-1", "sa-team-1", 1, emptyList(), emptySet(),
                terminalOutput = "旧任务结果"))
        legacyCheckpoint()
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT, task(session, "task-1", 1, emptyList()))
        fixture.log.append(LocalAgentTeamContract.TEAM_TASK_EVENT, task(session, "task-1", 2, emptyList(),
            status = "in_progress", ownerId = "member-1"))
        legacyCheckpoint()
        assertEquals(1, fixture.runtime.uiState(session).members.single().resultMessageCount)
        assertTrue(!fixture.runtime.uiState(session).members.single().awaitingReview)
        val failure = runCatching {
            fixture.runtime.execute(LocalToolCall("complete-stale", "team_task_update", buildJsonObject {
                put("task_id", "task-1"); put("expected_revision", 2); put("action", "complete")
                put("result_id", "legacy-result-job-team-1-1")
            }, "{}"), teamBinding(fixture, session))
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("TEAM_TASK_RESULT_STALE"))
        assertEquals(2, fixture.runtime.project(session).tasks.single().revision)
        fixture.log.append(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT,
            encodeLocalSubagentHistoryCheckpoint("job-team-1", "sa-team-1", 2, emptyList(), emptySet(),
                terminalOutput = "新任务结果", resultId = "fresh-result"))
        assertTrue(fixture.runtime.uiState(session).members.single().awaitingReview)
    }

    @Test fun tooLongTeamMessageIsRejectedBeforeQueuedAndDeliveredFacts() {
        val fixture = fixture("team-oversize", sender = { _, _, _ -> error("must reject before delivery") })
        appendActiveMember(fixture.log, "team-oversize")
        startLiveChild(fixture, "team-oversize")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            fixture.runtime.sendUiMessage("team-oversize", "member-1", "x".repeat(4_000))
        }
        assertEquals(0, fixture.runtime.project("team-oversize").pendingMessages.size)
        assertEquals(0, fixture.runtime.project("team-oversize").deliveredMessageIds.size)
    }

    private data class Fixture(
        val runtime: LocalAgentTeamRuntime,
        val log: LocalSessionEventLog,
        val jobs: LocalJobManager,
    )

    private fun fixture(
        sessionId: String,
        projectionScope: CoroutineScope? = null,
        onProjectionReady: (String) -> Unit = {},
        sender: (String, QueuedAgentInput, String) -> JobMessageAdmission = { _, _, _ ->
            JobMessageAdmission(false, false, false, "测试不投递")
        },
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        val jobs = LocalJobManager(scope = scope, onChanged = {})
        val log = LocalSessionEventLog(
            File(temporary.root, "$sessionId.jsonl"),
            Json,
        ).also(logs::add)
        val runtime = LocalAgentTeamRuntime(
            jobs = jobs,
            startTeammate = { _, _, _, _, _, _, _, _, _ ->
                JobStartResult(false, null, "测试不启动子代理")
            },
            sendToTeammate = sender,
            eventLogFor = { log },
            projectionRegistry = SessionProjectionRegistry(),
            projectionScope = projectionScope,
            onProjectionReady = onProjectionReady,
        )
        return Fixture(runtime, log, jobs)
    }

    private fun startLiveChild(fixture: Fixture, session: String) {
        fixture.jobs.startPersistent(
            label = "子代理：worker",
            resumeKind = "subagent_readonly",
            resumePayload = "{}",
            ownerSessionId = session,
            continuable = true,
            requestedId = "job-team-1",
        ) { _, _ -> awaitCancellation() }
    }

    private fun appendActiveMember(log: LocalSessionEventLog, teamId: String) {
        log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(teamId, "member-1", "worker", "provisioning"),
        )
        log.append(
            LocalAgentTeamContract.TEAM_MEMBER_EVENT,
            member(teamId, "member-1", "worker", "active"),
        )
    }

    private fun member(
        teamId: String,
        id: String,
        name: String,
        phase: String,
        description: String = "",
    ) = buildJsonObject {
        put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("member", buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
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
        put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
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
        put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("messageId", messageId)
        put("targetId", targetId)
    }

    private fun teamDiscarded(
        teamId: String,
        messageId: String,
        targetId: String,
    ) = buildJsonObject {
        put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("messageId", messageId)
        put("targetId", targetId)
        put("reason", "test")
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
        put("version", LocalAgentTeamContract.TEAM_EVENT_VERSION)
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
