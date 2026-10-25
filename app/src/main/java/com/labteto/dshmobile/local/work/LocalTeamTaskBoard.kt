package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_TASKS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_SUBJECT_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_DESCRIPTION_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_BLOCKERS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_WRITE_SCOPES
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_SCOPE_WARNINGS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamEventCodec.toEvent
import com.labteto.dshmobile.local.work.LocalAgentTeamReducer.validateTaskTransition

/**
 * Claude teammate TaskUpdate/TaskList task-board semantics + Codex turn/owner admission:
 * the Team task board owns task transitions, dependencies, claims, write-conflict admission,
 * proof attachment and persistence. The shared Team EventLog remains the only state owner.
 */
internal class LocalTeamTaskBoard(
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val project: (String) -> LocalTeamProjection,
    private val verification: LocalTeamVerificationController,
    private val commandLock: Any,
) {
    fun releaseOwnedTasks(sessionId: String, memberId: String) {
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
                        autoDispatchMessageId = null,
                    )
                    validateTaskTransition(currentState.tasks, previous = current, next = released)
                    appendTask(sessionId, released)
                }
            }
        }
    }

    fun createTask(
        sessionId: String,
        subject: String,
        description: String,
        blockedBy: List<String>,
        writeScopes: List<String>,
    ): String {
        return synchronized(commandLock) {
            val state = project(sessionId)
            val activeCount = activeTeamTaskReservations(state.tasks)
            require(activeCount < MAX_TASKS) {
                "TEAM_TASK_LIMIT：当前 $activeCount/$MAX_TASKS 个未完成任务，历史已完成任务不占运行名额"
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

    fun updateTask(
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
                    next.copy(status = LocalTeamTaskStatus.IN_PROGRESS, ownerId = member.id, autoDispatchMessageId = null,
                        rootUserRevision = workflowUserRevision(eventLogFor(sessionId)))
                }
                "release" -> {
                    require(current.status == LocalTeamTaskStatus.IN_PROGRESS) {
                        "TEAM_TASK_STATUS_INVALID：只有 in_progress 任务可以 release"
                    }
                    next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null, autoDispatchMessageId = null)
                }
                "complete" -> {
                    require(current.status == LocalTeamTaskStatus.IN_PROGRESS) {
                        "TEAM_TASK_STATUS_INVALID：只有 in_progress 任务可以 complete"
                    }
                    // A newer USER instruction invalidates evidence from the old task owner epoch.
                    // Legacy records without this field preserve their historical behavior.
                    current.rootUserRevision?.let { admitted ->
                        require(admitted == workflowUserRevision(eventLogFor(sessionId))) {
                            "TEAM_TASK_ROOT_INSTRUCTION_SUPERSEDED：用户指令已变化，请由主代理重新授权"
                        }
                    }
                    val verifiedResultId = resultId?.takeIf(String::isNotBlank)
                        ?: error("TEAM_TASK_RESULT_REQUIRED：complete 必须提供已核验的 result_id")
                    val owner = state.members.singleOrNull { it.id == current.ownerId }
                        ?: error("TEAM_TASK_OWNER_REQUIRED：任务负责人不存在")
                    val evidence = verification.findFreshTaskResult(sessionId, current, owner.jobId, verifiedResultId)
                        ?: error("TEAM_TASK_RESULT_STALE：结果必须属于当前负责人且晚于当前任务 revision")
                    next.copy(
                        status = LocalTeamTaskStatus.COMPLETED, autoDispatchMessageId = null,
                        completionResultId = verifiedResultId,
                        completionEvidenceSequence = evidence.sequence,
                    )
                }
                "reopen" -> {
                    require(current.status == LocalTeamTaskStatus.COMPLETED) {
                        "TEAM_TASK_STATUS_INVALID：只有 completed 任务可以 reopen"
                    }
                    next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null, autoDispatchMessageId = null,
                        rootUserRevision = null,
                        completionResultId = null, completionEvidenceSequence = null)
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
                        next.copy(status = LocalTeamTaskStatus.PENDING, ownerId = null, autoDispatchMessageId = null,
                        rootUserRevision = null)
                    } else {
                        require(dependenciesReady(current, state.tasks)) {
                            "TEAM_TASK_BLOCKED：依赖尚未完成"
                        }
                        val member = resolveOwner(state, requestedOwner)
                        next.copy(status = LocalTeamTaskStatus.IN_PROGRESS, ownerId = member.id, autoDispatchMessageId = null,
                        rootUserRevision = workflowUserRevision(eventLogFor(sessionId)))
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
                    next.copy(status = LocalTeamTaskStatus.DELETED, ownerId = null, autoDispatchMessageId = null,
                        rootUserRevision = null)
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
            if (next.status == LocalTeamTaskStatus.IN_PROGRESS) {
                val conflicts = writeScopeWarnings(next, state.tasks)
                require(conflicts.isEmpty()) {
                    "TEAM_WRITE_SCOPE_CONFLICT：当前任务与其他运行中任务的写入范围重叠：" +
                        conflicts.joinToString("；") + "。请等对方完成或重新划分写入范围。"
                }
            }
            validateTaskTransition(state.tasks, previous = current, next = next)
            appendTask(sessionId, next)
            return renderTaskView(next, project(sessionId))
        }
    }

    fun renderTasks(sessionId: String): String {
        val state = project(sessionId)
        val visible = state.tasks.filter { it.status != LocalTeamTaskStatus.DELETED }
        return if (visible.isEmpty()) "Team 任务板为空"
        else visible.sortedBy(::taskNumber).joinToString("\n") { renderTaskView(it, state) }
    }

    fun renderTask(sessionId: String, id: String, includeDeleted: Boolean): String {
        val state = project(sessionId)
        val task = state.tasks.singleOrNull { it.id == id }
            ?: error("TEAM_TASK_NOT_FOUND：任务不存在：$id")
        if (!includeDeleted && task.status == LocalTeamTaskStatus.DELETED) {
            error("TEAM_TASK_NOT_FOUND：任务不存在：$id")
        }
        return renderTaskView(task, state)
    }

    fun renderTaskView(task: LocalTeamTaskSnapshot, state: LocalTeamProjection): String {
        val owner = task.ownerId?.let { id -> state.members.firstOrNull { it.id == id }?.name }
        val ready = isReady(task, state.tasks)
        val warnings = writeScopeWarnings(task, state.tasks)
        return buildString {
            append("${task.id} r${task.revision} [${task.status.name.lowercase()}] ")
            append(task.subject)
            append(" | ready=$ready")
            owner?.let { append(" | owner=$it") }
            task.completionResultId?.let { append(" | completion_result_id=$it") }
            task.completionEvidenceSequence?.let { append(" | completion_evidence_seq=$it") }
            if (task.blockedBy.isNotEmpty()) append(" | blockedBy=${task.blockedBy.joinToString(",")}")
            if (task.writeScopes.isNotEmpty()) append(" | writeScopes=${task.writeScopes.joinToString(",")}")
            if (warnings.isNotEmpty()) append(" | warnings=${warnings.joinToString(";")}")
            append(" | workspace=shared")
            append(" | write_execution=serialized")
            if (task.writeScopes.isNotEmpty()) {
                append(" | scope_enforcement=tool_time_native_path_guard | opaque_mutation=denied | claim_conflict=enforced | file_isolation=none")
            }
            if (task.status == LocalTeamTaskStatus.IN_PROGRESS &&
                state.tasks.any { other ->
                    other.id != task.id && other.status == LocalTeamTaskStatus.IN_PROGRESS &&
                        (task.writeScopes.isEmpty() || other.writeScopes.isEmpty())
                }
            ) {
                append(" | note=parallel_task_scope_unknown; execution_mutex_guards_writes")
            }
        }
    }

    private fun resolveOwner(state: LocalTeamProjection, ownerName: String?): LocalTeamMemberSnapshot {
        val name = ownerName?.trim()?.takeIf(String::isNotBlank)
            ?: error("TEAM_TASK_OWNER_REQUIRED：claim 必须指定 owner")
        return state.members.singleOrNull {
            it.name == name && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$name")
    }

    fun appendTask(sessionId: String, task: LocalTeamTaskSnapshot) {
        synchronized(commandLock) {
            eventLogFor(sessionId).append(TEAM_TASK_EVENT, task.toEvent(sessionId))
        }
    }

    fun dependenciesReady(
        task: LocalTeamTaskSnapshot,
        tasks: List<LocalTeamTaskSnapshot>,
    ): Boolean {
        val byId = tasks.associateBy(LocalTeamTaskSnapshot::id)
        return task.blockedBy.all { byId[it]?.status == LocalTeamTaskStatus.COMPLETED }
    }

    fun isReady(task: LocalTeamTaskSnapshot, tasks: List<LocalTeamTaskSnapshot>): Boolean =
        task.status == LocalTeamTaskStatus.PENDING && dependenciesReady(task, tasks)

    fun writeScopeWarnings(
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
        localAgentWriteScopeOverlaps(left, right)

    private fun normalizeWriteScopes(values: List<String>): List<String> =
        normalizeLocalAgentWriteScopes(values, MAX_WRITE_SCOPES)

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

    fun taskNumber(task: LocalTeamTaskSnapshot): Int =
        task.id.removePrefix("task-").toIntOrNull() ?: Int.MAX_VALUE

}

/** Shared size/required-field validation, not a second task implementation. */
internal fun boundedTeamText(
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
