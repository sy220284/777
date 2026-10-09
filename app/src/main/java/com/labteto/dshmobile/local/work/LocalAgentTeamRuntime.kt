package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.LocalAgentTeamContract.AGENT_MESSAGE_SCAN_PAGE
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.DEFAULT_AGENT_MESSAGE_LIMIT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.LOCAL_SUBAGENT_PROVIDER
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.LOCAL_TEAM_PROJECTION_STATE_VERSION
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_AGENT_MESSAGE_LIMIT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_BLOCKERS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_DESCRIPTION_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_ERROR_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_MESSAGE_BYTES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_NAME_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_PENDING_MESSAGES_PER_MEMBER
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_RENDERED_MESSAGE_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_SCOPE_WARNINGS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_SUBJECT_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TASKS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TASK_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TEAMMATES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TEAM_MEMBER_HISTORY
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TEAM_TASK_HISTORY
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_WAIT_MS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_WRITE_SCOPES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MIN_WAIT_MS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MUTATING_TOOL_NAMES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_EVENTS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_EVENT_VERSION
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MEMBER_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MEMBER_ID_PREFIX
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_QUEUED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TOOL_NAMES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.WAIT_POLL_MS
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.teamJobId
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import com.labteto.dshmobile.local.work.LocalAgentTeamReducer.applyEvent
import com.labteto.dshmobile.local.work.LocalAgentTeamReducer.validateMemberTransition
import com.labteto.dshmobile.local.work.LocalAgentTeamReducer.validateTaskTransition
import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import com.labteto.dshmobile.harness.jobs.JobInboxContract
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.SessionProjectionRegistry
import com.labteto.dshmobile.harness.session.SessionReducer
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.jobs.LocalJobInfo
import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class LocalTeamMemberPhase {
    CREATED,
    PROVISIONING,
    ACTIVE,
    DISABLED,
    DISMISSED,
    FAILED,
}
internal fun recoverTeamStartPhase(
    previous: LocalTeamMemberPhase,
    job: LocalJobInfo?,
    admitted: Boolean,
    cancelled: Boolean,
): LocalTeamMemberPhase {
    if (job == null) return if (cancelled) previous else LocalTeamMemberPhase.FAILED
    if (job.status in setOf("failed", "cancelled", "killed")) return LocalTeamMemberPhase.FAILED
    if (
        previous == LocalTeamMemberPhase.CREATED || admitted ||
        job.pendingMessageCount > 0 || job.status in setOf("running", "stopping")
    ) return LocalTeamMemberPhase.ACTIVE
    return if (cancelled) previous else LocalTeamMemberPhase.FAILED
}

internal enum class LocalTeamMemberContext { FRESH, FORK }
internal enum class LocalTeamTaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

@Serializable
internal data class LocalTeamMemberSnapshot(
    val id: String,
    val jobId: String,
    val name: String,
    val description: String,
    val provider: String,
    val context: LocalTeamMemberContext,
    val phase: LocalTeamMemberPhase,
    val error: String? = null,
    val displayName: String = "",
    val mutableToolsEnabled: Boolean = false,
    val grantedExtensions: Set<String> = emptySet(),
)

@Serializable
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

@Serializable
internal data class LocalTeamMessageSnapshot(
    val id: String,
    val senderId: String,
    val senderName: String,
    val targetId: String,
    val content: String,
)

internal data class LocalTeamAgentMessageSnapshot(
    val id: String,
    val sequence: Long,
    val createdAt: Long,
    val memberId: String,
    val memberName: String,
    val content: String,
    val step: Int,
)

@Serializable
internal data class LocalTeamProjection(
    val members: List<LocalTeamMemberSnapshot> = emptyList(),
    val tasks: List<LocalTeamTaskSnapshot> = emptyList(),
    val pendingMessages: List<LocalTeamMessageSnapshot> = emptyList(),
    val deliveredMessageIds: List<String> = emptyList(),
    val discardedMessageIds: List<String> = emptyList(),
    val failure: String? = null,
    val asOfSequence: Long = -1L,
    val activities: List<LocalAgentTeamActivityUiState> = emptyList(),
)

/** Work-owned Agent Team domain. Durable state lives only in the lead Session EventLog. */
internal class LocalAgentTeamRuntime(
    private val jobs: LocalJobManager,
    private val startTeammate: suspend (
        binding: LocalWorkRunBinding,
        requestedJobId: String,
        task: String,
        instructions: String,
        model: String?,
        maxSteps: Int,
        context: LocalTeamMemberContext,
        parentCallId: String?,
        grantedExtensions: Set<String>,
    ) -> com.labteto.dshmobile.harness.jobs.JobStartResult,
    private val sendToTeammate: (
        agentId: String,
        input: QueuedAgentInput,
        sessionId: String,
    ) -> com.labteto.dshmobile.harness.jobs.JobMessageAdmission,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    projectionRegistry: SessionProjectionRegistry,
    projectionScope: CoroutineScope? = null,
    onProjectionReady: (String) -> Unit = {},
) {
    // All synchronous Team commands share one commit boundary; suspend starts reserve first.
    private val commandLock = Any()
    private val startingMembers = mutableSetOf<Pair<String, String>>()
    private val projectionRuntime = LocalAgentTeamProjectionRuntime(
        version = LOCAL_TEAM_PROJECTION_STATE_VERSION,
        reduce = { sessionId, state, event ->
            if (event.type in TEAM_EVENTS && event.data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId) {
                applyEvent(state, SessionEvent(event.sequence, event.type, event.createdAt, event.data))
            } else state
        },
        scope = projectionScope,
        onReady = onProjectionReady,
    )

    private val teamProjection = projectionRegistry.register(
        name = "work.agent-team",
        stateVersion = LOCAL_TEAM_PROJECTION_STATE_VERSION,
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
        awaitProjection(binding.sessionId)
        if (call.name in MUTATING_TOOL_NAMES) {
            synchronizeTeamRuntime(binding.sessionId)
        }
        val args = call.arguments
        return try {
            when (call.name) {
            "team_members" -> renderMembers(binding.sessionId)
            "team_member_status" -> renderMemberStatus(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_create_member" -> createMember(
                sessionId = binding.sessionId,
                name = args.requiredTeamString("name"),
                description = args.optionalTeamString("description").orEmpty(),
                displayName = args.optionalTeamString("display_name").orEmpty(),
                context = args.optionalTeamContext(),
            )
            "team_start_member" -> startMember(
                binding = binding,
                targetName = args.requiredTeamString("target"),
                task = args.requiredTeamString("task"),
                model = args.optionalTeamString("model"),
                maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: binding.aggregateSnapshot().subagentMaxSteps,
                parentCallId = call.id,
                grantedExtensions = args.teamStringArray("allowed_extensions").toSet(),
            )
            "team_spawn" -> spawn(
                binding = binding,
                name = args.requiredTeamString("name"),
                description = args.optionalTeamString("description").orEmpty(),
                displayName = args.optionalTeamString("display_name").orEmpty(),
                task = args.requiredTeamString("task"),
                model = args.optionalTeamString("model"),
                maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: binding.aggregateSnapshot().subagentMaxSteps,
                context = args.optionalTeamContext(),
                parentCallId = call.id,
                grantedExtensions = args.teamStringArray("allowed_extensions").toSet(),
            )
            "team_send_message" -> sendMessage(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
                message = args.requiredTeamString("message"),
            )
            "team_messages" -> renderAgentMessages(
                sessionId = binding.sessionId,
                targetName = args.optionalTeamString("target"),
                afterSequence = args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_AGENT_MESSAGE_LIMIT)
                    .coerceIn(1, MAX_AGENT_MESSAGE_LIMIT),
            )
            "team_wait_for_message" -> waitForMessage(
                sessionId = binding.sessionId,
                targetName = args.optionalTeamString("target"),
                afterSequence = args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
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
                resultId = args.optionalTeamString("result_id"),
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
            "team_disable_member" -> disableMember(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_dismiss_member" -> dismissMember(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_stop_all" -> stopAllMembers(binding.sessionId)
            "team_wait" -> waitForChange(
                sessionId = binding.sessionId,
                timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
            )
            else -> error("未覆盖的 Agent Team 工具：${call.name}")
            }
        } finally {
            binding.workState.update { current ->
                current.copy(team = uiState(binding.sessionId))
            }
        }
    }

    internal fun uiState(sessionId: String): LocalAgentTeamUiState {
        val state = try { project(sessionId) } catch (_: LocalTeamProjectionRebuilding) {
            return LocalAgentTeamUiState(rebuilding = true)
        }
        val jobsById = jobs.snapshotInfos().associateBy { it.id }
        val visibleTasks = state.tasks
            .filter { it.status != LocalTeamTaskStatus.DELETED }
            .sortedBy(::taskNumber)
        val taskById = visibleTasks.associateBy { it.id }
        val memberById = state.members.associateBy { it.id }

        val resultMessages = agentMessages(
            sessionId = sessionId,
            targetName = null,
            limit = MAX_AGENT_MESSAGE_LIMIT,
        )
        val messageCounts = resultMessages
            .groupingBy(LocalTeamAgentMessageSnapshot::memberId)
            .eachCount()
        val members = state.members.map { member ->
            val activity = when (member.phase) {
                LocalTeamMemberPhase.CREATED -> "created"
                LocalTeamMemberPhase.PROVISIONING -> "provisioning"
                LocalTeamMemberPhase.ACTIVE -> jobsById[member.jobId]?.status ?: "waiting"
                LocalTeamMemberPhase.DISABLED -> "disabled"
                LocalTeamMemberPhase.DISMISSED -> "dismissed"
                LocalTeamMemberPhase.FAILED -> "failed"
            }
            val currentTask = visibleTasks.firstOrNull {
                it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
            }
            LocalAgentTeamMemberUiState(
                id = member.id,
                jobId = member.jobId,
                name = member.name,
                description = member.description,
                displayName = member.displayName,
                mutableToolsEnabled = member.mutableToolsEnabled,
                grantedExtensions = member.grantedExtensions,
                phase = member.phase.name.lowercase(),
                activity = activity,
                currentTask = currentTask?.subject,
                hasCurrentTaskResult = currentTask != null && activity in setOf("dormant", "completed") &&
                    hasFreshTaskResult(sessionId, currentTask, member.jobId),
                progressPercent = memberProgressPercent(sessionId, member, activity),
                resultMessageCount = messageCounts[member.id] ?: 0,
                pendingMessageCount =
                    state.pendingMessages.count { it.targetId == member.id } +
                        (jobsById[member.jobId]?.pendingMessageCount ?: 0),
                error = member.error,
            )
        }
        val tasks = visibleTasks.map { task ->
            LocalAgentTeamTaskUiState(
                id = task.id,
                subject = task.subject,
                description = task.description,
                status = task.status.name.lowercase(),
                ownerName = task.ownerId?.let { id ->
                    memberById[id]?.let { member ->
                        member.displayName.ifBlank { teamMemberFriendlyName(member.name) }
                    }
                },
                blockedByTitles = task.blockedBy.map { blockerId ->
                    taskById[blockerId]?.subject ?: blockerId
                },
                ready = isReady(task, state.tasks),
                writeConflict = writeScopeWarnings(task, state.tasks).isNotEmpty(),
            )
        }
        return LocalAgentTeamUiState(
            members = members,
            tasks = tasks,
            pendingMessageCount = members.sumOf(LocalAgentTeamMemberUiState::pendingMessageCount),
            failure = state.failure,
            activities = state.activities,
        )
    }

    internal fun sendUiMessage(
        sessionId: String,
        memberId: String,
        message: String,
    ): String {
        synchronizeTeamRuntime(sessionId)
        val member = project(sessionId).members.singleOrNull {
            it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：该助手当前不可接收消息")
        return sendMessage(sessionId, member.name, message)
    }

    internal fun interruptUiMember(sessionId: String, memberId: String): String {
        synchronizeTeamRuntime(sessionId)
        val member = project(sessionId).members.singleOrNull {
            it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：该助手当前不可停止")
        return jobs.interruptContinuableAgent(member.jobId, sessionId)
    }

    internal fun interruptAllUi(sessionId: String): String {
        synchronizeTeamRuntime(sessionId)
        val state = project(sessionId)
        val jobStatus = jobs.snapshotInfos().associateBy(LocalJobInfo::id)
        val runningIds = state.members
            .filter { member ->
                member.phase == LocalTeamMemberPhase.ACTIVE &&
                    jobStatus[member.jobId]?.status == "running"
            }
            .mapTo(linkedSetOf(), LocalTeamMemberSnapshot::jobId)
        if (runningIds.isEmpty()) return "当前没有正在运行的助手"
        return jobs.interruptContinuableAgents(runningIds, sessionId)
    }

    private fun requireMemberCapacity(state: LocalTeamProjection) {
        require(state.members.count { it.phase != LocalTeamMemberPhase.DISMISSED } < MAX_TEAMMATES) {
            "TEAM_MEMBER_LIMIT：当前最多允许 " + MAX_TEAMMATES + " 个未解雇 teammate"
        }
        require(state.members.size < MAX_TEAM_MEMBER_HISTORY) {
            "TEAM_MEMBER_HISTORY_LIMIT：历史成员已达 " + MAX_TEAM_MEMBER_HISTORY + " 个"
        }
    }

    private fun createMember(
        sessionId: String,
        name: String,
        description: String,
        displayName: String,
        context: LocalTeamMemberContext,
    ): String {
        return synchronized(commandLock) {
            val cleanName = normalizeName(name)
            val before = project(sessionId)
            require(before.members.none { it.name == cleanName }) {
                "TEAM_MEMBER_NAME_CONFLICT：成员名称一经使用不可复用：" + cleanName
            }
            requireMemberCapacity(before)
            val memberId = TEAM_MEMBER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
            val member = LocalTeamMemberSnapshot(
                id = memberId,
                jobId = teamJobId(memberId),
                name = cleanName,
                displayName = displayName.trim().take(32),
                description = boundedTeamText(
                    value = description,
                    field = "description",
                    maxChars = MAX_DESCRIPTION_CHARS,
                    allowEmpty = true,
                ),
                provider = LOCAL_SUBAGENT_PROVIDER,
                context = context,
                phase = LocalTeamMemberPhase.CREATED,
            )
            appendMember(sessionId, member)
            return "teammate 已创建，等待启动：" + cleanName + " | id=" + memberId
        }
    }

    private suspend fun startMember(
        binding: LocalWorkRunBinding,
        targetName: String,
        task: String,
        model: String?,
        maxSteps: Int,
        parentCallId: String,
        grantedExtensions: Set<String>,
    ): String {
        val (member, cleanTask, provisioning) = synchronized(commandLock) {
            val before = project(binding.sessionId)
            val member = before.members.singleOrNull { it.name == targetName.trim() }
                ?: error("TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + targetName)
            require(member.phase in setOf(LocalTeamMemberPhase.CREATED, LocalTeamMemberPhase.DISABLED)) {
                "TEAM_MEMBER_START_INVALID：" + member.name +
                    " 当前 phase=" + member.phase.name.lowercase()
            }
            val cleanTask = boundedTeamText(task, "task", MAX_TASK_CHARS, allowEmpty = false)
            if (member.phase == LocalTeamMemberPhase.DISABLED && jobs.snapshotInfos().any { it.id == member.jobId }) {
                JobInboxContract.normalize(QueuedAgentInput(id = "team-resume-validation", content = cleanTask, memoryInput = cleanTask))
            }
            val resumingExisting = member.phase == LocalTeamMemberPhase.DISABLED &&
                jobs.snapshotInfos().any { it.id == member.jobId }
            val provisioning = member.copy(
                phase = LocalTeamMemberPhase.PROVISIONING,
                error = null,
                mutableToolsEnabled = if (resumingExisting) member.mutableToolsEnabled else true,
                grantedExtensions = if (resumingExisting) member.grantedExtensions else grantedExtensions,
            )
            appendMember(binding.sessionId, provisioning)
            startingMembers.add(binding.sessionId to member.id)
            Triple(member, cleanTask, provisioning)
        }
        var launchAdmitted = false
        return try {
            val existingJob = jobs.snapshotInfos().firstOrNull { it.id == member.jobId }
            if (member.phase == LocalTeamMemberPhase.DISABLED && existingJob != null) {
                val admission = sendToTeammate(
                    member.jobId,
                    QueuedAgentInput(
                        id = "team-resume-" + UUID.randomUUID().toString().replace("-", "").take(20),
                        content = cleanTask,
                        memoryInput = cleanTask,
                    ),
                    binding.sessionId,
                )
                if (!admission.accepted) {
                    appendMember(
                        binding.sessionId,
                        provisioning.copy(
                            phase = LocalTeamMemberPhase.FAILED,
                            error = admission.message.take(MAX_ERROR_CHARS),
                        ),
                    )
                    "[TEAM_MEMBER_RESUME_REJECTED] " + admission.message
                } else {
                    launchAdmitted = true
                    appendMember(
                        binding.sessionId,
                        provisioning.copy(phase = LocalTeamMemberPhase.ACTIVE),
                    )
                    "teammate 已重新启用：" + member.name
                }
            } else {
                val started = startTeammate(
                    binding,
                    member.jobId,
                    cleanTask,
                    buildTeamInstructions(member.name, member.description),
                    model,
                    LocalAgentRuntimeLimits.normalizeSubagentSteps(maxSteps),
                    member.context,
                    parentCallId.takeIf { member.context == LocalTeamMemberContext.FORK },
                    grantedExtensions,
                )
                if (!started.accepted || started.id != member.jobId) {
                    appendMember(
                        binding.sessionId,
                        provisioning.copy(
                            phase = LocalTeamMemberPhase.FAILED,
                            error = started.message.take(MAX_ERROR_CHARS),
                        ),
                    )
                    "[TEAM_MEMBER_START_REJECTED] " + started.message
                } else {
                    launchAdmitted = true
                    appendMember(
                        binding.sessionId,
                        provisioning.copy(phase = LocalTeamMemberPhase.ACTIVE),
                    )
                    "teammate 已启动：" + member.name + " | agent=" + member.jobId
                }
            }
        } catch (error: Exception) {
            val job = jobs.snapshotInfos().firstOrNull { it.id == member.jobId }
            val recoveredPhase = recoverTeamStartPhase(
                previous = member.phase,
                job = job,
                admitted = launchAdmitted,
                cancelled = error is CancellationException,
            )
            runCatching {
                synchronized(commandLock) {
                    val current = project(binding.sessionId).members.singleOrNull { it.id == member.id }
                    if (current?.phase == LocalTeamMemberPhase.PROVISIONING) {
                        appendMember(
                            binding.sessionId,
                            provisioning.copy(
                                phase = recoveredPhase,
                                error = if (recoveredPhase == LocalTeamMemberPhase.FAILED) {
                                    error.message.orEmpty().take(MAX_ERROR_CHARS)
                                } else null,
                            ),
                        )
                    }
                }
            }
            throw error
        } finally {
            synchronized(commandLock) { startingMembers.remove(binding.sessionId to member.id) }
        }
    }

    private fun disableMember(sessionId: String, targetName: String): String {
        return synchronized(commandLock) {
            synchronizeTeamRuntime(sessionId)
            val state = project(sessionId)
            val member = state.members.singleOrNull { it.name == targetName.trim() }
                ?: error("TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + targetName)
            require(member.phase in setOf(LocalTeamMemberPhase.CREATED, LocalTeamMemberPhase.ACTIVE)) {
                "TEAM_MEMBER_DISABLE_INVALID：" + member.name +
                    " 当前 phase=" + member.phase.name.lowercase()
            }
            if (member.phase == LocalTeamMemberPhase.ACTIVE) {
                require(jobs.snapshotInfos().any { it.id == member.jobId }) {
                    "TEAM_MEMBER_CHILD_MISSING：" + member.name
                }
                jobs.interruptContinuableAgent(member.jobId, sessionId)
            }
            releaseOwnedTasks(sessionId, member.id)
            appendMember(sessionId, member.copy(phase = LocalTeamMemberPhase.DISABLED, error = null))
            return "teammate 已停用：" + member.name + "；身份与 durable mailbox 已保留"
        }
    }

    private fun dismissMember(sessionId: String, targetName: String): String {
        return synchronized(commandLock) {
            synchronizeTeamRuntime(sessionId)
            val state = project(sessionId)
            val member = state.members.singleOrNull { it.name == targetName.trim() }
                ?: error("TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + targetName)
            require(
                member.phase in setOf(
                    LocalTeamMemberPhase.CREATED,
                    LocalTeamMemberPhase.ACTIVE,
                    LocalTeamMemberPhase.DISABLED,
                    LocalTeamMemberPhase.FAILED,
                ),
            ) {
                "TEAM_MEMBER_DISMISS_INVALID：" + member.name +
                    " 当前 phase=" + member.phase.name.lowercase()
            }
            if (member.phase == LocalTeamMemberPhase.ACTIVE ||
                member.phase == LocalTeamMemberPhase.DISABLED
            ) {
                val childExists = jobs.snapshotInfos().any { it.id == member.jobId }
                require(childExists || member.phase == LocalTeamMemberPhase.DISABLED) {
                    "TEAM_MEMBER_CHILD_MISSING：" + member.name
                }
                if (childExists) {
                    jobs.kill(member.jobId, sessionId)
                }
            }
            releaseOwnedTasks(sessionId, member.id)
            appendMember(sessionId, member.copy(phase = LocalTeamMemberPhase.DISMISSED, error = null))
            discardPendingMessages(sessionId, member.id, reason = "member_dismissed")
            return "teammate 已解雇：" + member.name
        }
    }

    private fun stopAllMembers(sessionId: String): String =
        interruptAllUi(sessionId)

    private suspend fun spawn(
        binding: LocalWorkRunBinding,
        name: String,
        description: String,
        displayName: String,
        task: String,
        model: String?,
        maxSteps: Int,
        context: LocalTeamMemberContext,
        parentCallId: String,
        grantedExtensions: Set<String>,
    ): String {
        val provisioning = synchronized(commandLock) {
            val cleanName = normalizeName(name)
            val before = project(binding.sessionId)
            require(before.members.none { it.name == cleanName }) {
                "TEAM_MEMBER_NAME_CONFLICT：成员名称一经使用不可复用：$cleanName"
            }
            requireMemberCapacity(before)
            val memberId = TEAM_MEMBER_ID_PREFIX + UUID.randomUUID().toString().replace("-", "").take(16)
            val jobId = teamJobId(memberId)
            val provisioning = LocalTeamMemberSnapshot(
                id = memberId,
                jobId = jobId,
                name = cleanName,
                displayName = displayName.trim().take(32),
                mutableToolsEnabled = true,
                grantedExtensions = grantedExtensions,
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
            startingMembers.add(binding.sessionId to provisioning.id)
            provisioning

        }
        val cleanName = provisioning.name
        val memberId = provisioning.id
        val jobId = provisioning.jobId

        return try {
            val started = startTeammate(
                binding,
                jobId,
                task,
                buildTeamInstructions(cleanName, description),
                model,
                LocalAgentRuntimeLimits.normalizeSubagentSteps(maxSteps),
                context,
                parentCallId.takeIf { context == LocalTeamMemberContext.FORK },
                grantedExtensions,
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
        } finally {
            synchronized(commandLock) { startingMembers.remove(binding.sessionId to memberId) }
        }
    }

    private fun sendMessage(
        sessionId: String,
        targetName: String,
        message: String,
    ): String {
        return synchronized(commandLock) {
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
            teamInboxInput(snapshot) // Reject before recording an undeliverable message.
            val log = eventLogFor(sessionId)
            log.append(TEAM_MESSAGE_QUEUED, snapshot.toEvent(sessionId))
            val delivered = deliverMessage(sessionId, snapshot, member)
            return if (delivered) {
                "Team 消息已投递：${snapshot.id} → ${member.name}"
            } else {
                "Team 消息已持久排队：${snapshot.id} → ${member.name}"
            }
        }
    }

    private fun discardPendingMessages(
        sessionId: String,
        memberId: String,
        reason: String,
    ) {
        synchronized(commandLock) {
            val pending = project(sessionId).pendingMessages.filter { it.targetId == memberId }
            pending.forEach { message -> discardMessage(sessionId, message, reason) }
        }
    }

    private fun discardMessage(sessionId: String, message: LocalTeamMessageSnapshot, reason: String) {
        eventLogFor(sessionId).append(
            TEAM_MESSAGE_DISCARDED,
            buildJsonObject {
                put("version", TEAM_EVENT_VERSION)
                put("teamId", sessionId)
                put("messageId", message.id)
                put("targetId", message.targetId)
                put("reason", reason.take(MAX_ERROR_CHARS))
            },
        )
    }

    private fun teamInboxInput(message: LocalTeamMessageSnapshot): QueuedAgentInput =
        JobInboxContract.normalize(QueuedAgentInput(
            id = message.id,
            content = "[Team message ${message.id} from ${message.senderName}] ${message.content}",
            memoryInput = message.content,
        ))

    private fun deliverMessage(
        sessionId: String,
        message: LocalTeamMessageSnapshot,
        member: LocalTeamMemberSnapshot,
    ): Boolean {
        return synchronized(commandLock) {
            val before = project(sessionId)
            if (message.id in before.deliveredMessageIds) return true
            if (before.pendingMessages.none { it.id == message.id }) return false
            val currentMember = before.members.singleOrNull { it.id == member.id }
            if (currentMember?.phase != LocalTeamMemberPhase.ACTIVE) return false
            val admission = sendToTeammate(
                member.jobId,
                teamInboxInput(message),
                sessionId,
            )
            if (!admission.accepted) return false
            if (message.id in project(sessionId).deliveredMessageIds) return true
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
    }

    fun recoverMailbox(sessionId: String) = synchronized(commandLock) {
        reconcileProvisioningMembers(sessionId)
        reconcileMemberOutcomes(sessionId)
        reconcileTerminalMailboxes(sessionId)
        val state = project(sessionId)
        if (state.failure != null || state.pendingMessages.isEmpty()) return@synchronized
        val members = state.members.associateBy(LocalTeamMemberSnapshot::id)
        state.pendingMessages.forEach { message ->
            try {
                teamInboxInput(message)
            } catch (error: IllegalArgumentException) {
                discardMessage(sessionId, message, error.message ?: "消息无法接收，请拆分后重发")
                return@forEach
            }
            val member = members[message.targetId] ?: return@forEach
            if (member.phase != LocalTeamMemberPhase.ACTIVE) return@forEach
            runCatching { deliverMessage(sessionId, message, member) }
        }
    }

    private fun reconcileTerminalMailboxes(sessionId: String) {
        val state = project(sessionId)
        if (state.failure != null || state.pendingMessages.isEmpty()) return
        val terminalMemberIds = state.members
            .filter {
                it.phase == LocalTeamMemberPhase.FAILED ||
                    it.phase == LocalTeamMemberPhase.DISMISSED
            }
            .mapTo(hashSetOf(), LocalTeamMemberSnapshot::id)
        if (terminalMemberIds.isEmpty()) return
        state.pendingMessages
            .asSequence()
            .filter { it.targetId in terminalMemberIds }
            .map(LocalTeamMessageSnapshot::targetId)
            .distinct()
            .forEach { memberId ->
                discardPendingMessages(
                    sessionId = sessionId,
                    memberId = memberId,
                    reason = "member_terminal",
                )
            }
    }

    private fun reconcileProvisioningMembers(sessionId: String) {
        val state = project(sessionId)
        if (state.failure != null) return
        val jobStates = jobs.snapshotInfos().associateBy { it.id }
        state.members
            .filter { it.phase == LocalTeamMemberPhase.PROVISIONING && (sessionId to it.id) !in startingMembers }
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
                appendMember(sessionId, terminal)
            }
    }

    private fun synchronizeTeamRuntime(sessionId: String) {
        recoverMailbox(sessionId)
    }

    private fun reconcileMemberOutcomes(sessionId: String) {
        val state = project(sessionId)
        if (state.failure != null) return
        val jobStates = jobs.snapshotInfos().associateBy(LocalJobInfo::id)
        state.members
            .filter { it.phase == LocalTeamMemberPhase.ACTIVE }
            .forEach { member ->
                val job = jobStates[member.jobId]
                if (job == null) {
                    releaseOwnedTasks(sessionId, member.id)
                    val current = project(sessionId).members
                        .singleOrNull { it.id == member.id }
                        ?: return@forEach
                    if (current.phase == LocalTeamMemberPhase.ACTIVE) {
                        appendMember(
                            sessionId,
                            current.copy(
                                phase = LocalTeamMemberPhase.FAILED,
                                error = "TEAM_MEMBER_CHILD_MISSING",
                            ),
                        )
                    }
                    return@forEach
                }
                when (val status = job.status) {
                    "failed", "cancelled", "killed" -> {
                        releaseOwnedTasks(sessionId, member.id)
                        val current = project(sessionId).members
                            .singleOrNull { it.id == member.id }
                            ?: return@forEach
                        if (current.phase == LocalTeamMemberPhase.ACTIVE) {
                            appendMember(
                                sessionId,
                                current.copy(
                                    phase = LocalTeamMemberPhase.FAILED,
                                    error = "TEAM_MEMBER_CHILD_" + status.uppercase(),
                                ),
                            )
                        }
                    }
                }
            }
    }

    private fun releaseOwnedTasks(sessionId: String, memberId: String) {
        synchronized(commandLock) {
            val taskIds = project(sessionId).tasks
                .filter {
                    it.status == LocalTeamTaskStatus.IN_PROGRESS && it.ownerId == memberId
                }
                .sortedBy(::taskNumber)
                .map(LocalTeamTaskSnapshot::id)

            taskIds.forEach { taskId ->
                val currentState = project(sessionId)
                val current = currentState.tasks.singleOrNull { it.id == taskId } ?: return@forEach
                if (
                    current.status == LocalTeamTaskStatus.IN_PROGRESS &&
                    current.ownerId == memberId
                ) {
                    val released = current.copy(
                        revision = current.revision + 1,
                        status = LocalTeamTaskStatus.PENDING,
                        ownerId = null,
                    )
                    validateTaskTransition(currentState.tasks, previous = current, next = released)
                    appendTask(sessionId, released)
                }
            }
        }
    }

    private fun createTask(
        sessionId: String,
        subject: String,
        description: String,
        blockedBy: List<String>,
        writeScopes: List<String>,
    ): String {
        return synchronized(commandLock) {
            val state = project(sessionId)
            require(state.tasks.count { it.status != LocalTeamTaskStatus.DELETED } < MAX_TASKS) {
                "TEAM_TASK_LIMIT：最多允许 $MAX_TASKS 个活动任务"
            }
            require(state.tasks.size < MAX_TEAM_TASK_HISTORY) {
                "TEAM_TASK_HISTORY_LIMIT：历史任务已达 $MAX_TEAM_TASK_HISTORY 个"
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
    }

    private fun updateTask(
        sessionId: String,
        id: String,
        expectedRevision: Int,
        action: String,
        resultId: String?,
        ownerName: String?,
        subject: String?,
        description: String?,
        blockedBy: List<String>?,
        writeScopes: List<String>?,
    ): String {
        return synchronized(commandLock) {
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
                    val verifiedResultId = resultId?.takeIf(String::isNotBlank)
                        ?: error("TEAM_TASK_RESULT_REQUIRED：complete 必须提供已核验的 result_id")
                    val owner = state.members.singleOrNull { it.id == current.ownerId }
                        ?: error("TEAM_TASK_OWNER_REQUIRED：任务负责人不存在")
                    require(hasFreshTaskResult(sessionId, current, owner.jobId, verifiedResultId)) {
                        "TEAM_TASK_RESULT_STALE：结果必须属于当前负责人且晚于当前任务 revision"
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
    }

    private fun interrupt(sessionId: String, targetName: String): String {
        val member = project(sessionId).members.singleOrNull {
            it.name == targetName.trim() && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$targetName")
        return jobs.interruptContinuableAgent(member.jobId, sessionId)
    }

    private fun renderMemberStatus(sessionId: String, targetName: String): String {
        val state = project(sessionId)
        val member = state.members.singleOrNull { it.name == targetName.trim() }
            ?: error("TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + targetName)
        val jobStatus = jobs.snapshotInfos()
            .firstOrNull { it.id == member.jobId }
            ?.status
            ?: when (member.phase) {
                LocalTeamMemberPhase.CREATED -> "created"
                LocalTeamMemberPhase.DISABLED -> "disabled"
                LocalTeamMemberPhase.DISMISSED -> "dismissed"
                LocalTeamMemberPhase.FAILED -> "failed"
                else -> "missing"
            }
        val progress = memberProgressPercent(sessionId, member, jobStatus)
        val currentTask = state.tasks.firstOrNull {
            it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
        }?.subject
        val messages = agentMessages(sessionId, member.name, MAX_AGENT_MESSAGE_LIMIT)
        return buildString {
            append(member.name)
            append(" | phase=")
            append(member.phase.name.lowercase())
            append(" | activity=")
            append(jobStatus)
            append(" | progress=")
            append(progress)
            append("% | id=")
            append(member.id)
            append(" | agent=")
            append(member.jobId)
            currentTask?.let {
                append(" | task=")
                append(it)
            }
            append(" | messages=")
            append(messages.size)
            messages.lastOrNull()?.let {
                append("\nlatest_message=")
                append(it.content.take(MAX_RENDERED_MESSAGE_CHARS))
            }
            member.error?.let {
                append("\nerror=")
                append(it)
            }
        }
    }

    private fun hasFreshTaskResult(
        sessionId: String,
        task: LocalTeamTaskSnapshot,
        jobId: String,
        resultId: String? = null,
    ): Boolean {
        val log = eventLogFor(sessionId)
        val taskSequence = log.latestMatching(setOf(TEAM_TASK_EVENT)) { data ->
            val raw = data["task"] as? JsonObject ?: return@latestMatching false
            raw["id"]?.jsonPrimitive?.contentOrNull == task.id &&
                raw["revision"]?.jsonPrimitive?.intOrNull == task.revision
        }?.sequence ?: return false
        var before = Long.MAX_VALUE
        while (true) {
            val event = log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT), before) { data ->
                data["background_job_id"]?.jsonPrimitive?.contentOrNull == jobId &&
                    (resultId == null || resultIdentity(data) == resultId) &&
                    !data["terminal_output"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            } ?: return false
            if (event.sequence <= taskSequence) return false
            if (isFirstResultCheckpoint(log, event)) return true
            before = event.sequence
        }
    }

    private fun resultIdentity(event: LocalSessionEventLog.Event): String =
        resultIdentity(event.data)

    private fun resultIdentity(data: JsonObject): String =
        data["result_id"]?.jsonPrimitive?.contentOrNull ?: (
            "legacy-result-" + data["background_job_id"]?.jsonPrimitive?.contentOrNull +
                "-" + data["step"]?.jsonPrimitive?.intOrNull
        )

    private fun isFirstResultCheckpoint(log: LocalSessionEventLog, event: LocalSessionEventLog.Event): Boolean {
        if (event.data["terminal_output"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return false
        if (event.data["result_id"] != null) {
            return event.data["result_first"]?.jsonPrimitive?.booleanOrNull == true
        }
        // V1 had two checkpoints for one model step. Only the first is a result.
        val jobId = event.data["background_job_id"]
        val step = event.data["step"]
        return log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT), event.sequence) { data ->
            data["background_job_id"] == jobId && data["step"] == step &&
                !data["terminal_output"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        } == null
    }

    internal data class LocalTeamAgentMessagePage(
        val messages: List<LocalTeamAgentMessageSnapshot>,
        val nextCursor: Long,
        val hasMore: Boolean,
    )

    internal fun agentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): List<LocalTeamAgentMessageSnapshot> = scanAgentMessages(sessionId, targetName, limit, afterSequence).messages

    internal fun scanAgentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): LocalTeamAgentMessagePage {
        val state = project(sessionId)
        val membersByJob = state.members.associateBy(LocalTeamMemberSnapshot::jobId)
        val target = targetName?.trim()?.takeIf(String::isNotBlank)
        if (target != null) {
            require(state.members.any { it.name == target }) {
                "TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + target
            }
        }
        val bounded = limit.coerceIn(1, MAX_AGENT_MESSAGE_LIMIT)
        val log = eventLogFor(sessionId)
        val latest = log.latestSequence()

        fun decode(event: LocalSessionEventLog.Event): LocalTeamAgentMessageSnapshot? {
            if (event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT) return null
            val jobId = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val member = membersByJob[jobId] ?: return null
            if (target != null && member.name != target) return null
            if (!isFirstResultCheckpoint(log, event)) return null
            val content = event.data["terminal_output"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return null
            return LocalTeamAgentMessageSnapshot(
                id = resultIdentity(event),
                sequence = event.sequence,
                createdAt = event.createdAt,
                memberId = member.id,
                memberName = member.name,
                content = content,
                step = event.data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
            )
        }

        if (afterSequence >= 0L) {
            val result = ArrayList<LocalTeamAgentMessageSnapshot>(bounded)
            var cursor = afterSequence
            var pages = 0
            while (result.size < bounded && cursor < latest && pages++ < 32) {
                val page = log.pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
                if (page.isEmpty()) break
                for (event in page.sortedBy(LocalSessionEventLog.Event::sequence)) {
                    if (event.sequence > latest) break
                    cursor = maxOf(cursor, event.sequence)
                    decode(event)?.let { message -> if (result.none { it.id == message.id }) result.add(message) }
                    if (result.size >= bounded) break
                }
                if (page.size < AGENT_MESSAGE_SCAN_PAGE) break
            }
            return LocalTeamAgentMessagePage(result, cursor, cursor < latest)
        }

        val result = ArrayList<LocalTeamAgentMessageSnapshot>(bounded)
        var before = Long.MAX_VALUE
        repeat(MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES) {
            if (result.size >= bounded) return@repeat
            val page = log.pageBeforeNewestFirst(before, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) return@repeat
            page.asSequence()
                .sortedByDescending(LocalSessionEventLog.Event::sequence)
                .forEach { event ->
                    if (result.size >= bounded) return@forEach
                    decode(event)?.let { message -> if (result.none { it.id == message.id }) result.add(message) }
                }
            before = page.minOf(LocalSessionEventLog.Event::sequence)
            if (page.size < AGENT_MESSAGE_SCAN_PAGE) return@repeat
        }
        val messages = result.distinctBy(LocalTeamAgentMessageSnapshot::id)
            .sortedBy(LocalTeamAgentMessageSnapshot::sequence).takeLast(bounded)
        return LocalTeamAgentMessagePage(messages, messages.lastOrNull()?.sequence ?: latest, false)
    }

    private fun renderAgentMessages(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        limit: Int,
    ): String {
        val page = scanAgentMessages(
            sessionId = sessionId,
            targetName = targetName,
            limit = limit,
            afterSequence = afterSequence,
        )
        val messages = page.messages
        if (messages.isEmpty()) {
            val label = if (targetName == null) {
                "暂无助手消息"
            } else {
                targetName.trim() + " 暂无助手消息"
            }
            return label + "\nnext_cursor=" + page.nextCursor + "\nhas_more=" + page.hasMore
        }
        val nextCursor = page.nextCursor
        return messages.joinToString("\n\n") { message ->
            "[" + message.id + "] " + message.memberName +
                " sequence=" + message.sequence +
                " step=" + message.step + "\n" +
                message.content.take(MAX_RENDERED_MESSAGE_CHARS)
        } + "\n\nnext_cursor=" + nextCursor + "\nhas_more=" + page.hasMore
    }

    private data class AgentMessageForwardScan(
        val message: LocalTeamAgentMessageSnapshot?,
        val scannedThroughSequence: Long,
    )

    private fun nextAgentMessageAfter(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
    ): AgentMessageForwardScan {
        val state = project(sessionId)
        val membersByJob = state.members.associateBy(LocalTeamMemberSnapshot::jobId)
        val target = targetName?.trim()?.takeIf(String::isNotBlank)
        if (target != null) {
            require(state.members.any { it.name == target }) {
                "TEAM_MEMBER_NOT_FOUND：找不到 teammate：" + target
            }
        }

        var cursor = afterSequence
        repeat(16) {
            val page = eventLogFor(sessionId).pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) {
                return AgentMessageForwardScan(null, cursor)
            }
            page.sortedBy(LocalSessionEventLog.Event::sequence).forEach { event ->
                cursor = maxOf(cursor, event.sequence)
                if (event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT) return@forEach
                val jobId = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val member = membersByJob[jobId] ?: return@forEach
                if (target != null && member.name != target) return@forEach
                if (!isFirstResultCheckpoint(eventLogFor(sessionId), event)) return@forEach
                val content = event.data["terminal_output"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                return AgentMessageForwardScan(
                    message = LocalTeamAgentMessageSnapshot(
                        id = resultIdentity(event),
                        sequence = event.sequence,
                        createdAt = event.createdAt,
                        memberId = member.id,
                        memberName = member.name,
                        content = content,
                        step = event.data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
                    ),
                    scannedThroughSequence = cursor,
                )
            }
            if (page.size < AGENT_MESSAGE_SCAN_PAGE) {
                return AgentMessageForwardScan(null, cursor)
            }
        }
        return AgentMessageForwardScan(null, cursor)
    }

    private suspend fun waitForMessage(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        timeoutMs: Int,
    ): String {
        var scanAfter = if (afterSequence >= 0L) {
            afterSequence
        } else {
            eventLogFor(sessionId).latestSequence()
        }

        fun scanNext(): LocalTeamAgentMessageSnapshot? {
            val scan = nextAgentMessageAfter(
                sessionId = sessionId,
                targetName = targetName,
                afterSequence = scanAfter,
            )
            scanAfter = maxOf(scanAfter, scan.scannedThroughSequence)
            return scan.message
        }

        scanNext()?.let { next ->
            return "[" + next.id + "] " + next.memberName +
                " sequence=" + next.sequence +
                " step=" + next.step + "\n" +
                next.content.take(MAX_RENDERED_MESSAGE_CHARS) +
                "\nnext_cursor=" + next.sequence
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(WAIT_POLL_MS)
            scanNext()?.let { next ->
                return "[" + next.id + "] " + next.memberName +
                    " sequence=" + next.sequence +
                    " step=" + next.step + "\n" +
                    next.content.take(MAX_RENDERED_MESSAGE_CHARS) +
                    "\nnext_cursor=" + next.sequence
            }
        }
        return "等待助手消息超时：" + timeoutMs + "ms"
    }

    private fun memberProgressPercent(
        sessionId: String,
        member: LocalTeamMemberSnapshot,
        activity: String,
    ): Int {
        if (member.phase == LocalTeamMemberPhase.CREATED) return 0
        if (member.phase == LocalTeamMemberPhase.PROVISIONING) return 5
        if (member.phase == LocalTeamMemberPhase.DISMISSED) return 100
        val log = eventLogFor(sessionId)
        val checkpoint = log.latestMatching(
            setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT),
        ) { data ->
            data["background_job_id"]?.jsonPrimitive?.contentOrNull == member.jobId
        }
        val step = checkpoint?.data
            ?.get("step")
            ?.jsonPrimitive
            ?.intOrNull
            ?.coerceAtLeast(0)
            ?: 0
        val softLimit = checkpoint?.data
            ?.get("soft_step_limit")
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }
        val startLimit = log.latestMatching(setOf("subagent/start")) { data ->
            data["background_job_id"]?.jsonPrimitive?.contentOrNull == member.jobId
        }?.data
            ?.get("max_steps")
            ?.jsonPrimitive
            ?.intOrNull
            ?.takeIf { it > 0 }
        val limit = softLimit ?: startLimit ?: 1
        val measured = ((step.toDouble() / limit.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 95)
        return when {
            activity == "dormant" || activity == "completed" -> 100
            member.phase == LocalTeamMemberPhase.FAILED ||
                activity in setOf("failed", "cancelled", "killed") -> measured
            member.phase == LocalTeamMemberPhase.DISABLED ||
                activity == "interrupted" -> measured
            activity == "running" || activity == "stopping" -> measured.coerceAtLeast(10)
            else -> measured
        }
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
                val activity = statuses[member.jobId]?.status ?: when (member.phase) {
                    LocalTeamMemberPhase.CREATED -> "created"
                    LocalTeamMemberPhase.DISABLED -> "disabled"
                    LocalTeamMemberPhase.DISMISSED -> "dismissed"
                    LocalTeamMemberPhase.FAILED -> "failed"
                    else -> "missing"
                }
                val progress = memberProgressPercent(sessionId, member, activity)
                val messages = agentMessages(
                    sessionId = sessionId,
                    targetName = member.name,
                    limit = MAX_AGENT_MESSAGE_LIMIT,
                ).size
                add(
                    member.name + " | " + member.phase.name.lowercase() +
                        " | activity=" + activity +
                        " | progress=" + progress + "%" +
                        " | messages=" + messages +
                        " | id=" + member.id +
                        " | agent=" + member.jobId,
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
        synchronized(commandLock) {
            val current = project(sessionId)
            validateMemberTransition(current.members, member)
            eventLogFor(sessionId).append(TEAM_MEMBER_EVENT, member.toEvent(sessionId))
        }
    }

    private fun appendTask(sessionId: String, task: LocalTeamTaskSnapshot) {
        synchronized(commandLock) {
            eventLogFor(sessionId).append(TEAM_TASK_EVENT, task.toEvent(sessionId))
        }
    }

    internal fun activityProjection(sessionId: String): LocalAgentTeamActivityProjection =
        projectLocalAgentTeamActivity(
            team = project(sessionId),
            jobs = jobs.snapshotInfos(),
        )

    internal suspend fun awaitProjection(sessionId: String) {
        projectionRuntime.awaitReady(sessionId, eventLogFor(sessionId))
    }

    internal fun project(sessionId: String): LocalTeamProjection =
        projectionRuntime.project(sessionId, eventLogFor(sessionId))

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

    private fun buildTeamInstructions(name: String, description: String): String =
        buildString {
            append("你是 Team teammate：$name。")
            if (description.isNotBlank()) append("职责：${description.trim()}。")
            append("\n完成任务后给出可核验结论。Team Lead 后续可能通过 durable mailbox 追加消息。")
        }


}
