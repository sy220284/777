package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class LocalTeamMemberPhase { PROVISIONING, ACTIVE, FAILED }
internal enum class LocalTeamMemberContext { FRESH, FORK }
internal enum class LocalTeamTaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

internal data class LocalTeamMemberSnapshot(
    val id: String,
    val jobId: String,
    val name: String,
    val description: String,
    val provider: String,
    val context: LocalTeamMemberContext,
    val phase: LocalTeamMemberPhase,
    val error: String? = null,
)

internal data class LocalTeamTaskSnapshot(
    val id: String,
    val revision: Int,
    val subject: String,
    val description: String,
    val status: LocalTeamTaskStatus,
    val ownerId: String? = null,
    val blockedBy: List<String> = emptyList(),
    val writeScopes: List<String> = emptyList(),
)

internal data class LocalTeamMessageSnapshot(
    val id: String,
    val senderId: String,
    val senderName: String,
    val targetId: String,
    val content: String,
)

internal data class LocalTeamProjection(
    val members: List<LocalTeamMemberSnapshot> = emptyList(),
    val tasks: List<LocalTeamTaskSnapshot> = emptyList(),
    val pendingMessages: List<LocalTeamMessageSnapshot> = emptyList(),
    val deliveredMessageIds: List<String> = emptyList(),
    val failure: String? = null,
    val asOfSequence: Long = -1L,
)

/** Work-owned Agent Team domain. Durable state lives only in the lead Session EventLog. */
internal class LocalAgentTeamRuntime(
    private val jobs: LocalJobManager,
    private val startTeammate: suspend (
        binding: LocalWorkRunBinding,
        requestedJobId: String,
        task: String,
        model: String?,
        maxSteps: Int,
        context: LocalTeamMemberContext,
        parentCallId: String?,
    ) -> com.labteto.dshmobile.harness.jobs.JobStartResult,
    private val sendToTeammate: (
        agentId: String,
        input: QueuedAgentInput,
        sessionId: String,
    ) -> com.labteto.dshmobile.harness.jobs.JobMessageAdmission,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    projectionRegistry: SessionProjectionRegistry,
) {
    private val teamProjection = projectionRegistry.register(
        name = "work.agent-team",
        stateVersion = OFFICIAL_TEAM_PROJECTION_STATE_VERSION,
        initial = { LocalTeamProjection() },
        reducer = SessionReducer<LocalTeamProjection> { state, event ->
            applyEvent(state, event)
        },
    )

    suspend fun execute(
        call: LocalToolCall,
        binding: LocalWorkRunBinding,
    ): String? {
        if (call.name !in TOOL_NAMES) return null
        recoverMailbox(binding.sessionId)
        val args = call.arguments
        return when (call.name) {
            "team_members" -> renderMembers(binding.sessionId)
            "team_spawn" -> spawn(
                binding = binding,
                name = args.requiredTeamString("name"),
                description = args.optionalTeamString("description").orEmpty(),
                task = args.requiredTeamString("task"),
                model = args.optionalTeamString("model"),
                maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: binding.aggregateSnapshot().subagentMaxSteps,
                context = args.optionalTeamContext(),
                parentCallId = call.id,
            )
            "team_send_message" -> sendMessage(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
                message = args.requiredTeamString("message"),
            )
            "team_task_create" -> createTask(
                sessionId = binding.sessionId,
                subject = args.requiredTeamString("subject"),
                description = args.optionalTeamString("description").orEmpty(),
                blockedBy = args.teamStringArray("blocked_by"),
                writeScopes = args.teamStringArray("write_scopes"),
            )
            "team_task_get" -> renderTask(
                sessionId = binding.sessionId,
                id = args.requiredTeamString("task_id"),
                includeDeleted = true,
            )
            "team_task_list" -> renderTasks(binding.sessionId)
            "team_task_update" -> updateTask(
                sessionId = binding.sessionId,
                id = args.requiredTeamString("task_id"),
                expectedRevision = args["expected_revision"]?.jsonPrimitive?.intOrNull
                    ?: error("TEAM_TASK_REVISION_REQUIRED：expected_revision 必填"),
                action = args.requiredTeamString("action"),
                ownerName = args.optionalTeamString("owner"),
                subject = args.optionalTeamString("subject"),
                description = args.optionalTeamString("description"),
                blockedBy = args.teamStringArrayOrNull("blocked_by"),
                writeScopes = args.teamStringArrayOrNull("write_scopes"),
            )
            "team_interrupt" -> interrupt(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_wait" -> waitForChange(
                sessionId = binding.sessionId,
                timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
            )
            else -> error("未覆盖的 Agent Team 工具：${call.name}")
        }
    }

    private suspend fun spawn(
        binding: LocalWorkRunBinding,
        name: String,
        description: String,
        task: String,
        model: String?,
        maxSteps: Int,
        context: LocalTeamMemberContext,
        parentCallId: String,
    ): String {
        val cleanName = normalizeName(name)
        val before = project(binding.sessionId)
        require(before.members.none { it.name == cleanName }) {
            "TEAM_MEMBER_NAME_CONFLICT：成员名称一经使用不可复用：$cleanName"
        }
        require(before.members.size < MAX_TEAMMATES) {
            "TEAM_MEMBER_LIMIT：最多允许 $MAX_TEAMMATES 个 teammate（包括 failed）"
        }
        val memberId = TEAM_MEMBER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
        val jobId = teamJobId(memberId)
        val provisioning = LocalTeamMemberSnapshot(
            id = memberId,
            jobId = jobId,
            name = cleanName,
            description = boundedTeamText(
                value = description,
                field = "description",
                maxChars = MAX_DESCRIPTION_CHARS,
                allowEmpty = true,
            ),
            provider = LOCAL_SUBAGENT_PROVIDER,
            context = context,
            phase = LocalTeamMemberPhase.PROVISIONING,
        )
        appendMember(binding.sessionId, provisioning)

        return try {
            val started = startTeammate(
                binding,
                jobId,
                buildTeamTaskPrompt(cleanName, description, task),
                model,
                LocalAgentRuntimeLimits.normalizeSubagentSteps(maxSteps),
                context,
                parentCallId.takeIf { context == LocalTeamMemberContext.FORK },
            )
            if (!started.accepted || started.id != jobId) {
                appendMember(
                    binding.sessionId,
                    provisioning.copy(
                        phase = LocalTeamMemberPhase.FAILED,
                        error = started.message.take(MAX_ERROR_CHARS),
                    ),
                )
                "[TEAM_MEMBER_START_REJECTED] " + started.message
            } else {
                appendMember(
                    binding.sessionId,
                    provisioning.copy(phase = LocalTeamMemberPhase.ACTIVE),
                )
                "teammate 已创建：" + cleanName + " | id=" + memberId + " | agent=" + jobId
            }
        } catch (error: Exception) {
            runCatching {
                appendMember(
                    binding.sessionId,
                    provisioning.copy(
                        phase = LocalTeamMemberPhase.FAILED,
                        error = error.message.orEmpty().take(MAX_ERROR_CHARS),
                    ),
                )
            }
            runCatching { jobs.kill(jobId, binding.sessionId) }
            throw error
        }
    }

    private fun sendMessage(
        sessionId: String,
        targetName: String,
        message: String,
    ): String {
        val state = project(sessionId)
        val member = state.members.singleOrNull {
            it.name == targetName.trim() && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$targetName")
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty()) { "TEAM_MESSAGE_REQUIRED：消息不能为空" }
        require(cleanMessage.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "TEAM_MESSAGE_TOO_LARGE：消息超过 $MAX_MESSAGE_BYTES UTF-8 字节"
        }
        require(state.pendingMessages.count { it.targetId == member.id } < MAX_PENDING_MESSAGES_PER_MEMBER) {
            "TEAM_MAILBOX_LIMIT：" + member.name +
                " 的待投递消息已达 $MAX_PENDING_MESSAGES_PER_MEMBER 条"
        }
        val snapshot = LocalTeamMessageSnapshot(
            id = "team-msg-" + UUID.randomUUID().toString().replace("-", "").take(20),
            senderId = sessionId,
            senderName = "lead",
            targetId = member.id,
            content = cleanMessage,
        )
        val log = eventLogFor(sessionId)
        log.append(TEAM_MESSAGE_QUEUED, snapshot.toEvent(sessionId))
        val delivered = deliverMessage(sessionId, snapshot, member)
        return if (delivered) {
            "Team 消息已投递：${snapshot.id} → ${member.name}"
        } else {
            "Team 消息已持久排队：${snapshot.id} → ${member.name}"
        }
    }

    private fun deliverMessage(
        sessionId: String,
        message: LocalTeamMessageSnapshot,
        member: LocalTeamMemberSnapshot,
    ): Boolean {
        val framed = "[Team message ${message.id} from ${message.senderName}] ${message.content}"
        val admission = sendToTeammate(
            member.jobId,
            QueuedAgentInput(
                id = message.id,
                content = framed,
                memoryInput = message.content,
            ),
            sessionId,
        )
        if (!admission.accepted) return false
        eventLogFor(sessionId).append(
            TEAM_MESSAGE_DELIVERED,
            buildJsonObject {
                put("version", TEAM_EVENT_VERSION)
                put("teamId", sessionId)
                put("messageId", message.id)
                put("targetId", member.id)
            },
        )
        return true
    }

    fun recoverMailbox(sessionId: String) {
        reconcileProvisioningMembers(sessionId)
        val state = project(sessionId)
        if (state.failure != null || state.pendingMessages.isEmpty()) return
        val members = state.members.associateBy(LocalTeamMemberSnapshot::id)
        state.pendingMessages.forEach { message ->
            val member = members[message.targetId] ?: return@forEach
            if (member.phase != LocalTeamMemberPhase.ACTIVE) return@forEach
            runCatching { deliverMessage(sessionId, message, member) }
        }
    }

    private fun reconcileProvisioningMembers(sessionId: String) {
        val state = project(sessionId)
        if (state.failure != null) return
        val jobStates = jobs.snapshotInfos().associateBy { it.id }
        state.members
            .filter { it.phase == LocalTeamMemberPhase.PROVISIONING }
            .forEach { member ->
                val job = jobStates[member.jobId]
                val recoverable = job?.status in setOf("running", "interrupted", "dormant", "completed")
                val terminal = if (recoverable) {
                    member.copy(phase = LocalTeamMemberPhase.ACTIVE)
                } else {
                    member.copy(
                        phase = LocalTeamMemberPhase.FAILED,
                        error = if (job == null) {
                            "TEAM_MEMBER_CHILD_MISSING"
                        } else {
                            "TEAM_MEMBER_CHILD_" + job.status.uppercase()
                        },
                    )
                }
                runCatching { appendMember(sessionId, terminal) }
            }
    }

    private fun createTask(
        sessionId: String,
        subject: String,
        description: String,
        blockedBy: List<String>,
        writeScopes: List<String>,
    ): String {
        val state = project(sessionId)
        require(state.tasks.count { it.status != LocalTeamTaskStatus.DELETED } < MAX_TASKS) {
            "TEAM_TASK_LIMIT：最多允许 $MAX_TASKS 个活动任务"
        }
        val id = nextTaskId(state.tasks)
        val task = LocalTeamTaskSnapshot(
            id = id,
            revision = 1,
            subject = boundedTeamText(
                value = subject,
                field = "subject",
                maxChars = MAX_SUBJECT_CHARS,
                allowEmpty = false,
            ),
            description = boundedTeamText(
                value = description,
                field = "description",
                maxChars = MAX_DESCRIPTION_CHARS,
                allowEmpty = true,
            ),
            status = LocalTeamTaskStatus.PENDING,
            blockedBy = normalizeTaskIds(blockedBy),
            writeScopes = normalizeWriteScopes(writeScopes),
        )
        validateTaskTransition(state.tasks, previous = null, next = task)
        appendTask(sessionId, task)
        return renderTaskView(task, project(sessionId))
    }

    private fun updateTask(
        sessionId: String,
        id: String,
        expectedRevision: Int,
        action: String,
        ownerName: String?,
        subject: String?,
        description: String?,
        blockedBy: List<String>?,
        writeScopes: List<String>?,
    ): String {
        val state = project(sessionId)
        val current = state.tasks.singleOrNull { it.id == id }
            ?: error("TEAM_TASK_NOT_FOUND：任务不存在：$id")
        require(current.revision == expectedRevision) {
            "TEAM_TASK_REVISION_CONFLICT：$id 当前 revision=${current.revision}，请求=$expectedRevision"
        }
        require(current.status != LocalTeamTaskStatus.DELETED) {
            "TEAM_TASK_DELETED：任务已删除：$id"
        }
        val normalizedAction = action.trim().lowercase()
        var next = current.copy(revision = current.revision + 1)
        next = when (normalizedAction) {
            "claim" -> {
                require(current.status == LocalTeamTaskStatus.PENDING) {
                    "TEAM_TASK_STATUS_INVALID：只有 pending 任务可以 claim"
                }
                require(isReady(current, state.tasks)) {
                    "TEAM_TASK_BLOCKED：依赖尚未完成"
                }
                val member = resolveOwner(state, ownerName)
                next.copy(status = LocalTeamTaskStatus.IN_PROGRESS, ownerId = member.id)
            }
            "release" -> {
                require(current.status == LocalTeamTaskStatus.IN_PROGRESS) {
                    "TEAM_TASK_STATUS_INVALID：只有 in_progress 任务可以 release"
                }
                next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null)
            }
            "complete" -> {
                require(current.status == LocalTeamTaskStatus.IN_PROGRESS) {
                    "TEAM_TASK_STATUS_INVALID：只有 in_progress 任务可以 complete"
                }
                next.copy(status = LocalTeamTaskStatus.COMPLETED)
            }
            "reopen" -> {
                require(current.status == LocalTeamTaskStatus.COMPLETED) {
                    "TEAM_TASK_STATUS_INVALID：只有 completed 任务可以 reopen"
                }
                next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null)
            }
            "reassign" -> {
                require(
                    current.status == LocalTeamTaskStatus.PENDING ||
                        current.status == LocalTeamTaskStatus.IN_PROGRESS,
                ) {
                    "TEAM_TASK_STATUS_INVALID：只有 pending / in_progress 任务可以 reassign"
                }
                val requestedOwner = ownerName?.trim()?.takeIf(String::isNotBlank)
                if (requestedOwner == null) {
                    next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null)
                } else {
                    require(dependenciesReady(current, state.tasks)) {
                        "TEAM_TASK_BLOCKED：依赖尚未完成"
                    }
                    val member = resolveOwner(state, requestedOwner)
                    next.copy(status = LocalTeamTaskStatus.IN_PROGRESS, ownerId = member.id)
                }
            }
            "delete" -> {
                val dependents = state.tasks.filter { task ->
                    task.status != LocalTeamTaskStatus.DELETED &&
                        task.id != current.id &&
                        current.id in task.blockedBy
                }
                require(dependents.isEmpty()) {
                    "TEAM_TASK_DELETE_BLOCKED：仍被依赖：" +
                        dependents.joinToString(",") { it.id }
                }
                next.copy(status = LocalTeamTaskStatus.DELETED, ownerId = null)
            }
            "set_dependencies" -> next.copy(
                blockedBy = normalizeTaskIds(
                    blockedBy ?: error("TEAM_TASK_DEPENDENCIES_REQUIRED：blocked_by 必填"),
                ),
            )
            "edit", "update" -> {
                require(subject != null || description != null || writeScopes != null) {
                    "TEAM_TASK_EDIT_REQUIRED：edit 至少需要 subject / description / write_scopes 之一"
                }
                next.copy(
                    subject = subject?.let {
                        boundedTeamText(it, "subject", MAX_SUBJECT_CHARS, allowEmpty = false)
                    } ?: current.subject,
                    description = description?.let {
                        boundedTeamText(it, "description", MAX_DESCRIPTION_CHARS, allowEmpty = true)
                    } ?: current.description,
                    writeScopes = writeScopes?.let(::normalizeWriteScopes) ?: current.writeScopes,
                )
            }
            else -> error("TEAM_TASK_ACTION_INVALID：不支持的 action=$action")
        }
        validateTaskTransition(state.tasks, previous = current, next = next)
        appendTask(sessionId, next)
        return renderTaskView(next, project(sessionId))
    }

    private fun interrupt(sessionId: String, targetName: String): String {
        val member = project(sessionId).members.singleOrNull {
            it.name == targetName.trim() && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$targetName")
        return jobs.interruptContinuableAgent(member.jobId, sessionId)
    }

    private suspend fun waitForChange(sessionId: String, timeoutMs: Int): String {
        val baseline = teamChangeFingerprint(sessionId)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(WAIT_POLL_MS)
            val next = teamChangeFingerprint(sessionId)
            if (next != baseline) {
                return "Team 状态已变化：event_seq=${next.eventSequence}"
            }
        }
        return "Team 等待超时：${timeoutMs}ms"
    }

    private data class TeamChangeFingerprint(
        val eventSequence: Long,
        val memberActivity: List<String>,
    )

    private fun teamChangeFingerprint(sessionId: String): TeamChangeFingerprint {
        val memberJobs = project(sessionId).members.map(LocalTeamMemberSnapshot::jobId).toSet()
        val activity = jobs.snapshotInfos()
            .asSequence()
            .filter { it.id in memberJobs }
            .sortedBy { it.id }
            .map { "${it.id}:${it.status}" }
            .toList()
        return TeamChangeFingerprint(
            eventSequence = eventLogFor(sessionId).latestSequence(),
            memberActivity = activity,
        )
    }

    private fun renderMembers(sessionId: String): String {
        val state = project(sessionId)
        val statuses = jobs.snapshotInfos().associateBy { it.id }
        val rows = buildList {
            add("lead | active | id=$sessionId")
            state.members.forEach { member ->
                val activity = statuses[member.jobId]?.status ?: "missing"
                add(
                    "${member.name} | ${member.phase.name.lowercase()} | activity=$activity | " +
                        "id=${member.id} | agent=${member.jobId}",
                )
            }
        }
        return rows.joinToString("\n") + state.failure?.let { "\nfailure=$it" }.orEmpty()
    }

    private fun renderTasks(sessionId: String): String {
        val state = project(sessionId)
        val visible = state.tasks.filter { it.status != LocalTeamTaskStatus.DELETED }
        return if (visible.isEmpty()) "Team 任务板为空"
        else visible.sortedBy(::taskNumber).joinToString("\n") { renderTaskView(it, state) }
    }

    private fun renderTask(sessionId: String, id: String, includeDeleted: Boolean): String {
        val state = project(sessionId)
        val task = state.tasks.singleOrNull { it.id == id }
            ?: error("TEAM_TASK_NOT_FOUND：任务不存在：$id")
        if (!includeDeleted && task.status == LocalTeamTaskStatus.DELETED) {
            error("TEAM_TASK_NOT_FOUND：任务不存在：$id")
        }
        return renderTaskView(task, state)
    }

    private fun renderTaskView(task: LocalTeamTaskSnapshot, state: LocalTeamProjection): String {
        val owner = task.ownerId?.let { id -> state.members.firstOrNull { it.id == id }?.name }
        val ready = isReady(task, state.tasks)
        val warnings = writeScopeWarnings(task, state.tasks)
        return buildString {
            append("${task.id} r${task.revision} [${task.status.name.lowercase()}] ")
            append(task.subject)
            append(" | ready=$ready")
            owner?.let { append(" | owner=$it") }
            if (task.blockedBy.isNotEmpty()) append(" | blockedBy=${task.blockedBy.joinToString(",")}")
            if (task.writeScopes.isNotEmpty()) append(" | writeScopes=${task.writeScopes.joinToString(",")}")
            if (warnings.isNotEmpty()) append(" | warnings=${warnings.joinToString(";")}")
        }
    }

    private fun resolveOwner(state: LocalTeamProjection, ownerName: String?): LocalTeamMemberSnapshot {
        val name = ownerName?.trim()?.takeIf(String::isNotBlank)
            ?: error("TEAM_TASK_OWNER_REQUIRED：claim 必须指定 owner")
        return state.members.singleOrNull {
            it.name == name && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$name")
    }

    private fun appendMember(sessionId: String, member: LocalTeamMemberSnapshot) {
        val current = project(sessionId)
        validateMemberTransition(current.members, member)
        eventLogFor(sessionId).append(TEAM_MEMBER_EVENT, member.toEvent(sessionId))
    }

    private fun appendTask(sessionId: String, task: LocalTeamTaskSnapshot) {
        eventLogFor(sessionId).append(TEAM_TASK_EVENT, task.toEvent(sessionId))
    }

    internal fun project(sessionId: String): LocalTeamProjection {
        val events = eventLogFor(sessionId).withEvents { stream ->
            stream
                .filter { event ->
                    event.type in TEAM_EVENTS &&
                        event.data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId
                }
                .map { event ->
                    SessionEvent(
                        sequence = event.sequence,
                        type = event.type,
                        createdAt = event.createdAt,
                        data = event.data,
                    )
                }
                .toList()
        }
        return teamProjection.fold(events).state
    }

    private fun applyEvent(
        state: LocalTeamProjection,
        event: SessionEvent,
    ): LocalTeamProjection {
        if (event.sequence <= state.asOfSequence) return state
        val advanced = state.copy(asOfSequence = event.sequence)
        if (state.failure != null) return advanced
        return try {
            when (event.type) {
                TEAM_MEMBER_EVENT -> {
                    val member = decodeMember(event.data)
                    validateMemberTransition(state.members, member)
                    advanced.copy(
                        members = state.members.filterNot { it.id == member.id } + member,
                    )
                }
                TEAM_TASK_EVENT -> {
                    val task = decodeTask(event.data)
                    val previous = state.tasks.firstOrNull { it.id == task.id }
                    validateTaskTransition(state.tasks, previous, task)
                    task.ownerId?.let { ownerId ->
                        require(
                            state.members.any { member ->
                                member.id == ownerId && member.phase == LocalTeamMemberPhase.ACTIVE
                            },
                        ) { "TEAM_TASK_OWNER_NOT_ACTIVE：" + ownerId }
                    }
                    advanced.copy(
                        tasks = state.tasks.filterNot { it.id == task.id } + task,
                    )
                }
                TEAM_MESSAGE_QUEUED -> {
                    val message = decodeMessage(event.data)
                    require(state.pendingMessages.none { it.id == message.id }) {
                        "TEAM_MESSAGE_QUEUED_TWICE：" + message.id
                    }
                    require(message.id !in state.deliveredMessageIds) {
                        "TEAM_MESSAGE_QUEUED_AFTER_DELIVERY：" + message.id
                    }
                    require(
                        state.members.any { member ->
                            member.id == message.targetId &&
                                member.phase == LocalTeamMemberPhase.ACTIVE
                        },
                    ) {
                        "TEAM_MESSAGE_TARGET_NOT_ACTIVE：" + message.targetId
                    }
                    require(message.content.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
                        "TEAM_MESSAGE_TOO_LARGE：" + message.id
                    }
                    require(
                        state.pendingMessages.count { it.targetId == message.targetId } <
                            MAX_PENDING_MESSAGES_PER_MEMBER,
                    ) {
                        "TEAM_MAILBOX_LIMIT：" + message.targetId
                    }
                    advanced.copy(pendingMessages = state.pendingMessages + message)
                }
                TEAM_MESSAGE_DELIVERED -> {
                    validateTeamEnvelope(event.data)
                    val id = event.data["messageId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_MESSAGE_DELIVERED 缺少 messageId")
                    val targetId = event.data["targetId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_MESSAGE_DELIVERED 缺少 targetId")
                    require(id !in state.deliveredMessageIds) {
                        "TEAM_MESSAGE_DELIVERED_TWICE：" + id
                    }
                    val queued = state.pendingMessages.firstOrNull { it.id == id }
                        ?: error("TEAM_MESSAGE_DELIVERED_BEFORE_QUEUE：" + id)
                    require(queued.targetId == targetId) {
                        "TEAM_MESSAGE_TARGET_CHANGED：" + id
                    }
                    advanced.copy(
                        pendingMessages = state.pendingMessages.filterNot { it.id == id },
                        deliveredMessageIds = state.deliveredMessageIds + id,
                    )
                }
                else -> advanced
            }
        } catch (error: Exception) {
            advanced.copy(failure = error.message ?: error::class.java.simpleName)
        }
    }
    private fun validateMemberTransition(
        members: List<LocalTeamMemberSnapshot>,
        next: LocalTeamMemberSnapshot,
    ) {
        require(next.id.isNotBlank() && next.jobId == teamJobId(next.id)) { "TEAM_MEMBER_ID_INVALID" }
        require(next.name.isNotBlank()) { "TEAM_MEMBER_NAME_INVALID" }
        val byName = members.firstOrNull { it.name == next.name && it.id != next.id }
        require(byName == null) { "TEAM_MEMBER_NAME_CONFLICT：${next.name}" }
        val previous = members.firstOrNull { it.id == next.id }
        if (previous == null) {
            require(next.phase == LocalTeamMemberPhase.PROVISIONING) {
                "TEAM_MEMBER_MUST_PROVISION_FIRST：" + next.id
            }
            return
        }
        require(
            previous.name == next.name &&
                previous.provider == next.provider &&
                previous.context == next.context
        ) {
            "TEAM_MEMBER_IMMUTABLE_FIELDS_CHANGED：" + next.id
        }
        require(previous.jobId == next.jobId) { "TEAM_MEMBER_JOB_CHANGED：" + next.id }
        require(previous.phase == LocalTeamMemberPhase.PROVISIONING) {
            "TEAM_MEMBER_PHASE_FINAL：${next.id}"
        }
        require(next.phase in setOf(LocalTeamMemberPhase.ACTIVE, LocalTeamMemberPhase.FAILED)) {
            "TEAM_MEMBER_PHASE_INVALID：${previous.phase} -> ${next.phase}"
        }
    }

    private fun validateTaskTransition(
        tasks: List<LocalTeamTaskSnapshot>,
        previous: LocalTeamTaskSnapshot?,
        next: LocalTeamTaskSnapshot,
    ) {
        require(next.subject.isNotBlank()) { "TEAM_TASK_SUBJECT_REQUIRED" }
        require(next.subject.length <= MAX_SUBJECT_CHARS) { "TEAM_TASK_SUBJECT_TOO_LARGE" }
        require(next.description.length <= MAX_DESCRIPTION_CHARS) { "TEAM_TASK_DESCRIPTION_TOO_LARGE" }
        require(next.blockedBy.size <= MAX_BLOCKERS) { "TEAM_TASK_BLOCKER_LIMIT" }
        require(next.blockedBy.distinct().size == next.blockedBy.size) {
            "TEAM_TASK_BLOCKER_DUPLICATE"
        }
        require(next.writeScopes.size <= MAX_WRITE_SCOPES) { "TEAM_TASK_WRITE_SCOPE_LIMIT" }
        if (previous == null) {
            require(next.status == LocalTeamTaskStatus.PENDING && next.ownerId == null) {
                "TEAM_TASK_MUST_START_PENDING：" + next.id
            }
        } else {
            val allowed = when (previous.status) {
                LocalTeamTaskStatus.PENDING -> setOf(
                    LocalTeamTaskStatus.PENDING,
                    LocalTeamTaskStatus.IN_PROGRESS,
                    LocalTeamTaskStatus.DELETED,
                )
                LocalTeamTaskStatus.IN_PROGRESS -> setOf(
                    LocalTeamTaskStatus.IN_PROGRESS,
                    LocalTeamTaskStatus.PENDING,
                    LocalTeamTaskStatus.COMPLETED,
                    LocalTeamTaskStatus.DELETED,
                )
                LocalTeamTaskStatus.COMPLETED -> setOf(
                    LocalTeamTaskStatus.COMPLETED,
                    LocalTeamTaskStatus.PENDING,
                    LocalTeamTaskStatus.DELETED,
                )
                LocalTeamTaskStatus.DELETED -> emptySet()
            }
            require(next.status in allowed) {
                "TEAM_TASK_STATUS_TRANSITION_INVALID：" +
                    previous.status.name.lowercase() + " -> " + next.status.name.lowercase()
            }
        }
        require(next.revision == (previous?.revision?.plus(1) ?: 1)) {
            "TEAM_TASK_REVISION_INVALID：${next.id}"
        }
        if (previous != null) require(previous.id == next.id) { "TEAM_TASK_ID_IMMUTABLE" }
        val current = tasks.associateBy(LocalTeamTaskSnapshot::id).toMutableMap()
        current[next.id] = next
        next.blockedBy.forEach { blocker ->
            require(blocker != next.id) { "TEAM_TASK_DEPENDENCY_CYCLE：${next.id}" }
            val target = current[blocker]
                ?: error("TEAM_TASK_BLOCKER_NOT_FOUND：$blocker")
            require(target.status != LocalTeamTaskStatus.DELETED) {
                "TEAM_TASK_BLOCKER_DELETED：$blocker"
            }
        }
        require(!hasDependencyCycle(current.values.toList())) {
            "TEAM_TASK_DEPENDENCY_CYCLE：${next.id}"
        }
        if (next.status == LocalTeamTaskStatus.IN_PROGRESS) {
            require(next.ownerId != null) { "TEAM_TASK_OWNER_REQUIRED" }
        } else if (next.status in setOf(LocalTeamTaskStatus.PENDING, LocalTeamTaskStatus.DELETED)) {
            require(next.ownerId == null) {
                "TEAM_TASK_OWNER_INVALID：" + next.status.name.lowercase() + " 任务不能携带 owner"
            }
        }
    }

    private fun hasDependencyCycle(tasks: List<LocalTeamTaskSnapshot>): Boolean {
        val map = tasks.filter { it.status != LocalTeamTaskStatus.DELETED }.associateBy { it.id }
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()
        fun visit(id: String): Boolean {
            if (!visiting.add(id)) return true
            if (!visited.add(id)) { visiting.remove(id); return false }
            val cycle = map[id]?.blockedBy.orEmpty().any(::visit)
            visiting.remove(id)
            return cycle
        }
        return map.keys.any(::visit)
    }

    private fun dependenciesReady(
        task: LocalTeamTaskSnapshot,
        tasks: List<LocalTeamTaskSnapshot>,
    ): Boolean {
        val byId = tasks.associateBy(LocalTeamTaskSnapshot::id)
        return task.blockedBy.all { byId[it]?.status == LocalTeamTaskStatus.COMPLETED }
    }

    private fun isReady(task: LocalTeamTaskSnapshot, tasks: List<LocalTeamTaskSnapshot>): Boolean =
        task.status == LocalTeamTaskStatus.PENDING && dependenciesReady(task, tasks)

    private fun writeScopeWarnings(
        task: LocalTeamTaskSnapshot,
        tasks: List<LocalTeamTaskSnapshot>,
    ): List<String> {
        if (task.writeScopes.isEmpty()) return emptyList()
        return tasks.asSequence()
            .filter { other ->
                other.id != task.id &&
                    other.status == LocalTeamTaskStatus.IN_PROGRESS &&
                    other.writeScopes.isNotEmpty()
            }
            .flatMap { other ->
                task.writeScopes.asSequence().flatMap { left ->
                    other.writeScopes.asSequence()
                        .filter { right -> scopesOverlap(left, right) }
                        .map { right -> "${other.id}:$left↔$right" }
                }
            }
            .distinct()
            .take(MAX_SCOPE_WARNINGS)
            .toList()
    }

    private fun scopesOverlap(left: String, right: String): Boolean =
        left == right || left.startsWith("$right/") || right.startsWith("$left/")

    private fun normalizeWriteScopes(values: List<String>): List<String> {
        val normalized = values
            .map { it.trim().replace('\\', '/').trim('/') }
            .filter(String::isNotBlank)
            .distinct()
        require(normalized.size <= MAX_WRITE_SCOPES) {
            "TEAM_TASK_WRITE_SCOPE_LIMIT：最多允许 $MAX_WRITE_SCOPES 个 write scope"
        }
        return normalized
    }

    private fun normalizeTaskIds(values: List<String>): List<String> {
        val normalized = values
            .map(String::trim)
            .filter(String::isNotBlank)
        require(normalized.size <= MAX_BLOCKERS) {
            "TEAM_TASK_BLOCKER_LIMIT：最多允许 $MAX_BLOCKERS 个依赖"
        }
        require(normalized.distinct().size == normalized.size) {
            "TEAM_TASK_BLOCKER_DUPLICATE：blocked_by 不能包含重复任务"
        }
        return normalized
    }

    private fun nextTaskId(tasks: List<LocalTeamTaskSnapshot>): String =
        "task-" + ((tasks.maxOfOrNull(::taskNumber) ?: 0) + 1)

    private fun taskNumber(task: LocalTeamTaskSnapshot): Int =
        task.id.removePrefix("task-").toIntOrNull() ?: Int.MAX_VALUE

    private fun normalizeName(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), "-").lowercase()
        require(clean.isNotEmpty() && clean.length <= MAX_NAME_CHARS) {
            "TEAM_MEMBER_NAME_INVALID：名称长度必须在 1..$MAX_NAME_CHARS"
        }
        require(clean.matches(Regex("[a-z0-9_-]+"))) {
            "TEAM_MEMBER_NAME_INVALID：名称只允许小写字母、数字、下划线和短横线"
        }
        return clean
    }

    private fun boundedTeamText(
        value: String,
        field: String,
        maxChars: Int,
        allowEmpty: Boolean,
    ): String {
        val clean = value.trim()
        require(allowEmpty || clean.isNotEmpty()) { "TEAM_ARGUMENT_REQUIRED：$field" }
        require(clean.length <= maxChars) {
            "TEAM_ARGUMENT_TOO_LARGE：$field 超过 $maxChars 字符"
        }
        return clean
    }

    private fun buildTeamTaskPrompt(name: String, description: String, task: String): String =
        buildString {
            append("你是 Team teammate：$name。")
            if (description.isNotBlank()) append("职责：${description.trim()}。")
            append("\n完成任务后给出可核验结论。Team Lead 后续可能通过 durable mailbox 追加消息。")
            append("\n当前任务：$task")
        }

    private fun LocalTeamMemberSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("member", buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("provider", provider)
            put("context", context.name.lowercase())
            put("phase", phase.name.lowercase())
            error?.let { put("error", it) }
        })
    }

    private fun LocalTeamTaskSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("task", buildJsonObject {
            put("id", id)
            put("revision", revision)
            put("subject", subject)
            put("description", description)
            put("status", status.name.lowercase())
            ownerId?.let { put("ownerId", it) }
            put("blockedBy", JsonArray(blockedBy.map(::JsonPrimitive)))
            put("writeScopes", JsonArray(writeScopes.map(::JsonPrimitive)))
        })
    }

    private fun LocalTeamMessageSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("version", TEAM_EVENT_VERSION)
        put("teamId", teamId)
        put("message", buildJsonObject {
            put("id", id)
            put("senderId", senderId)
            put("senderName", senderName)
            put("targetId", targetId)
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            })
        })
    }

    private fun validateTeamEnvelope(data: JsonObject) {
        require(data["version"]?.jsonPrimitive?.intOrNull == TEAM_EVENT_VERSION) {
            "TEAM_EVENT_VERSION_UNSUPPORTED"
        }
        require(!data["teamId"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "TEAM_EVENT_TEAM_ID_REQUIRED"
        }
    }

    private fun decodeMember(data: JsonObject): LocalTeamMemberSnapshot {
        validateTeamEnvelope(data)
        val member = data["member"] as? JsonObject ?: error("TEAM_MEMBER_EVENT 缺少 member")
        val id = member.requiredTeamString("id")
        return LocalTeamMemberSnapshot(
            id = id,
            jobId = teamJobId(id),
            name = member.requiredTeamString("name"),
            description = member.optionalTeamString("description").orEmpty(),
            provider = member.requiredTeamString("provider"),
            context = runCatching {
                LocalTeamMemberContext.valueOf(member.requiredTeamString("context").uppercase())
            }.getOrElse { error("TEAM_MEMBER_CONTEXT_INVALID") },
            phase = runCatching {
                LocalTeamMemberPhase.valueOf(member.requiredTeamString("phase").uppercase())
            }.getOrElse { error("TEAM_MEMBER_PHASE_INVALID") },
            error = member.optionalTeamString("error"),
        )
    }

    private fun decodeTask(data: JsonObject): LocalTeamTaskSnapshot {
        validateTeamEnvelope(data)
        val task = data["task"] as? JsonObject ?: error("TEAM_TASK_EVENT 缺少 task")
        return LocalTeamTaskSnapshot(
            id = task.requiredTeamString("id"),
            revision = task["revision"]?.jsonPrimitive?.intOrNull
                ?: error("TEAM_TASK_REVISION_REQUIRED"),
            subject = task.requiredTeamString("subject"),
            description = task.optionalTeamString("description").orEmpty(),
            status = runCatching {
                LocalTeamTaskStatus.valueOf(task.requiredTeamString("status").uppercase())
            }.getOrElse { error("TEAM_TASK_STATUS_INVALID") },
            ownerId = task.optionalTeamString("ownerId"),
            blockedBy = task.teamStringArray("blockedBy"),
            writeScopes = task.teamStringArray("writeScopes"),
        )
    }

    private fun decodeMessage(data: JsonObject): LocalTeamMessageSnapshot {
        validateTeamEnvelope(data)
        val message = data["message"] as? JsonObject ?: error("TEAM_MESSAGE_QUEUED 缺少 message")
        val blocks = message["content"] as? JsonArray ?: error("TEAM_MESSAGE_CONTENT_INVALID")
        val text = blocks.joinToString("\n") { block ->
            val obj = block as? JsonObject ?: error("TEAM_MESSAGE_CONTENT_INVALID")
            require(obj.requiredTeamString("type") == "text") { "TEAM_MESSAGE_CONTENT_UNSUPPORTED" }
            obj["text"]?.jsonPrimitive?.contentOrNull ?: error("TEAM_MESSAGE_CONTENT_INVALID")
        }
        return LocalTeamMessageSnapshot(
            id = message.requiredTeamString("id"),
            senderId = message.requiredTeamString("senderId"),
            senderName = message.requiredTeamString("senderName"),
            targetId = message.requiredTeamString("targetId"),
            content = text,
        )
    }

    private fun teamJobId(memberId: String): String {
        require(memberId.startsWith(TEAM_MEMBER_ID_PREFIX)) { "TEAM_MEMBER_ID_INVALID" }
        return TEAM_JOB_ID_PREFIX + memberId.removePrefix(TEAM_MEMBER_ID_PREFIX)
    }

    internal companion object {
        const val TEAM_MEMBER_EVENT = "team/member"
        const val TEAM_TASK_EVENT = "team/task"
        const val TEAM_MESSAGE_QUEUED = "team/message/queued"
        const val TEAM_MESSAGE_DELIVERED = "team/message/delivered"
        const val TEAM_EVENT_VERSION = 2
        const val OFFICIAL_TEAM_PROJECTION_STATE_VERSION = 4
        val TEAM_EVENTS = setOf(
            TEAM_MEMBER_EVENT,
            TEAM_TASK_EVENT,
            TEAM_MESSAGE_QUEUED,
            TEAM_MESSAGE_DELIVERED,
        )
        val TOOL_NAMES = setOf(
            "team_members",
            "team_spawn",
            "team_send_message",
            "team_task_create",
            "team_task_get",
            "team_task_list",
            "team_task_update",
            "team_interrupt",
            "team_wait",
        )
        private const val TEAM_MEMBER_ID_PREFIX = "member-"
        private const val TEAM_JOB_ID_PREFIX = "job-team-"
        private const val LOCAL_SUBAGENT_PROVIDER = "local-subagent"
        private const val MAX_TEAMMATES = 16
        private const val MAX_NAME_CHARS = 48
        private const val MAX_SUBJECT_CHARS = 240
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private const val MAX_MESSAGE_BYTES = 65_536
        private const val MAX_PENDING_MESSAGES_PER_MEMBER = 64
        private const val MAX_TASKS = 256
        private const val MAX_ERROR_CHARS = 1_000
        private const val MAX_BLOCKERS = 32
        private const val MAX_WRITE_SCOPES = 32
        private const val MAX_SCOPE_WARNINGS = 16
        private const val MAX_PROJECTION_BATCH = 512
        private const val MIN_WAIT_MS = 1_000
        private const val MAX_WAIT_MS = 60_000
        private const val WAIT_POLL_MS = 250L
    }
}

private fun JsonObject.requiredTeamString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        ?: error("TEAM_ARGUMENT_REQUIRED：$key")

private fun JsonObject.optionalTeamString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.teamStringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()

private fun JsonObject.teamStringArrayOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
