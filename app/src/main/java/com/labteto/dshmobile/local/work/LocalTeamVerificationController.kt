package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.jobs.LocalJobManager
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.MAX_ERROR_CHARS
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_TASK_REVIEW_EVENT
import com.labteto.dshmobile.local.work.LocalAgentTeamContract.TEAM_EVENT_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One evidence/verifier domain for Team tasks, separate from Team membership and messaging.
 * Codex agent/control/completion.rs separates terminal notification from acceptance;
 * Claude verificationAgent.ts independently rechecks a result instead of trusting its author.
 * Existing Session EventLog remains the sole source of persisted completion and review facts.
 */
internal class LocalTeamVerificationController(
    private val jobs: LocalJobManager,
    private val eventLogFor: (String) -> LocalSessionEventLog,
    private val project: (String) -> LocalTeamProjection,
    private val commandLock: Any,
) {
    /**
     * A reviewer is a distinct live verify/review Agent with an actual checkpointed result.
     * The verdict must be present in that Agent's own structured model output. An event-backed
     * reviewer declaration still does not prove that tests passed or artifacts are correct.
     */
    fun recordIndependentTaskReview(
        sessionId: String,
        taskId: String,
        expectedRevision: Int,
        reviewerName: String,
        reviewResultId: String,
    ): String = synchronized(commandLock) {
        val state = project(sessionId)
        require(state.failure == null) { "TEAM_PROJECTION_INVALID" }
        val task = state.tasks.singleOrNull { it.id == taskId }
            ?: error("TEAM_TASK_REVIEW_TASK_NOT_FOUND")
        require(task.status == LocalTeamTaskStatus.COMPLETED &&
            task.revision == expectedRevision && task.completionResultId != null &&
            task.completionEvidenceSequence != null) { "TEAM_TASK_REVIEW_STALE" }
        require(state.taskReviews.none { it.taskId == taskId && it.taskRevision == expectedRevision }) {
            "TEAM_TASK_REVIEW_DUPLICATE"
        }
        val reviewer = state.members.singleOrNull { it.name == reviewerName || it.id == reviewerName }
            ?: error("TEAM_TASK_REVIEW_MEMBER_NOT_FOUND")
        require(reviewer.id != task.ownerId && reviewer.phase == LocalTeamMemberPhase.ACTIVE &&
            reviewer.role in setOf("verify", "review")) {
            "TEAM_TASK_REVIEW_INDEPENDENT_MEMBER_REQUIRED"
        }
        require(jobs.snapshotInfos().any {
            it.id == reviewer.jobId && it.ownerSessionId == sessionId && it.isAgent
        }) { "TEAM_TASK_REVIEW_AGENT_NOT_FOUND" }
        val cleanResultId = reviewResultId.trim()
        require(cleanResultId.isNotBlank()) { "TEAM_TASK_REVIEW_RESULT_REQUIRED" }
        val evidence = findFreshTaskResult(sessionId, task, reviewer.jobId, cleanResultId)
            ?: error("TEAM_TASK_REVIEW_RESULT_STALE")
        require(evidence.sequence > task.completionEvidenceSequence!!) {
            "TEAM_TASK_REVIEW_EVIDENCE_ORDER_INVALID"
        }
        val rawOutput = evidence.data["terminal_output"]?.jsonPrimitive?.contentOrNull
            ?: error("TEAM_TASK_REVIEW_OUTPUT_MISSING")
        val declaration = runCatching {
            Json.parseToJsonElement(rawOutput) as? JsonObject
        }.getOrNull() ?: error("TEAM_TASK_REVIEW_STRUCTURED_OUTPUT_REQUIRED")
        require(declaration["task_id"]?.jsonPrimitive?.contentOrNull == task.id &&
            declaration["reviewed_result_id"]?.jsonPrimitive?.contentOrNull == task.completionResultId) {
            "TEAM_TASK_REVIEW_TARGET_MISMATCH"
        }
        val verdict = declaration["verdict"]?.jsonPrimitive?.contentOrNull
        require(verdict in setOf("approve", "reject")) { "TEAM_TASK_REVIEW_VERDICT_INVALID" }
        val reason = declaration["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        require(reason.isNotBlank() && reason.toByteArray(Charsets.UTF_8).size <= MAX_ERROR_CHARS) {
            "TEAM_TASK_REVIEW_REASON_REQUIRED"
        }
        val log = eventLogFor(sessionId)
        log.append(TEAM_TASK_REVIEW_EVENT, buildJsonObject {
            put("version", TEAM_EVENT_VERSION)
            put("teamId", sessionId)
            put("review", buildJsonObject {
                put("taskId", task.id)
                put("taskRevision", task.revision)
                put("taskResultId", task.completionResultId)
                put("reviewerId", reviewer.id)
                put("reviewResultId", cleanResultId)
                put("reviewEvidenceSequence", evidence.sequence)
                put("verdict", verdict)
                put("reason", reason)
            })
        })
        "review_recorded=true\ntask_id=$taskId\nreviewer=${reviewer.name}" +
            "\nreviewer_declared_verdict=$verdict\nreview_result_id=$cleanResultId" +
            "\nreview_checkpoint_sequence=${evidence.sequence}" +
            "\nindependent_identity=true\nartifact_verified=false\ngoal_accepted=false"
    }

    /**
     * Revalidate the reviewer event's actual source checkpoint on every read.
     * A structurally valid EventLog entry alone is not independent review evidence.
     */
    private fun hasValidReviewerCheckpoint(
        log: LocalSessionEventLog,
        task: LocalTeamTaskSnapshot,
        review: LocalTeamTaskReviewSnapshot,
        state: LocalTeamProjection,
    ): Boolean {
        val reviewer = state.members.singleOrNull { it.id == review.reviewerId } ?: return false
        if (reviewer.id == task.ownerId || reviewer.role !in setOf("verify", "review")) return false
        if (review.taskId != task.id || review.taskRevision != task.revision ||
            review.taskResultId != task.completionResultId ||
            review.reviewEvidenceSequence <= (task.completionEvidenceSequence ?: return false)) return false
        val seq = review.reviewEvidenceSequence
        val event = log.pageBeforeNewestFirst(
            sequenceExclusive = if (seq == Long.MAX_VALUE) Long.MAX_VALUE else seq + 1L, limit = 1,
        ).singleOrNull() ?: return false
        if (event.sequence != seq || event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT ||
            event.data["background_job_id"]?.jsonPrimitive?.contentOrNull != reviewer.jobId ||
            resultIdentity(event.data) != review.reviewResultId ||
            !isFirstResultCheckpoint(log, event)) return false
        val output = event.data["terminal_output"]?.jsonPrimitive?.contentOrNull ?: return false
        val declared = runCatching { Json.parseToJsonElement(output) as? JsonObject }
            .getOrNull() ?: return false
        return declared["task_id"]?.jsonPrimitive?.contentOrNull == task.id &&
            declared["reviewed_result_id"]?.jsonPrimitive?.contentOrNull == task.completionResultId &&
            declared["verdict"]?.jsonPrimitive?.contentOrNull == review.verdict &&
            declared["reason"]?.jsonPrimitive?.contentOrNull?.trim() == review.reason
    }

    /**
     * Recheck the persisted proof pointer from the original Session EventLog.
     * This validates event provenance and freshness, not artifact content or Goal correctness.
     */
    /**
     * This is a replay-checked review verdict, NOT an objective artifact/test acceptance.
     * A live Work projection can use it as a Goal blocker without inventing a new ledger.
     */
    fun reviewStatus(sessionId: String, task: LocalTeamTaskSnapshot, state: LocalTeamProjection): String {
        if (task.status != LocalTeamTaskStatus.COMPLETED) return "not_applicable"
        val resultId = task.completionResultId ?: return "legacy_unknown"
        val seq = task.completionEvidenceSequence ?: return "evidence_invalid"
        val log = eventLogFor(sessionId)
        val owner = state.members.singleOrNull { it.id == task.ownerId } ?: return "evidence_invalid"
        val event = log.pageBeforeNewestFirst(
            sequenceExclusive = if (seq == Long.MAX_VALUE) Long.MAX_VALUE else seq + 1L,
            limit = 1,
        ).singleOrNull() ?: return "evidence_invalid"
        if (event.sequence != seq || event.type != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT ||
            event.data["background_job_id"]?.jsonPrimitive?.contentOrNull != owner.jobId ||
            resultIdentity(event.data) != resultId || !isFirstResultCheckpoint(log, event)) {
            return "evidence_invalid"
        }
        val prior = log.latestMatching(setOf(TEAM_TASK_EVENT), seq) { data ->
            val raw = data["task"] as? JsonObject ?: return@latestMatching false
            raw["id"]?.jsonPrimitive?.contentOrNull == task.id &&
                raw["status"]?.jsonPrimitive?.contentOrNull == "in_progress" &&
                raw["ownerId"]?.jsonPrimitive?.contentOrNull == task.ownerId &&
                (raw["revision"]?.jsonPrimitive?.intOrNull ?: -1) < task.revision
        } ?: return "evidence_invalid"
        if (prior.sequence >= seq) return "evidence_invalid"
        val admitted = task.rootUserRevision
        if (admitted != null && admitted != workflowUserRevision(log)) return "root_superseded"
        val review = state.taskReviews.singleOrNull {
            it.taskId == task.id && it.taskRevision == task.revision &&
                it.taskResultId == resultId
        } ?: return "pending"
        if (!hasValidReviewerCheckpoint(log, task, review, state)) return "review_invalid"
        return if (review.verdict == "approve") "reviewer_approved" else "reviewer_rejected"
    }

    fun renderTaskCompletionEvidence(sessionId: String, taskId: String): String {
        val state = project(sessionId)
        require(state.failure == null) { "TEAM_PROJECTION_INVALID" }
        val task = state.tasks.singleOrNull { it.id == taskId }
            ?: error("TEAM_TASK_NOT_FOUND")
        require(task.status == LocalTeamTaskStatus.COMPLETED) {
            "TEAM_TASK_EVIDENCE_NOT_COMPLETED"
        }
        val resultId = task.completionResultId
        val seq = task.completionEvidenceSequence
        if (resultId == null || seq == null) {
            return "task_id=$taskId\nevidence_state=legacy_unknown" +
                "\ncontent_verified=false\ngoal_accepted=false"
        }
        val log = eventLogFor(sessionId)
        val member = state.members.singleOrNull { it.id == task.ownerId }
        val candidate = log.pageBeforeNewestFirst(
            sequenceExclusive = if (seq == Long.MAX_VALUE) Long.MAX_VALUE else seq + 1L,
            limit = 1,
        ).singleOrNull()
        val checkpointValid = candidate?.let { event ->
            event.sequence == seq &&
                event.type == LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT &&
                event.data["background_job_id"]?.jsonPrimitive?.contentOrNull == member?.jobId &&
                resultIdentity(event.data) == resultId &&
                !event.data["terminal_output"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                isFirstResultCheckpoint(log, event)
        } == true
        val priorOwnedTask = if (checkpointValid) log.latestMatching(
            setOf(TEAM_TASK_EVENT), seq,
        ) { data ->
            (data["task"] as? JsonObject)?.let { raw ->
                raw["id"]?.jsonPrimitive?.contentOrNull == taskId &&
                    raw["status"]?.jsonPrimitive?.contentOrNull == "in_progress" &&
                    raw["ownerId"]?.jsonPrimitive?.contentOrNull == task.ownerId
            } == true
        } else null
        val bindingValid = checkpointValid && priorOwnedTask != null &&
            priorOwnedTask.sequence < seq
        val rootCurrent = task.rootUserRevision?.let {
            it == workflowUserRevision(log)
        }
        val review = state.taskReviews.singleOrNull {
            it.taskId == taskId && it.taskRevision == task.revision &&
                it.taskResultId == resultId
        }
        val reviewBindingValid = review?.let { hasValidReviewerCheckpoint(log, task, it, state) } ?: false
        return buildString {
            append("task_id=").append(taskId)
            append("\nresult_id=").append(resultId)
            append("\nevidence_sequence=").append(seq)
            append("\ncheckpoint_binding_valid=").append(bindingValid)
            append("\nowner_job=").append(member?.jobId ?: "missing")
            append("\nroot_instruction_current=").append(rootCurrent ?: "legacy_unknown")
            append("\nindependent_reviewer_reported=").append(review != null)
            append("\nreview_evidence_binding_valid=").append(reviewBindingValid)
            review?.let {
                append("\nreviewer_id=").append(it.reviewerId)
                append("\nreviewer_declared_verdict=").append(it.verdict)
                append("\nreview_result_id=").append(it.reviewResultId)
                append("\nreview_evidence_sequence=").append(it.reviewEvidenceSequence)
            }
            append("\ncontent_verified=false\ngoal_accepted=false")
        }
    }

    fun hasFreshTaskResult(
        sessionId: String,
        task: LocalTeamTaskSnapshot,
        jobId: String,
        resultId: String? = null,
    ): Boolean = findFreshTaskResult(sessionId, task, jobId, resultId) != null

    /** Return the exact existing durable evidence, not just a Boolean success claim. */
    fun findFreshTaskResult(
        sessionId: String,
        task: LocalTeamTaskSnapshot,
        jobId: String,
        resultId: String? = null,
    ): LocalSessionEventLog.Event? {
        val log = eventLogFor(sessionId)
        val taskSequence = log.latestMatching(setOf(TEAM_TASK_EVENT)) { data ->
            val raw = data["task"] as? JsonObject ?: return@latestMatching false
            raw["id"]?.jsonPrimitive?.contentOrNull == task.id &&
                raw["revision"]?.jsonPrimitive?.intOrNull == task.revision
        }?.sequence ?: return null
        var before = Long.MAX_VALUE
        while (true) {
            val event = log.latestMatching(setOf(LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT), before) { data ->
                data["background_job_id"]?.jsonPrimitive?.contentOrNull == jobId &&
                    (resultId == null || resultIdentity(data) == resultId) &&
                    !data["terminal_output"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            } ?: return null
            if (event.sequence <= taskSequence) return null
            if (isFirstResultCheckpoint(log, event)) return event
            before = event.sequence
        }
    }

    fun resultIdentity(event: LocalSessionEventLog.Event): String =
        resultIdentity(event.data)

    fun resultIdentity(data: JsonObject): String =
        data["result_id"]?.jsonPrimitive?.contentOrNull ?: (
            "legacy-result-" + data["background_job_id"]?.jsonPrimitive?.contentOrNull +
                "-" + data["step"]?.jsonPrimitive?.intOrNull
        )

    fun isFirstResultCheckpoint(log: LocalSessionEventLog, event: LocalSessionEventLog.Event): Boolean {
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

}
