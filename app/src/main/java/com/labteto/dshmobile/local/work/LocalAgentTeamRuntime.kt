package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
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
internal enum class LocalTeamTaskStatus { PENDING, IN_PROGRESS, COMPLETED, DELETED }

internal data class LocalTeamMemberSnapshot(
    val id: String,
    val jobId: String,
    val name: String,
    val description: String,
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
    val failure: String? = null,
    val asOfSequence: Long = -1L,
)

/** Work-owned Agent Team domain. Durable state lives only in the lead Session EventLog. */
internal class LocalAgentTeamRuntime(
    private val jobs: LocalJobManager,
    private val persistentJobs: LocalPersistentJobRecoveryCoordinator,
    private val eventLogFor: (String) -> LocalSessionEventLog,
) {
    private val cacheLock = Any()
    private val projections = mutableMapOf<String, LocalTeamProjection>()

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
    ): String {
        val cleanName = normalizeName(name)
        val before = project(binding.sessionId)
        require(before.members.none { it.name == cleanName && it.phase != LocalTeamMemberPhase.FAILED }) {
            "TEAM_MEMBER_NAME_CONFLICT：成员名称已存在：$cleanName"
        }
        require(before.members.count { it.phase == LocalTeamMemberPhase.ACTIVE } < MAX_TEAMMATES) {
            "TEAM_MEMBER_LIMIT：最多允许 $MAX_TEAMMATES 个 active teammate"
        }
        val snapshot = binding.aggregateSnapshot()
        val start = persistentJobs.startReadonlySubagentResult(
            task = buildTeamTaskPrompt(cleanName, description, task),
            model = model,
            maxSteps = LocalAgentRuntimeLimits.normalizeSubagentSteps(maxSteps),
            virtualScreen = false,
            sessionId = binding.sessionId,
            boundState = snapshot,
            historySnapshot = binding.runHandle.modelHistory::snapshot,
        )
        if (!start.accepted || start.id.isNullOrBlank()) {
            return "[TEAM_MEMBER_START_REJECTED] ${start.message}"
        }
        val jobId = requireNotNull(start.id)
        val memberId = "member-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val provisioning = LocalTeamMemberSnapshot(
            id = memberId,
            jobId = jobId,
            name = cleanName,
            description = description.trim().take(MAX_DESCRIPTION_CHARS),
            phase = LocalTeamMemberPhase.PROVISIONING,
        )
        return try {
            appendMember(binding.sessionId, provisioning)
            appendMember(
                binding.sessionId,
                provisioning.copy(phase = LocalTeamMemberPhase.ACTIVE),
            )
            "teammate 已创建：$cleanName | id=$memberId | agent=$jobId"
        } catch (error: Exception) {
            runCatching { jobs.kill(jobId, binding.sessionId) }
            runCatching {
                appendMember(
                    binding.sessionId,
                    provisioning.copy(
                        phase = LocalTeamMemberPhase.FAILED,
                        error = error.message.orEmpty().take(MAX_ERROR_CHARS),
                    ),
                )
            }
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
        val snapshot = LocalTeamMessageSnapshot(
            id = "team-msg-" + UUID.randomUUID().toString().replace("-", "").take(20),
            senderId = sessionId,
            senderName = "lead",
            targetId = member.id,
            content = message.trim().take(MAX_MESSAGE_CHARS),
        )
        require(snapshot.content.isNotEmpty()) { "TEAM_MESSAGE_REQUIRED：消息不能为空" }
        val log = eventLogFor(sessionId)
        log.append(TEAM_MESSAGE_QUEUED, snapshot.toEvent(sessionId))
        invalidate(sessionId)
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
        val admission = persistentJobs.sendInput(
            agentId = member.jobId,
            input = QueuedAgentInput(
                id = message.id,
                content = framed,
                memoryInput = message.content,
            ),
            sessionId = sessionId,
        )
        if (!admission.accepted) return false
        eventLogFor(sessionId).append(
            TEAM_MESSAGE_DELIVERED,
            buildJsonObject {
                put("team_id", sessionId)
                put("message_id", message.id)
                put("target_id", member.id)
            },
        )
        invalidate(sessionId)
        return true
    }

    fun recoverMailbox(sessionId: String) {
        val state = project(sessionId)
        if (state.pendingMessages.isEmpty()) return
        val members = state.members.associateBy(LocalTeamMemberSnapshot::id)
        state.pendingMessages.forEach { message ->
            val member = members[message.targetId] ?: return@forEach
            if (member.phase != LocalTeamMemberPhase.ACTIVE) return@forEach
            runCatching { deliverMessage(sessionId, message, member) }
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
        val id = nextTaskId(state.tasks)
        val task = LocalTeamTaskSnapshot(
            id = id,
            revision = 1,
            subject = subject.trim().take(MAX_SUBJECT_CHARS),
            description = description.trim().take(MAX_DESCRIPTION_CHARS),
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
            "delete" -> next.copy(status = LocalTeamTaskStatus.DELETED, ownerId = null)
            "set_dependencies" -> next.copy(
                blockedBy = normalizeTaskIds(
                    blockedBy ?: error("TEAM_TASK_DEPENDENCIES_REQUIRED：blocked_by 必填"),
                ),
            )
            "update" -> next.copy(
                subject = subject?.trim()?.take(MAX_SUBJECT_CHARS) ?: current.subject,
                description = description?.trim()?.take(MAX_DESCRIPTION_CHARS)
                    ?: current.description,
                writeScopes = writeScopes?.let(::normalizeWriteScopes) ?: current.writeScopes,
            )
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
        return jobs.kill(member.jobId, sessionId)
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
        val name = ownerName?.trim().takeUnless(String?::isNullOrBlank)
            ?: error("TEAM_TASK_OWNER_REQUIRED：claim 必须指定 owner")
        return state.members.singleOrNull {
            it.name == name && it.phase == LocalTeamMemberPhase.ACTIVE
        } ?: error("TEAM_MEMBER_NOT_FOUND：找不到 active teammate：$name")
    }

    private fun appendMember(sessionId: String, member: LocalTeamMemberSnapshot) {
        val current = project(sessionId)
        validateMemberTransition(current.members, member)
        eventLogFor(sessionId).append(TEAM_MEMBER_EVENT, member.toEvent(sessionId))
        invalidate(sessionId)
    }

    private fun appendTask(sessionId: String, task: LocalTeamTaskSnapshot) {
        eventLogFor(sessionId).append(TEAM_TASK_EVENT, task.toEvent(sessionId))
        invalidate(sessionId)
    }

    internal fun project(sessionId: String): LocalTeamProjection = synchronized(cacheLock) {
        val log = eventLogFor(sessionId)
        var state = projections[sessionId] ?: LocalTeamProjection()
        if (state.asOfSequence > log.latestSequence()) {
            state = LocalTeamProjection()
        }
        val events = log.pageAfter(state.asOfSequence, limit = MAX_PROJECTION_BATCH)
        if (events.isEmpty()) return@synchronized state
        var cursor = state
        var nextStart = state.asOfSequence
        while (true) {
            val page = if (nextStart == state.asOfSequence) events
                else log.pageAfter(nextStart, limit = MAX_PROJECTION_BATCH)
            if (page.isEmpty()) break
            page.forEach { event ->
                cursor = applyEvent(sessionId, cursor, event)
                nextStart = event.sequence
            }
            if (page.size < MAX_PROJECTION_BATCH) break
        }
        projections[sessionId] = cursor
        cursor
    }

    private fun applyEvent(
        sessionId: String,
        state: LocalTeamProjection,
        event: LocalSessionEventLog.Event,
    ): LocalTeamProjection {
        if (event.sequence <= state.asOfSequence) return state
        val advanced = state.copy(asOfSequence = event.sequence)
        if (state.failure != null) return advanced
        if (event.type !in TEAM_EVENTS) return advanced
        if (event.data["team_id"]?.jsonPrimitive?.contentOrNull != sessionId) return advanced
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
                    advanced.copy(
                        tasks = state.tasks.filterNot { it.id == task.id } + task,
                    )
                }
                TEAM_MESSAGE_QUEUED -> {
                    val message = decodeMessage(event.data)
                    val existing = state.pendingMessages.firstOrNull { it.id == message.id }
                    require(existing == null || existing == message) {
                        "TEAM_MESSAGE_ID_CONFLICT：${message.id}"
                    }
                    if (existing != null) advanced
                    else advanced.copy(pendingMessages = state.pendingMessages + message)
                }
                TEAM_MESSAGE_DELIVERED -> {
                    val id = event.data["message_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("TEAM_MESSAGE_DELIVERED 缺少 message_id")
                    advanced.copy(
                        pendingMessages = state.pendingMessages.filterNot { it.id == id },
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
        require(next.id.isNotBlank() && next.jobId.isNotBlank()) { "TEAM_MEMBER_ID_INVALID" }
        require(next.name.isNotBlank()) { "TEAM_MEMBER_NAME_INVALID" }
        val byName = members.firstOrNull { it.name == next.name && it.id != next.id }
        require(byName == null) { "TEAM_MEMBER_NAME_CONFLICT：${next.name}" }
        val previous = members.firstOrNull { it.id == next.id } ?: return
        require(previous.name == next.name && previous.description == next.description) {
            "TEAM_MEMBER_IMMUTABLE_FIELDS_CHANGED：${next.id}"
        }
        require(previous.jobId == next.jobId) { "TEAM_MEMBER_JOB_CHANGED：${next.id}" }
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

    private fun isReady(task: LocalTeamTaskSnapshot, tasks: List<LocalTeamTaskSnapshot>): Boolean {
        if (task.status != LocalTeamTaskStatus.PENDING) return false
        val byId = tasks.associateBy(LocalTeamTaskSnapshot::id)
        return task.blockedBy.all { byId[it]?.status == LocalTeamTaskStatus.COMPLETED }
    }

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

    private fun normalizeWriteScopes(values: List<String>): List<String> = values
        .asSequence()
        .map { it.trim().replace('\\', '/').trim('/') }
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_WRITE_SCOPES)
        .toList()

    private fun normalizeTaskIds(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_BLOCKERS)

    private fun nextTaskId(tasks: List<LocalTeamTaskSnapshot>): String =
        "task-" + ((tasks.maxOfOrNull(::taskNumber) ?: 0) + 1)

    private fun taskNumber(task: LocalTeamTaskSnapshot): Int =
        task.id.removePrefix("task-").toIntOrNull() ?: Int.MAX_VALUE

    private fun normalizeName(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), "-").take(MAX_NAME_CHARS)
        require(clean.matches(Regex("[A-Za-z0-9_-]+"))) {
            "TEAM_MEMBER_NAME_INVALID：名称只允许字母、数字、下划线和短横线"
        }
        return clean
    }

    private fun invalidate(sessionId: String) = synchronized(cacheLock) {
        projections.remove(sessionId)
        Unit
    }

    private fun buildTeamTaskPrompt(name: String, description: String, task: String): String =
        buildString {
            append("你是 Team teammate：$name。")
            if (description.isNotBlank()) append("职责：${description.trim()}。")
            append("\n完成任务后给出可核验结论。Team Lead 后续可能通过 durable mailbox 追加消息。")
            append("\n当前任务：$task")
        }

    private fun LocalTeamMemberSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("team_id", teamId)
        put("id", id)
        put("job_id", jobId)
        put("name", name)
        put("description", description)
        put("phase", phase.name.lowercase())
        error?.let { put("error", it) }
    }

    private fun LocalTeamTaskSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("team_id", teamId)
        put("id", id)
        put("revision", revision)
        put("subject", subject)
        put("description", description)
        put("status", status.name.lowercase())
        ownerId?.let { put("owner_id", it) }
        put("blocked_by", JsonArray(blockedBy.map(::JsonPrimitive)))
        put("write_scopes", JsonArray(writeScopes.map(::JsonPrimitive)))
    }

    private fun LocalTeamMessageSnapshot.toEvent(teamId: String): JsonObject = buildJsonObject {
        put("team_id", teamId)
        put("id", id)
        put("sender_id", senderId)
        put("sender_name", senderName)
        put("target_id", targetId)
        put("content", content)
    }

    private fun decodeMember(data: JsonObject): LocalTeamMemberSnapshot = LocalTeamMemberSnapshot(
        id = data.requiredTeamString("id"),
        jobId = data.requiredTeamString("job_id"),
        name = data.requiredTeamString("name"),
        description = data.optionalTeamString("description").orEmpty(),
        phase = runCatching {
            LocalTeamMemberPhase.valueOf(data.requiredTeamString("phase").uppercase())
        }.getOrElse { error("TEAM_MEMBER_PHASE_INVALID") },
        error = data.optionalTeamString("error"),
    )

    private fun decodeTask(data: JsonObject): LocalTeamTaskSnapshot = LocalTeamTaskSnapshot(
        id = data.requiredTeamString("id"),
        revision = data["revision"]?.jsonPrimitive?.intOrNull
            ?: error("TEAM_TASK_REVISION_REQUIRED"),
        subject = data.requiredTeamString("subject"),
        description = data.optionalTeamString("description").orEmpty(),
        status = runCatching {
            LocalTeamTaskStatus.valueOf(data.requiredTeamString("status").uppercase())
        }.getOrElse { error("TEAM_TASK_STATUS_INVALID") },
        ownerId = data.optionalTeamString("owner_id"),
        blockedBy = data.teamStringArray("blocked_by"),
        writeScopes = data.teamStringArray("write_scopes"),
    )

    private fun decodeMessage(data: JsonObject): LocalTeamMessageSnapshot = LocalTeamMessageSnapshot(
        id = data.requiredTeamString("id"),
        senderId = data.requiredTeamString("sender_id"),
        senderName = data.requiredTeamString("sender_name"),
        targetId = data.requiredTeamString("target_id"),
        content = data.requiredTeamString("content"),
    )

    internal companion object {
        const val TEAM_MEMBER_EVENT = "team/member"
        const val TEAM_TASK_EVENT = "team/task"
        const val TEAM_MESSAGE_QUEUED = "team/message-queued"
        const val TEAM_MESSAGE_DELIVERED = "team/message-delivered"
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
        private const val MAX_TEAMMATES = 8
        private const val MAX_NAME_CHARS = 48
        private const val MAX_SUBJECT_CHARS = 240
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private const val MAX_MESSAGE_CHARS = 8_000
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
