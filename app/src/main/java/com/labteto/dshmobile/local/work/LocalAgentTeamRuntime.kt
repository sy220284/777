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
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_ACKNOWLEDGED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_REVIEW_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_SHUTDOWN_RESPONSE
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
import com.labteto.dshmobile.local.agent.bindLocalAgentRole
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
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
        job.pendingMessageCount > 0 || job.status in setOf("running", "stopping", "waiting_slice")
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
    /** Versioned role name; absent V2 events imply the historical general-purpose member. */
    val role: String = "general",
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
    /** Outbox identity persisted with an automatic Task reservation. */
    val autoDispatchMessageId: String? = null,
    /** USER instruction revision that authorized the current task owner; absent on legacy events. */
    val rootUserRevision: Long? = null,
    /** Persisted proof pointer fixed at the Task complete transition, never a model assertion. */
    val completionResultId: String? = null,
    val completionEvidenceSequence: Long? = null,
)

@Serializable
internal enum class LocalTeamMessageKind(val wireName: String) {
    MESSAGE("message"),
    SHUTDOWN_REQUEST("shutdown_request");
    companion object {
        fun fromWire(raw: String?): LocalTeamMessageKind = when (raw) {
            null, "", "message" -> MESSAGE
            "shutdown_request" -> SHUTDOWN_REQUEST
            else -> error("TEAM_MESSAGE_KIND_INVALID")
        }
    }
}

@Serializable
internal data class LocalTeamMessageSnapshot(
    val id: String,
    val senderId: String,
    val senderName: String,
    val targetId: String,
    val content: String,
    val kind: LocalTeamMessageKind = LocalTeamMessageKind.MESSAGE,
)

@Serializable
internal data class LocalTeamShutdownRequestSnapshot(val requestId: String, val targetId: String)

@Serializable
internal data class LocalTeamShutdownResponseSnapshot(
    val requestId: String,
    val memberId: String,
    val approved: Boolean,
    val reason: String = "",
)

@Serializable
internal data class LocalTeamMessageAckSnapshot(
    val messageId: String,
    val memberId: String,
    val outcome: String,
    val reason: String = "",
)

@Serializable
internal data class LocalTeamTaskReviewSnapshot(
    val taskId: String,
    val taskRevision: Int,
    val taskResultId: String,
    val reviewerId: String,
    val reviewResultId: String,
    val reviewEvidenceSequence: Long,
    val verdict: String,
    val reason: String,
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
    /** Explicit independent reviewer declarations, replayed from the same Team EventLog. */
    val taskReviews: List<LocalTeamTaskReviewSnapshot> = emptyList(),
    val pendingMessages: List<LocalTeamMessageSnapshot> = emptyList(),
    val deliveredMessageIds: List<String> = emptyList(),
    /** Rebuilt from delivery events to authenticate later member acknowledgements. */
    val deliveredMessageTargets: Map<String, String> = emptyMap(),
    val messageAcknowledgements: List<LocalTeamMessageAckSnapshot> = emptyList(),
    val discardedMessageIds: List<String> = emptyList(),
    val shutdownRequests: List<LocalTeamShutdownRequestSnapshot> = emptyList(),
    val shutdownResponses: List<LocalTeamShutdownResponseSnapshot> = emptyList(),
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
        role: String,
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
    private val sendToLead: (String, QueuedAgentInput) -> Boolean = { _, _ -> false },
) {
    // All synchronous Team commands share one commit boundary; suspend starts reserve first.
    private val commandLock = Any()
    private val memberLifecycle by lazy {
        LocalTeamMemberLifecycle(
            jobs, eventLogFor, ::project, commandLock, taskBoard, messageBus,
            startTeammate, sendToTeammate,
        )
    }
    private val messageBus by lazy {
        LocalTeamMessageBus(
            jobs, eventLogFor, ::project, commandLock, sendToTeammate, sendToLead,
            taskBoard, { session, member -> memberLifecycle.appendMember(session, member) },
        )
    }
    private val resultMailbox by lazy {
        LocalTeamResultMailbox(eventLogFor, ::project, verification)
    }
    private val taskBoard by lazy {
        LocalTeamTaskBoard(eventLogFor, ::project, verification, commandLock)
    }
    private val verification by lazy {
        LocalTeamVerificationController(jobs, eventLogFor, ::project, commandLock)
    }
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

    // FEATURE_TREE: {"id":"work.agent-team.member-collaboration","path":"第一层｜产品功能与界面 / 工作功能 / 团队协作 / 真实成员身份与窄授权协作（未合并）","entry":"executeMember","owner":"WORK","test":"app/src/test/java/com/labteto/dshmobile/local/work/LocalAgentTeamRuntimeTest.kt#trustedMembersSendPeerLeadAndReadOwnDurableMailbox"}
    internal suspend fun executeMember(sessionId: String, jobId: String, call: LocalToolCall): String {
        val caller = currentCoroutineContext()[com.labteto.dshmobile.harness.agent.AgentRunExecutionContext]
            ?: error("TEAM_CALLER_REQUIRED：缺少可信运行身份")
        require(caller.identity.sessionId == sessionId && caller.identity.slotId == "child:${localPersistentSubagentId(jobId)}") {
            "TEAM_CALLER_MISMATCH：运行身份与成员任务不一致"
        }
        awaitProjection(sessionId)
        currentCoroutineContext().ensureActive()
        val result = caller.commitCurrent(sessionId) {
            synchronized(commandLock) {
                val state = project(sessionId)
                val member = state.members.singleOrNull {
                    it.jobId == jobId && it.phase == LocalTeamMemberPhase.ACTIVE
                } ?: error("TEAM_CALLER_STALE：成员运行已失效")
                require(call.name in LocalAgentTeamContract.MEMBER_TOOL_NAMES) { "TEAM_MEMBER_PERMISSION_DENIED" }
                val args = call.arguments
                when (call.name) {
                    "team_send_message" -> messageBus.sendMessage(sessionId, args.requiredTeamString("target"),
                        args.requiredTeamString("message"), member)
                    "team_ack_message" -> messageBus.acknowledgeMessage(
                        sessionId = sessionId,
                        member = member,
                        messageId = args.requiredTeamString("message_id"),
                        outcome = args.requiredTeamString("outcome"),
                        reason = args.optionalTeamString("reason").orEmpty(),
                    )
                    "team_respond_shutdown" -> messageBus.respondShutdown(
                        sessionId = sessionId,
                        member = member,
                        requestId = args.requiredTeamString("request_id"),
                        approved = args["approve"]?.jsonPrimitive?.booleanOrNull
                            ?: error("TEAM_SHUTDOWN_APPROVE_REQUIRED"),
                        reason = args.optionalTeamString("reason").orEmpty(),
                    )
                    "team_messages" -> {
                        require(args.optionalTeamString("target").let { it == null || it == member.name }) {
                            "TEAM_MEMBER_PERMISSION_DENIED：只能读取自己的邮箱"
                        }
                        renderMailbox(sessionId, member.id, args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                            (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_AGENT_MESSAGE_LIMIT).coerceIn(1, MAX_AGENT_MESSAGE_LIMIT)) + "\n" + renderAgentMessages(sessionId, member.name,
                            args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                            (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_AGENT_MESSAGE_LIMIT).coerceIn(1, MAX_AGENT_MESSAGE_LIMIT))
                    }
                    "team_task_list" -> state.tasks.filter {
                        it.ownerId == member.id || (it.ownerId == null && taskBoard.isReady(it, state.tasks))
                    }.joinToString("\n") { taskBoard.renderTaskView(it, state) }
                    "team_task_get" -> {
                        val task = state.tasks.singleOrNull { it.id == args.requiredTeamString("task_id") }
                            ?: error("TEAM_TASK_NOT_FOUND")
                        require(task.ownerId == member.id || (task.ownerId == null && taskBoard.isReady(task, state.tasks))) {
                            "TEAM_MEMBER_PERMISSION_DENIED"
                        }
                        taskBoard.renderTaskView(task, state)
                    }
                    "team_task_update" -> {
                        val task = state.tasks.singleOrNull { it.id == args.requiredTeamString("task_id") }
                            ?: error("TEAM_TASK_NOT_FOUND")
                        val action = args.requiredTeamString("action").trim().lowercase()
                        require(action in setOf("claim", "release", "complete")) { "TEAM_MEMBER_PERMISSION_DENIED" }
                        if (action == "claim" && task.rootUserRevision != null) {
                            require(task.rootUserRevision == workflowUserRevision(eventLogFor(sessionId))) {
                                "TEAM_TASK_ROOT_REAUTHORIZATION_REQUIRED：请主代理重新授权旧任务"
                            }
                        }
                        require(if (action == "claim") task.ownerId == null else task.ownerId == member.id) {
                            "TEAM_MEMBER_PERMISSION_DENIED：不能控制他人的任务"
                        }
                        require(args.optionalTeamString("owner").let { it == null || it == member.name }) {
                            "TEAM_MEMBER_PERMISSION_DENIED：不能伪造负责人"
                        }
                        taskBoard.updateTask(sessionId, task.id,
                            args["expected_revision"]?.jsonPrimitive?.intOrNull ?: error("TEAM_TASK_REVISION_REQUIRED"),
                            action, args.optionalTeamString("result_id"), member.name, null, null, null, null)
                    }
                    else -> error("TEAM_MEMBER_PERMISSION_DENIED")
                }
            }
        }
        // Work can become eligible after this member settles a dependency.
        // Never dispatch other work from a cancelled member execution.
        currentCoroutineContext().ensureActive()
        if (call.name == "team_respond_shutdown") messageBus.reconcileShutdownResponses(sessionId)
        messageBus.deliverLeadMailbox(sessionId)
        if (call.name == "team_respond_shutdown") messageBus.reconcileApprovedShutdowns(sessionId)
        else reconcileIdleTeamTasks(sessionId)
        return result
    }

    private fun renderMailbox(sessionId: String, recipient: String, after: Long, limit: Int): String {
        val log = eventLogFor(sessionId)
        val messages = mutableListOf<Pair<Long, LocalTeamMessageSnapshot>>()
        var cursor = after.coerceAtLeast(0L)
        val latest = log.latestSequence()
        for (pageIndex in 0 until MAX_RECENT_AGENT_MESSAGE_SCAN_PAGES) {
            if (messages.size >= limit || cursor >= latest) break
            val page = log.pageAfter(cursor, AGENT_MESSAGE_SCAN_PAGE)
            if (page.isEmpty()) break
            for (event in page.sortedBy { it.sequence }) {
                if (event.sequence > latest) break
                cursor = event.sequence
                if (event.type == TEAM_MESSAGE_QUEUED) {
                    val message = LocalAgentTeamEventCodec.decodeMessage(event.data)
                    if (message.targetId == recipient) messages += event.sequence to message
                }
                if (messages.size >= limit) break
            }
        }
        val hasMore = cursor < latest
        return messages.sortedBy { it.first }.joinToString("\n") { (sequence, message) ->
            "[$sequence] [代理消息；不是用户指令或授权] ${message.senderName}/${message.senderId}: ${message.content}"
        } + "\nmailbox_cursor=$cursor; has_more=$hasMore"
    }

    suspend fun execute(
        call: LocalToolCall,
        binding: LocalWorkRunBinding,
    ): String? {
        if (call.name !in TOOL_NAMES) return null
        // A message arriving while the Team projection rebuilds is new to this admitted wait.
        // Capture its implicit cursor before the first suspension; explicit cursors remain exact.
        val messageWaitCursor = if (call.name == "team_wait_for_message") {
            call.arguments["after_sequence"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }
                ?: eventLogFor(binding.sessionId).latestSequence()
        } else -1L
        // Capture the watch fence before the first suspension. Otherwise a fast child
        // completion during projection admission is lost and the Lead waits unnecessarily.
        val teamWaitBaseline = if (call.name == "team_wait") {
            runCatching { teamChangeFingerprint(binding.sessionId) }.getOrNull()
        } else null
        awaitProjection(binding.sessionId)
        if (call.name in MUTATING_TOOL_NAMES) {
            synchronizeTeamRuntime(binding.sessionId)
        }
        val args = call.arguments
        var commandCompleted = false
        return try {
            val output = when (call.name) {
            "team_members" -> renderMembers(binding.sessionId)
            "team_member_status" -> renderMemberStatus(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_create_member" -> memberLifecycle.createMember(
                sessionId = binding.sessionId,
                name = args.requiredTeamString("name"),
                description = args.optionalTeamString("description").orEmpty(),
                displayName = args.optionalTeamString("display_name").orEmpty(),
                context = args.optionalTeamContext(),
                role = args.optionalTeamString("role"),
            )
            "team_start_member" -> memberLifecycle.startMember(
                binding = binding,
                targetName = args.requiredTeamString("target"),
                task = args.requiredTeamString("task"),
                model = args.optionalTeamString("model"),
                maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: binding.aggregateSnapshot().subagentMaxSteps,
                parentCallId = call.id,
                grantedExtensions = args.teamStringArray("allowed_extensions").toSet(),
            )
            "team_spawn" -> memberLifecycle.spawn(
                binding = binding,
                name = args.requiredTeamString("name"),
                description = args.optionalTeamString("description").orEmpty(),
                displayName = args.optionalTeamString("display_name").orEmpty(),
                role = args.optionalTeamString("role"),
                task = args.requiredTeamString("task"),
                model = args.optionalTeamString("model"),
                maxSteps = args["max_steps"]?.jsonPrimitive?.intOrNull
                    ?: binding.aggregateSnapshot().subagentMaxSteps,
                context = args.optionalTeamContext(),
                parentCallId = call.id,
                grantedExtensions = args.teamStringArray("allowed_extensions").toSet(),
            )
            "team_send_message" -> messageBus.sendMessage(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
                message = args.requiredTeamString("message"),
            )
            "team_request_shutdown" -> messageBus.sendMessage(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
                message = args.requiredTeamString("reason"),
                kind = LocalTeamMessageKind.SHUTDOWN_REQUEST,
            )
            "team_message_receipt" -> readLocalTeamMessageReceipt(
                log = eventLogFor(binding.sessionId),
                sessionId = binding.sessionId,
                id = args.requiredTeamString("message_id"),
            ).render()
            "team_messages" -> renderMailbox(binding.sessionId, binding.sessionId,
                args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L, DEFAULT_AGENT_MESSAGE_LIMIT) + "\n" + renderAgentMessages(
                sessionId = binding.sessionId,
                targetName = args.optionalTeamString("target"),
                afterSequence = args["after_sequence"]?.jsonPrimitive?.longOrNull ?: -1L,
                limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_AGENT_MESSAGE_LIMIT)
                    .coerceIn(1, MAX_AGENT_MESSAGE_LIMIT),
            )
            "team_wait_for_message" -> waitForMessage(
                sessionId = binding.sessionId,
                targetName = args.optionalTeamString("target"),
                afterSequence = messageWaitCursor,
                timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
            )
            "team_task_create" -> taskBoard.createTask(
                sessionId = binding.sessionId,
                subject = args.requiredTeamString("subject"),
                description = args.optionalTeamString("description").orEmpty(),
                blockedBy = args.teamStringArray("blocked_by"),
                writeScopes = args.teamStringArray("write_scopes"),
            )
            "team_task_get" -> taskBoard.renderTask(
                sessionId = binding.sessionId,
                id = args.requiredTeamString("task_id"),
                includeDeleted = true,
            )
            "team_task_evidence" -> verification.renderTaskCompletionEvidence(
                binding.sessionId, args.requiredTeamString("task_id"),
            )
            "team_task_review" -> verification.recordIndependentTaskReview(
                sessionId = binding.sessionId,
                taskId = args.requiredTeamString("task_id"),
                expectedRevision = args["expected_revision"]?.jsonPrimitive?.intOrNull
                    ?: error("TEAM_TASK_REVIEW_REVISION_REQUIRED"),
                reviewerName = args.requiredTeamString("reviewer"),
                reviewResultId = args.requiredTeamString("review_result_id"),
            )
            "team_task_list" -> taskBoard.renderTasks(binding.sessionId)
            "team_task_update" -> taskBoard.updateTask(
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
            "team_disable_member" -> memberLifecycle.disableMember(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_dismiss_member" -> memberLifecycle.dismissMember(
                sessionId = binding.sessionId,
                targetName = args.requiredTeamString("target"),
            )
            "team_stop_all" -> stopAllMembers(binding.sessionId)
            "team_wait" -> waitForChange(
                sessionId = binding.sessionId,
                timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000)
                    .coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
                baseline = teamWaitBaseline,
            )
            else -> error("未覆盖的 Agent Team 工具：${call.name}")
            }
            commandCompleted = true
            output
        } finally {
            if (commandCompleted && currentCoroutineContext().isActive &&
                call.name in setOf("team_task_create", "team_task_update", "team_start_member", "team_spawn")) {
                reconcileIdleTeamTasks(binding.sessionId)
            }
            binding.workState.update { current ->
                current.copy(team = uiState(binding.sessionId))
            }
        }
    }

    internal fun uiState(sessionId: String): LocalAgentTeamUiState {
        val state = try { project(sessionId) } catch (_: LocalTeamProjectionRebuilding) {
            return LocalAgentTeamUiState(rebuilding = true)
        }
        val jobsSnapshot = jobs.snapshotInfos()
        val jobsById = jobsSnapshot.associateBy { it.id }
        val activityById = projectLocalAgentTeamActivity(state, jobsSnapshot)
            .members.associateBy(LocalTeamMemberActivityView::memberId)
        val visibleTasks = state.tasks
            .filter { it.status != LocalTeamTaskStatus.DELETED }
            .sortedBy(taskBoard::taskNumber)
        val taskById = visibleTasks.associateBy { it.id }
        val memberById = state.members.associateBy { it.id }
        val memberExecutions = memberExecutionEvidenceByJobId(sessionId, state.members)

        val resultMessages = agentMessages(
            sessionId = sessionId,
            targetName = null,
            limit = MAX_AGENT_MESSAGE_LIMIT,
        )
        val messageCounts = resultMessages
            .groupingBy(LocalTeamAgentMessageSnapshot::memberId)
            .eachCount()
        val members = state.members.map { member ->
            val activity = requireNotNull(activityById[member.id]).activity.name.lowercase()
            val currentTask = visibleTasks.firstOrNull {
                it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
            }
            val execution = memberExecutions[member.jobId] ?: MemberExecutionEvidence(null, null)
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
                hasCurrentTaskResult = currentTask != null && activity == "idle" &&
                    verification.hasFreshTaskResult(sessionId, currentTask, member.jobId),
                executionSteps = execution.usedSteps,
                executionStepLimit = execution.stepLimit,
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
                ready = taskBoard.isReady(task, state.tasks),
                writeConflict = taskBoard.writeScopeWarnings(task, state.tasks).isNotEmpty(),
                revision = task.revision,
                completionResultId = task.completionResultId,
                completionEvidenceSequence = task.completionEvidenceSequence,
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

    /** Preserve the existing event-sourced reassign transition and revision check. */
    internal fun reassignUiTask(
        sessionId: String, taskId: String, expectedRevision: Int, newMemberId: String?,
    ): String = synchronized(commandLock) {
        synchronizeTeamRuntime(sessionId)
        val assigneeName = newMemberId?.let { memberId ->
            project(sessionId).members.singleOrNull {
                it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
            }?.name ?: error("TEAM_MEMBER_NOT_FOUND：目标负责人已退出或不可用")
        }
        taskBoard.updateTask(
            sessionId = sessionId, id = taskId, expectedRevision = expectedRevision,
            action = "reassign", resultId = null, ownerName = assigneeName,
            subject = null, description = null, blockedBy = null, writeScopes = null,
        )
    }

    internal fun outputForUi(sessionId: String, memberId: String, expectedJobId: String): String = synchronized(commandLock) {
        val member = project(sessionId).members.singleOrNull { it.id == memberId && it.jobId == expectedJobId }
            ?: error("TEAM_MEMBER_EXPIRED：助手执行身份已改变")
        jobs.output(member.jobId, sessionId)
    }

    internal fun sendUiMessage(
        sessionId: String,
        memberId: String,
        message: String,
        expectedJobId: String? = null,
    ): String = synchronized(commandLock) {
        synchronizeTeamRuntime(sessionId)
        val member = project(sessionId).members.singleOrNull {
            it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：该助手当前不可接收消息")
        require(expectedJobId == null || member.jobId == expectedJobId) { "TEAM_MEMBER_EXPIRED：助手执行身份已改变" }
        messageBus.sendMessage(sessionId, member.name, message)
    }

    internal fun interruptUiMember(sessionId: String, memberId: String, expectedJobId: String? = null): String = synchronized(commandLock) {
        synchronizeTeamRuntime(sessionId)
        val member = project(sessionId).members.singleOrNull {
            it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：该助手当前不可停止")
        require(expectedJobId == null || member.jobId == expectedJobId) { "TEAM_MEMBER_EXPIRED：助手执行身份已改变" }
        jobs.interruptContinuableAgent(member.jobId, sessionId)
    }

    internal fun interruptAllUi(sessionId: String, expectedJobIds: Set<String>? = null): String = synchronized(commandLock) {
        synchronizeTeamRuntime(sessionId)
        val state = project(sessionId)
        require(expectedJobIds == null || state.members.map { it.jobId }.toSet() == expectedJobIds) { "TEAM_CHANGED：团队成员已改变" }
        val jobStatus = jobs.snapshotInfos().associateBy(LocalJobInfo::id)
        val runningIds = state.members
            .filter { member ->
                member.phase == LocalTeamMemberPhase.ACTIVE &&
                    jobStatus[member.jobId]?.status == "running"
            }
            .mapTo(linkedSetOf(), LocalTeamMemberSnapshot::jobId)
        if (runningIds.isEmpty()) "当前没有正在运行的助手"
        else jobs.interruptContinuableAgents(runningIds, sessionId)
    }

    private fun stopAllMembers(sessionId: String): String =
        interruptAllUi(sessionId)

    fun recoverMailbox(sessionId: String) {
        messageBus.reconcileShutdownResponses(sessionId)
        messageBus.deliverLeadMailbox(sessionId)
        messageBus.reconcileApprovedShutdowns(sessionId)
        // Lifecycle reconciliation remains owned by the Team coordinator. No mailbox
        // implementation may independently change member lifecycle or task reservations.
        memberLifecycle.reconcileProvisioningMembers(sessionId)
        memberLifecycle.reconcileMemberOutcomes(sessionId)
        messageBus.recoverMemberMailbox(sessionId)
        reconcileIdleTeamTasks(sessionId)
        messageBus.recoverMemberMailbox(sessionId)
        messageBus.deliverLeadMailbox(sessionId)
    }

    /**
     * Claim the durable task revision before sending its Team message. A crash after the
     * reservation is repaired from the persisted autoDispatchMessageId on cold recovery.
     */
    private fun reconcileIdleTeamTasks(sessionId: String) = synchronized(commandLock) {
        var state = project(sessionId)
        if (state.failure != null) return@synchronized
        state.tasks.filter {
            it.status == LocalTeamTaskStatus.IN_PROGRESS && it.autoDispatchMessageId != null
        }.forEach { task ->
            state = project(sessionId)
            val member = state.members.singleOrNull {
                it.id == task.ownerId && it.phase == LocalTeamMemberPhase.ACTIVE
            } ?: return@forEach
            ensureAutoTaskMessage(sessionId, member, task)
        }
        while (true) {
            state = project(sessionId)
            val assignment = nextIdleTeamTaskAssignment(
                state, projectLocalAgentTeamActivity(state, jobs.snapshotInfos()),
                currentRootRevision = workflowUserRevision(eventLogFor(sessionId)),
            ) ?: break
            val (member, task) = assignment
            val revision = task.revision + 1
            val identity = "$sessionId:${member.id}:${task.id}:$revision"
            val messageId = "team-msg-" + UUID.nameUUIDFromBytes(
                identity.toByteArray(Charsets.UTF_8),
            ).toString().replace("-", "").take(20)
            val reserved = task.copy(
                revision = revision,
                status = LocalTeamTaskStatus.IN_PROGRESS,
                ownerId = member.id,
                autoDispatchMessageId = messageId,
                rootUserRevision = workflowUserRevision(eventLogFor(sessionId)),
            )
            validateTaskTransition(state.tasks, previous = task, next = reserved)
            taskBoard.appendTask(sessionId, reserved)
            ensureAutoTaskMessage(sessionId, member, reserved)
        }
    }

    private fun ensureAutoTaskMessage(
        sessionId: String,
        member: LocalTeamMemberSnapshot,
        task: LocalTeamTaskSnapshot,
    ) {
        val id = requireNotNull(task.autoDispatchMessageId)
        val state = project(sessionId)
        if (id in state.deliveredMessageIds || id in state.discardedMessageIds) return
        var message = state.pendingMessages.singleOrNull { it.id == id }
        if (message == null) {
            message = LocalTeamMessageSnapshot(
                id = id,
                senderId = sessionId,
                senderName = "lead",
                targetId = member.id,
                content = "团队任务自动分派；task_id=${task.id}；认领版本=${task.revision}。" +
                    "请执行：${task.subject}\n${task.description}\n" +
                    "完成后向 @lead 发送可核验证据，任务结算需要归属正确的新鲜结果 ID。" +
                    "本消息不增加工具或写入权限。",
            )
            messageBus.teamInboxInput(message, sessionId)
            eventLogFor(sessionId).append(TEAM_MESSAGE_QUEUED, message.toEvent(sessionId))
        }
        messageBus.deliverMessage(sessionId, message, member)
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
        val jobInfos = jobs.snapshotInfos()
        val jobStatus = jobInfos.firstOrNull { it.id == member.jobId }?.status
        val activity = projectLocalAgentTeamActivity(state, jobInfos)
            .members.single { it.memberId == member.id }.activity.name.lowercase()
        val execution = memberExecutionEvidence(sessionId, member)
        val currentTask = state.tasks.firstOrNull {
            it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
        }?.subject
        val messages = agentMessages(sessionId, member.name, MAX_AGENT_MESSAGE_LIMIT)
        return buildString {
            append(member.name)
            append(" | phase=")
            append(member.phase.name.lowercase())
            append(" | activity=")
            append(activity)
            append(" | job_status=")
            append(jobStatus ?: "missing")
            execution.usedSteps?.let { steps ->
                append(" | steps=").append(steps)
                execution.stepLimit?.let { append("/").append(it) }
            }
            append(" | id=")
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

    internal fun agentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): List<LocalTeamAgentMessageSnapshot> =
        resultMailbox.agentMessages(sessionId, targetName, limit, afterSequence)

    internal fun scanAgentMessages(
        sessionId: String,
        targetName: String?,
        limit: Int,
        afterSequence: Long = -1L,
    ): LocalTeamResultMailbox.LocalTeamAgentMessagePage =
        resultMailbox.scanAgentMessages(sessionId, targetName, limit, afterSequence)

    private fun renderAgentMessages(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        limit: Int,
    ): String = resultMailbox.renderAgentMessages(sessionId, targetName, afterSequence, limit)

    private suspend fun waitForMessage(
        sessionId: String,
        targetName: String?,
        afterSequence: Long,
        timeoutMs: Int,
    ): String = resultMailbox.waitForMessage(sessionId, targetName, afterSequence, timeoutMs)

    private data class MemberExecutionEvidence(val usedSteps: Int?, val stepLimit: Int?)

    /** A step budget is a resource limit, not a measure of task completion. */
    private fun memberExecutionEvidence(
        sessionId: String,
        member: LocalTeamMemberSnapshot,
    ): MemberExecutionEvidence =
        memberExecutionEvidenceByJobId(sessionId, listOf(member))
            .getValue(member.jobId)

    /**
     * UI projection asks for every member at once: reading each event segment separately for
     * each member causes O(members × history) repeated decompression during long-running teams.
     */
    private fun memberExecutionEvidenceByJobId(
        sessionId: String,
        members: List<LocalTeamMemberSnapshot>,
    ): Map<String, MemberExecutionEvidence> {
        if (members.isEmpty()) return emptyMap()
        val ids = members.filter { it.phase != LocalTeamMemberPhase.CREATED }
            .map(LocalTeamMemberSnapshot::jobId).toSet()
        val keys = ids.flatMap { id -> listOf("checkpoint:$id", "start:$id") }.toSet()
        val events = eventLogFor(sessionId).latestMatchingByKey(
            types = setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT, "subagent/start"),
            requiredKeys = keys,
        ) { event ->
            val id = event.data["background_job_id"]?.jsonPrimitive?.contentOrNull
                ?: return@latestMatchingByKey null
            when (event.type) {
                LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT -> "checkpoint:$id"
                "subagent/start" -> "start:$id"
                else -> null
            }
        }
        return members.associate { member ->
            val checkpoint = events["checkpoint:${member.jobId}"]?.data
            val start = events["start:${member.jobId}"]?.data
            val usedSteps = checkpoint?.get("step")
                ?.jsonPrimitive?.intOrNull?.coerceAtLeast(0)
            val softLimit = checkpoint?.get("soft_step_limit")
                ?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val initialLimit = start?.get("max_steps")
                ?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            member.jobId to if (member.phase == LocalTeamMemberPhase.CREATED) {
                MemberExecutionEvidence(null, null)
            } else {
                MemberExecutionEvidence(usedSteps, softLimit ?: initialLimit)
            }
        }
    }

    private suspend fun waitForChange(
        sessionId: String,
        timeoutMs: Int,
        baseline: LocalTeamWaitSnapshot?,
    ): String {
        val before = baseline ?: teamChangeFingerprint(sessionId)
        // First compare immediately after admission: the child may already have gone idle.
        reconcileIdleTeamTasks(sessionId)
        val first = teamChangeFingerprint(sessionId)
        if (first != before) return renderLocalTeamWaitTransition(before, first)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(WAIT_POLL_MS)
            reconcileIdleTeamTasks(sessionId)
            val next = teamChangeFingerprint(sessionId)
            if (next != before) return renderLocalTeamWaitTransition(before, next)
        }
        return "Team 等待超时：${timeoutMs}ms | event_seq=${before.eventSequence}"
    }

    private fun teamChangeFingerprint(sessionId: String): LocalTeamWaitSnapshot {
        val team = project(sessionId)
        val runtimeJobs = jobs.snapshotInfos()
        // Reuse the authoritative activity projection. Job status is retained only for
        // diagnostics, never persisted as a second member lifecycle state.
        val activity = projectLocalAgentTeamActivity(team, runtimeJobs).members
            .map { member ->
                LocalTeamWaitMemberState(
                    id = member.memberId,
                    name = member.memberName,
                    activity = member.activity.name.lowercase(),
                )
            }.sortedBy(LocalTeamWaitMemberState::id)
        return LocalTeamWaitSnapshot(
            eventSequence = eventLogFor(sessionId).latestSequence(),
            members = activity,
            tasks = team.tasks.filter { it.status != LocalTeamTaskStatus.DELETED }
                .map {
                    LocalTeamWaitTaskState(
                        id = it.id,
                        status = it.status.name.lowercase(),
                        revision = it.revision,
                        ownerId = it.ownerId,
                    )
                }.sortedBy(LocalTeamWaitTaskState::id),
        )
    }

    private fun renderMembers(sessionId: String): String {
        val state = project(sessionId)
        val currentJobs = jobs.snapshotInfos()
        val statuses = currentJobs.associateBy { it.id }
        val activityById = projectLocalAgentTeamActivity(state, currentJobs)
            .members.associateBy(LocalTeamMemberActivityView::memberId)
        val rows = buildList {
            add("lead | active | id=$sessionId")
            state.members.forEach { member ->
                val activity = requireNotNull(activityById[member.id]).activity.name.lowercase()
                val jobStatus = statuses[member.jobId]?.status ?: "missing"
                val execution = memberExecutionEvidence(sessionId, member)
                val messages = agentMessages(
                    sessionId = sessionId,
                    targetName = member.name,
                    limit = MAX_AGENT_MESSAGE_LIMIT,
                ).size
                add(
                    member.name + " | " + member.phase.name.lowercase() +
                        " | activity=" + activity +
                        " | job_status=" + jobStatus +
                        (execution.usedSteps?.let { used ->
                            " | steps=" + used +
                                (execution.stepLimit?.let { "/$it" } ?: "")
                        } ?: "") +
                        " | messages=" + messages +
                        " | id=" + member.id +
                        " | agent=" + member.jobId,
                )
            }
        }
        return rows.joinToString("\n") + state.failure?.let { "\nfailure=$it" }.orEmpty()
    }

    /** One canonical USER epoch for an owned task; stale children must not call tools. */
    internal fun requireCurrentRootAuthorization(sessionId: String, jobId: String) {
        val state = project(sessionId)
        require(state.failure == null) { "TEAM_PROJECTION_INVALID：不能使用损坏的团队授权" }
        val member = state.members.singleOrNull { it.jobId == jobId } ?: return
        // The Job may begin before its provisioning event is promoted to ACTIVE.
        // No task owner can be bound in PROVISIONING, so leave initial admission intact.
        if (member.phase == LocalTeamMemberPhase.PROVISIONING) return
        if (member.phase != LocalTeamMemberPhase.ACTIVE) throw LocalWorkflowSuperseded()
        val log = eventLogFor(sessionId)
        val assigned = state.tasks.singleOrNull {
            it.ownerId == member.id && it.status == LocalTeamTaskStatus.IN_PROGRESS
        }
        if (assigned == null) {
            // A released/reassigned Job may still be running: lack of a current owner is
            // not permission to continue. Consult the existing durable ownership events.
            val previouslyOwned = log.latestMatching(setOf(TEAM_TASK_EVENT)) { data ->
                data["teamId"]?.jsonPrimitive?.contentOrNull == sessionId &&
                    (data["task"] as? JsonObject)?.let { task ->
                        task["ownerId"]?.jsonPrimitive?.contentOrNull == member.id &&
                            task["status"]?.jsonPrimitive?.contentOrNull == "in_progress"
                    } == true
            }?.data?.let(LocalAgentTeamEventCodec::decodeTask) ?: return
            val latest = state.tasks.singleOrNull { it.id == previouslyOwned.id }
            if (latest == null || (latest.status != LocalTeamTaskStatus.COMPLETED &&
                    latest.ownerId != member.id)) throw LocalWorkflowSuperseded()
            return
        }
        val admitted = assigned.rootUserRevision ?: return // Legacy tasks retain their previous semantics.
        if (admitted != workflowUserRevision(log)) throw LocalWorkflowSuperseded()
    }

    internal fun activityProjection(sessionId: String): LocalAgentTeamActivityProjection =
        projectLocalAgentTeamActivity(
            team = project(sessionId),
            jobs = jobs.snapshotInfos(),
        )

    internal suspend fun awaitProjection(sessionId: String) {
        projectionRuntime.awaitReady(sessionId, eventLogFor(sessionId))
    }

    /** Cold-start mailbox reconciliation must not run against a half-rebuilt event projection. */
    internal suspend fun recoverMailboxAfterProjection(sessionId: String) {
        awaitProjection(sessionId)
        recoverMailbox(sessionId)
    }

    internal fun project(sessionId: String): LocalTeamProjection =
        projectionRuntime.project(sessionId, eventLogFor(sessionId))


}
