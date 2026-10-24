package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.workflow.TaskGraphTopology
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_BLOCKERS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_DESCRIPTION_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_MESSAGE_BYTES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_PENDING_MESSAGES_PER_MEMBER
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_SUBJECT_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_WRITE_SCOPES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MEMBER_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_DELIVERED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_DISCARDED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_MESSAGE_QUEUED
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_SHUTDOWN_RESPONSE
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.decodeMember
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.decodeMessage
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.decodeTask
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.teamJobId
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.validateTeamEnvelope

internal object LocalAgentTeamReducer {
    fun applyEvent(
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
                        message.targetId == event.data["teamId"]?.jsonPrimitive?.contentOrNull ||
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
                    advanced.copy(
                        pendingMessages = state.pendingMessages + message,
                        shutdownRequests = if (message.kind == LocalTeamMessageKind.SHUTDOWN_REQUEST) {
                            state.shutdownRequests + LocalTeamShutdownRequestSnapshot(message.id, message.targetId)
                        } else state.shutdownRequests,
                    )
                }
                TEAM_SHUTDOWN_RESPONSE -> {
                    validateTeamEnvelope(event.data)
                    val requestId = event.data["requestId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_SHUTDOWN_REQUEST_ID_REQUIRED")
                    val memberId = event.data["memberId"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_SHUTDOWN_MEMBER_ID_REQUIRED")
                    val approved = event.data["approved"]?.jsonPrimitive?.booleanOrNull
                        ?: error("TEAM_SHUTDOWN_APPROVE_REQUIRED")
                    val reason = event.data["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    require(reason.length <= MAX_MESSAGE_BYTES) { "TEAM_SHUTDOWN_REASON_TOO_LARGE" }
                    require(approved || reason.isNotBlank()) { "TEAM_SHUTDOWN_REJECT_REASON_REQUIRED" }
                    require(state.shutdownRequests.any {
                        it.requestId == requestId && it.targetId == memberId
                    }) { "TEAM_SHUTDOWN_REQUEST_TARGET_MISMATCH" }
                    require(requestId in state.deliveredMessageIds) {
                        "TEAM_SHUTDOWN_REQUEST_NOT_DELIVERED"
                    }
                    require(state.members.any {
                        it.id == memberId && it.phase == LocalTeamMemberPhase.ACTIVE
                    }) { "TEAM_SHUTDOWN_MEMBER_NOT_ACTIVE" }
                    require(state.shutdownResponses.none { it.requestId == requestId }) {
                        "TEAM_SHUTDOWN_RESPONSE_DUPLICATE"
                    }
                    advanced.copy(shutdownResponses = state.shutdownResponses +
                        LocalTeamShutdownResponseSnapshot(requestId, memberId, approved, reason))
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
                TEAM_SHUTDOWN_RESPONSE -> LocalAgentTeamActivityUiState(
                    event.sequence, "message", "成员关闭请求已有结构化回复",
                    if (event.data["approved"]?.jsonPrimitive?.booleanOrNull == true) "approved" else "rejected",
                    next.members.firstOrNull {
                        it.id == event.data["memberId"]?.jsonPrimitive?.contentOrNull
                    }?.name,
                )
                TEAM_MESSAGE_DELIVERED -> LocalAgentTeamActivityUiState(
                    event.sequence, "message", "", "delivered",
                    next.members.firstOrNull {
                        it.id == event.data["targetId"]?.jsonPrimitive?.contentOrNull
                    }?.name,
                )
                TEAM_MESSAGE_DISCARDED -> LocalAgentTeamActivityUiState(
                    event.sequence, "message",
                    event.data["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(), "failed",
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
    fun validateMemberTransition(
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

    fun validateTaskTransition(
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
        require(next.autoDispatchMessageId == null ||
            (next.status == LocalTeamTaskStatus.IN_PROGRESS && next.ownerId != null)) {
            "TEAM_TASK_AUTO_DISPATCH_STATE_INVALID"
        }
        if (next.autoDispatchMessageId != null && previous != null) {
            require(
                next.autoDispatchMessageId == previous.autoDispatchMessageId ||
                    (previous.status == LocalTeamTaskStatus.PENDING &&
                        previous.autoDispatchMessageId == null)
            ) { "TEAM_TASK_AUTO_DISPATCH_REPLACED" }
            if (next.autoDispatchMessageId == previous.autoDispatchMessageId) {
                require(next.ownerId == previous.ownerId) { "TEAM_TASK_AUTO_OWNER_CHANGED" }
            }
        }
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
        val active = tasks.filter { it.status != LocalTeamTaskStatus.DELETED }
            .associateBy { it.id }
        return TaskGraphTopology.containsCycle(active.mapValues { (_, task) ->
            task.blockedBy.filter { it in active }
        })
    }

}
