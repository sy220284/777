package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
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

internal data class LocalTeamAgentMessageSnapshot(
    val id: String,
    val sequence: Long,
    val createdAt: Long,
    val memberId: String,
    val memberName: String,
    val content: String,
    val step: Int,
)

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
    ) -> com.labteto.dshmobile.harness.jobs.JobStartResult,
    private val sendToTeammate: (
        agentId: String,
        input: QueuedAgentInput,
        sessionId: String,
    ) -> com.labteto.dshmobile.harness.jobs.JobMessageAdmission,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    projectionRegistry: SessionProjectionRegistry,
) {
    // All synchronous Team commands share one commit boundary; suspend starts reserve first.
    private val commandLock = Any()
    private val startingMembers = mutableSetOf<Pair<String, String>>()
    private data class ProjectionEntry(val log: LocalSessionEventLog, val generation: Long, val cursor: Long, val state: LocalTeamProjection)
    private val projections = object : LinkedHashMap<String, ProjectionEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProjectionEntry>): Boolean = size > 32
    }

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
            )
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
        val state = project(sessionId)
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
                ownerName = task.ownerId?.let { memberById[it]?.name },
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
            val provisioning = member.copy(phase = LocalTeamMemberPhase.PROVISIONING, error = null)
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
        task: String,
        model: String?,
        maxSteps: Int,
        context: LocalTeamMemberContext,
        parentCallId: String,
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
            pending.forEach { message ->
                eventLogFor(sessionId).append(
                    TEAM_MESSAGE_DISCARDED,
                    buildJsonObject {
                        put("version", TEAM_EVENT_VERSION)
                        put("teamId", sessionId)
                        put("messageId", message.id)
                        put("targetId", memberId)
                        put("reason", reason.take(MAX_ERROR_CHARS))
                    },
                )
            }
        }
    }

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

    internal fun project(sessionId: String): LocalTeamProjection = synchronized(commandLock) {
        val log = eventLogFor(sessionId)
        val generation = log.resetGeneration
        val latest = log.latestSequence()
        val cached = projections[sessionId]?.takeIf { it.log === log && it.generation == generation && it.cursor <= latest }
        var state = cached?.state ?: LocalTeamProjection()
        var cursor = cached?.cursor ?: -1L
        while (cursor < latest) {
            val page = log.pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) break
            for (event in page) {
                if (event.sequence > latest) break
                cursor = event.sequence
                if (event.type in TEAM_EVENTS && event.data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId) {
                    state = applyEvent(state, SessionEvent(event.sequence, event.type, event.createdAt, event.data))
                }
            }
        }
        projections[sessionId] = ProjectionEntry(log, generation, cursor, state)
        state
    }

    private fun applyEvent(
        state: LocalTeamProjection,
        event: SessionEvent,
    ): LocalTeamProjection {
        if (event.sequence <= state.asOfSequence) return state
        val advanced = state.copy(asOfSequence = event.sequence)
        if (state.failure != null) return advanced
        return try {
            val next = when (event.type) {
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
                    require(message.id !in state.discardedMessageIds) {
                        "TEAM_MESSAGE_QUEUED_AFTER_DISCARD：" + message.id
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
                    require(id !in state.discardedMessageIds) {
                        "TEAM_MESSAGE_DELIVERED_AFTER_DISCARD：" + id
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
                TEAM_MESSAGE_DISCARDED -> {
                    validateTeamEnvelope(event.data)
                    val id = event.data["messageId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_MESSAGE_DISCARDED 缺少 messageId")
                    val targetId = event.data["targetId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_MESSAGE_DISCARDED 缺少 targetId")
                    require(id !in state.deliveredMessageIds) {
                        "TEAM_MESSAGE_DISCARDED_AFTER_DELIVERY：" + id
                    }
                    require(id !in state.discardedMessageIds) {
                        "TEAM_MESSAGE_DISCARDED_TWICE：" + id
                    }
                    val queued = state.pendingMessages.firstOrNull { it.id == id }
                        ?: error("TEAM_MESSAGE_DISCARDED_BEFORE_QUEUE：" + id)
                    require(queued.targetId == targetId) {
                        "TEAM_MESSAGE_TARGET_CHANGED：" + id
                    }
                    advanced.copy(
                        pendingMessages = state.pendingMessages.filterNot { it.id == id },
                        discardedMessageIds = state.discardedMessageIds + id,
                    )
                }
                else -> advanced
            }
            val activity = when (event.type) {
                TEAM_MEMBER_EVENT -> decodeMember(event.data).let { member ->
                    LocalAgentTeamActivityUiState(
                        event.sequence, "member", truncateWithoutSplittingSurrogatePair(member.description, 180),
                        member.phase.name.lowercase(), member.name,
                    )
                }
                TEAM_TASK_EVENT -> decodeTask(event.data).let { task ->
                    LocalAgentTeamActivityUiState(
                        event.sequence, "task", truncateWithoutSplittingSurrogatePair(task.subject, 180),
                        if (task.status == LocalTeamTaskStatus.PENDING && state.tasks.any { it.id == task.id }) {
                            "updated"
                        } else task.status.name.lowercase(),
                        next.members.firstOrNull { it.id == task.ownerId }?.name,
                    )
                }
                TEAM_MESSAGE_QUEUED -> decodeMessage(event.data).let { message ->
                    LocalAgentTeamActivityUiState(
                        event.sequence, "message", truncateWithoutSplittingSurrogatePair(message.content, 180), "queued",
                        next.members.firstOrNull { it.id == message.targetId }?.name,
                    )
                }
                TEAM_MESSAGE_DELIVERED -> LocalAgentTeamActivityUiState(
                    event.sequence, "message", "", "delivered",
                    next.members.firstOrNull {
                        it.id == event.data["targetId"]?.jsonPrimitive?.contentOrNull
                    }?.name,
                )
                else -> null
            }
            if (activity == null) next else next.copy(
                activities = (state.activities + activity).takeLast(64),
            )
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
        require(byName == null) { "TEAM_MEMBER_NAME_CONFLICT：" + next.name }
        val previous = members.firstOrNull { it.id == next.id }
        if (previous == null) {
            require(next.phase in setOf(LocalTeamMemberPhase.CREATED, LocalTeamMemberPhase.PROVISIONING)) {
                "TEAM_MEMBER_MUST_CREATE_OR_PROVISION_FIRST：" + next.id
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
        val allowed = when (previous.phase) {
            LocalTeamMemberPhase.CREATED -> setOf(
                LocalTeamMemberPhase.PROVISIONING,
                LocalTeamMemberPhase.DISABLED,
                LocalTeamMemberPhase.DISMISSED,
            )
            LocalTeamMemberPhase.PROVISIONING -> setOf(
                LocalTeamMemberPhase.ACTIVE,
                LocalTeamMemberPhase.FAILED,
            )
            LocalTeamMemberPhase.ACTIVE -> setOf(
                LocalTeamMemberPhase.DISABLED,
                LocalTeamMemberPhase.DISMISSED,
                LocalTeamMemberPhase.FAILED,
            )
            LocalTeamMemberPhase.DISABLED -> setOf(
                LocalTeamMemberPhase.PROVISIONING,
                LocalTeamMemberPhase.DISMISSED,
            )
            LocalTeamMemberPhase.FAILED -> setOf(LocalTeamMemberPhase.DISMISSED)
            LocalTeamMemberPhase.DISMISSED -> emptySet()
        }
        require(next.phase in allowed) {
            "TEAM_MEMBER_PHASE_INVALID：" + previous.phase + " -> " + next.phase
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
            require(
                tasks.none { other ->
                    other.id != next.id &&
                        other.status == LocalTeamTaskStatus.IN_PROGRESS &&
                        other.ownerId == next.ownerId
                },
            ) {
                "TEAM_MEMBER_TASK_BUSY：" + next.ownerId
            }
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

    private fun buildTeamInstructions(name: String, description: String): String =
        buildString {
            append("你是 Team teammate：$name。")
            if (description.isNotBlank()) append("职责：${description.trim()}。")
            append("\n完成任务后给出可核验结论。Team Lead 后续可能通过 durable mailbox 追加消息。")
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
        const val TEAM_MESSAGE_DISCARDED = "team/message/discarded"
        const val TEAM_EVENT_VERSION = 2
        // Official V2 whole-value protocol remains at version 4; local activity projection adds display state.
        const val OFFICIAL_TEAM_PROJECTION_STATE_VERSION = 4
        private const val LOCAL_TEAM_PROJECTION_STATE_VERSION = 5
        val TEAM_EVENTS = setOf(
            TEAM_MEMBER_EVENT,
            TEAM_TASK_EVENT,
            TEAM_MESSAGE_QUEUED,
            TEAM_MESSAGE_DELIVERED,
            TEAM_MESSAGE_DISCARDED,
        )
        val TOOL_NAMES = setOf(
            "team_members",
            "team_member_status",
            "team_create_member",
            "team_start_member",
            "team_spawn",
            "team_send_message",
            "team_messages",
            "team_wait_for_message",
            "team_task_create",
            "team_task_get",
            "team_task_list",
            "team_task_update",
            "team_interrupt",
            "team_disable_member",
            "team_dismiss_member",
            "team_stop_all",
            "team_wait",
        )
        internal fun isTeamJobId(jobId: String): Boolean =
            jobId.startsWith(TEAM_JOB_ID_PREFIX)

        private val MUTATING_TOOL_NAMES = setOf(
            "team_create_member",
            "team_start_member",
            "team_spawn",
            "team_send_message",
            "team_task_create",
            "team_task_update",
            "team_interrupt",
            "team_disable_member",
            "team_dismiss_member",
            "team_stop_all",
        )
        private const val TEAM_MEMBER_ID_PREFIX = "member-"
        private const val TEAM_JOB_ID_PREFIX = "job-team-"
        private const val LOCAL_SUBAGENT_PROVIDER = "local-subagent"
        private const val MAX_TEAMMATES = 16
        private const val MAX_TEAM_MEMBER_HISTORY = 256
        private const val MAX_NAME_CHARS = 48
        private const val MAX_SUBJECT_CHARS = 240
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private const val MAX_TASK_CHARS = 16_000
        private const val MAX_MESSAGE_BYTES = 65_536
        private const val MAX_PENDING_MESSAGES_PER_MEMBER = 64
        private const val MAX_TASKS = 256
        private const val MAX_TEAM_TASK_HISTORY = 2_048
        private const val MAX_ERROR_CHARS = 1_000
        private const val MAX_BLOCKERS = 32
        private const val MAX_WRITE_SCOPES = 32
        private const val MAX_SCOPE_WARNINGS = 16
        private const val MAX_PROJECTION_BATCH = 512
        private const val DEFAULT_AGENT_MESSAGE_LIMIT = 20
        private const val MAX_AGENT_MESSAGE_LIMIT = 100
        private const val MAX_RENDERED_MESSAGE_CHARS = 8_000
        private const val AGENT_MESSAGE_SCAN_PAGE = 160
        private const val MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES = 16
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

private fun JsonObject.optionalTeamContext(): LocalTeamMemberContext =
    when (optionalTeamString("context")?.lowercase()) {
        null, "", "fresh" -> LocalTeamMemberContext.FRESH
        "fork" -> LocalTeamMemberContext.FORK
        else -> error("TEAM_MEMBER_CONTEXT_INVALID：context 仅支持 fresh / fork")
    }

private fun JsonObject.teamStringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()

private fun JsonObject.teamStringArrayOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
