package com.labteto.dshmobile.harness.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The one platform-neutral projection of persisted tool execution facts.
 *
 * Work and SessionRecovery use identical admission/settlement semantics. This
 * reducer creates no new source of truth; it only folds the canonical EventLog.
 * A declared tool is not necessarily admitted. Missing results after actual
 * executor admission and an unknown result remain unsafe to replay.
 */
enum class ToolAttemptState {
    DECLARED, READING, EXTERNAL_PENDING, SETTLED, UNKNOWN_EXTERNAL_EFFECT,
}

class ToolAttemptLedger {
    data class Attempt(
        val id: String,
        val name: String?,
        val step: Int?,
        val goalId: String?,
        val started: Boolean,
        val settled: Boolean,
        val outcomeUncertain: Boolean,
        val executionId: String? = null,
        val verifiedReadOnly: Boolean = false,
        val runId: String? = null,
        val parentRunId: String? = null,
    ) {
        val state: ToolAttemptState get() = when {
            outcomeUncertain -> ToolAttemptState.UNKNOWN_EXTERNAL_EFFECT
            settled -> ToolAttemptState.SETTLED
            started && verifiedReadOnly -> ToolAttemptState.READING
            started -> ToolAttemptState.EXTERNAL_PENDING
            else -> ToolAttemptState.DECLARED
        }
        val unresolvedExternalEffect: Boolean
            get() = state == ToolAttemptState.UNKNOWN_EXTERNAL_EFFECT ||
                state == ToolAttemptState.EXTERNAL_PENDING
    }

    @Serializable
    private data class MutableAttempt(
        val id: String,
        var name: String? = null,
        var step: Int? = null,
        var goalId: String? = null,
        var started: Boolean = false,
        var settled: Boolean = false,
        var uncertain: Boolean = false,
        var toolCallObserved: Boolean = false,
        var admissionObserved: Boolean = false,
        var executionId: String? = null,
        var verifiedReadOnly: Boolean = false,
        var runId: String? = null,
        var parentRunId: String? = null,
    ) {
        fun snapshot() = Attempt(id, name, step, goalId, started, settled, uncertain, executionId,
            verifiedReadOnly, runId, parentRunId)
    }

    // Tool call IDs are only guaranteed by providers within an individual request.
    // Reuse across turns must never settle an earlier, still-unknown mutation.
    private val attempts = mutableListOf<MutableAttempt>()
    private val latestByCallId = mutableMapOf<String, MutableAttempt>()
    private val byExecutionId = mutableMapOf<String, MutableAttempt>()
    // A provider call ID has request scope, not session scope. Once reused,
    // a legacy receipt without execution identity cannot identify its target.
    private val ambiguousLegacyCallIds = mutableSetOf<String>()

    @Serializable
    private data class Checkpoint(
        val attempts: List<MutableAttempt>,
        val ambiguousLegacyCallIds: Set<String>,
    )

    /** Disposable reducer state only: no arguments, outputs, authorization or execution rights. */
    fun checkpoint(): String = Json.encodeToString(Checkpoint.serializer(),
        Checkpoint(attempts, ambiguousLegacyCallIds))

    companion object {
        fun fromCheckpoint(payload: String): ToolAttemptLedger {
            val saved = Json.decodeFromString(Checkpoint.serializer(), payload)
            return ToolAttemptLedger().apply {
                saved.attempts.forEach { attempt ->
                    attempts.add(attempt)
                    latestByCallId[attempt.id] = attempt
                    attempt.executionId?.let { execution ->
                        require(byExecutionId.put(execution, attempt) == null) {
                            "工具投影检查点包含重复执行身份"
                        }
                    }
                }
                ambiguousLegacyCallIds.addAll(saved.ambiguousLegacyCallIds)
            }
        }
    }

    fun observe(event: SessionEvent, stepHint: Int? = null) {
        observe(event.type, event.data, stepHint)
    }

    fun observe(type: String, data: JsonObject, stepHint: Int? = null) {
        fun readString(obj: JsonObject, key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

        fun track(id: String): MutableAttempt =
            latestByCallId.getOrPut(id) {
                MutableAttempt(id).also(attempts::add)
            }

        fun declare(id: String, actualCall: Boolean): MutableAttempt {
            val previous = latestByCallId[id]
            // The assistant can declare a tool just before tool/call; that pair
            // belongs to one attempt. A later call with the same ID creates
            // a fresh record even if the earlier external effect is unresolved.
            val newAttempt = previous == null ||
                (if (actualCall) previous.toolCallObserved
                 else previous.toolCallObserved || previous.started || previous.settled)
            if (newAttempt && previous != null) ambiguousLegacyCallIds += id
            val attempt = if (newAttempt) {
                MutableAttempt(id).also {
                    attempts.add(it)
                    latestByCallId[id] = it
                }
            } else requireNotNull(previous)
            if (actualCall) attempt.toolCallObserved = true
            return attempt
        }

        when (type) {
            "assistant/message" -> {
                val neutral = data["model_tool_calls"] as? JsonArray
                if (neutral != null) {
                    for (element in neutral) {
                        val call = element as? JsonObject ?: continue
                        val id = readString(call, "id") ?: continue
                        declare(id, actualCall = false).apply {
                            name = readString(call, "name") ?: name
                            step = stepHint ?: step
                        }
                    }
                } else {
                    // Legacy OpenAI-shaped messages must remain replayable.
                    val legacy = data["tool_calls"] as? JsonArray ?: return
                    for (element in legacy) {
                        val call = element as? JsonObject ?: continue
                        val id = readString(call, "id") ?: continue
                        val function = call["function"] as? JsonObject
                        declare(id, actualCall = false).apply {
                            name = function?.let { readString(it, "name") } ?: name
                            step = stepHint ?: step
                        }
                    }
                }
            }
            "tool/call" -> {
                val id = readString(data, "id") ?: return
                declare(id, actualCall = true).apply {
                    name = readString(data, "name") ?: name
                    step = (data["step"] as? JsonPrimitive)?.intOrNull ?: stepHint ?: step
                    goalId = readString(data, "goal_id") ?: goalId
                    // Older logs predate the split between tool declaration and
                    // actual executor admission. Preserve their conservative
                    // "started" meaning when the flag is missing.
                    started = started ||
                        ((data["execution_started"] as? JsonPrimitive)?.booleanOrNull ?: true)
                }
            }
            "tool/execution-started" -> {
                val id = readString(data, "id") ?: return
                val executionId = readString(data, "execution_id")
                val known = executionId?.let(byExecutionId::get)
                // An execution identity is immutable after its first actual admission.
                // Replayed or delayed admissions may neither rebind a provider call ID
                // nor rewrite the Run/read-only contract of an earlier invocation.
                if (known != null && known.id != id) return
                val latest = latestByCallId[id]
                // Admission is an authoritative execution fact even when a consumer did not
                // observe the declaration. A new execution must not disappear behind an
                // earlier admission with the same request-scoped provider call ID.
                val attempt = known ?: if (executionId != null && latest != null &&
                    (latest.admissionObserved || latest.settled || latest.executionId != null)) {
                    ambiguousLegacyCallIds += id
                    MutableAttempt(id).also {
                        attempts.add(it)
                        latestByCallId[id] = it
                    }
                } else track(id)
                if (attempt.admissionObserved || attempt.settled || attempt.executionId != null) return
                attempt.apply {
                    name = readString(data, "name") ?: name
                    step = stepHint ?: step
                    if (executionId != null) {
                        this.executionId = executionId
                        byExecutionId[executionId] = this
                    }
                    admissionObserved = true
                    val attributes = data["attributes"] as? JsonObject
                    goalId = attributes?.let { readString(it, "goal_id") } ?: goalId
                    runId = attributes?.let { readString(it, "run_id") } ?: runId
                    parentRunId = attributes?.let { readString(it, "parent_run_id") } ?: parentRunId
                    verifiedReadOnly = (data["read_only"] as? JsonPrimitive)?.booleanOrNull == true
                    started = true
                }
            }
            "tool/result" -> {
                val id = readString(data, "id") ?: return
                val executionId = readString(data, "execution_id")
                val known = executionId?.let(byExecutionId::get)
                // Explicit execution identity must never fall back to a different admitted
                // invocation merely because the provider reused its call ID.
                if (known != null && known.id != id) return
                val latest = latestByCallId[id]
                // An execution-addressed receipt must match a persisted admission.
                // A receipt without execution identity can only settle a legacy attempt
                // that never acquired one. Provider call IDs may be reused across runs.
                if (executionId != null && known == null) return
                if (executionId == null &&
                    (latest?.executionId != null || id in ambiguousLegacyCallIds)) return
                val attempt = known ?: track(id)
                attempt.apply {
                    if (executionId != null) {
                        this.executionId = executionId
                        byExecutionId[executionId] = this
                    }
                    settled = true
                    val code = readString(data, "error_code")
                    val sideEffect = readString(data, "side_effect")
                    val isError = (data["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                    uncertain = uncertain ||
                        code == "TOOL_OUTCOME_UNKNOWN" ||
                        (isError && sideEffect == "possible")
                }
            }
        }
    }

    /** In declaration order, for rebuilding the currently open Step after a crash. */
    fun pending(): List<Attempt> =
        attempts.filter { !it.settled }.map { it.snapshot() }

    /** Never allow an unknown mutation to be masked by a later successful tool. */
    fun hasUnresolvedExternalEffects(goalId: String): Boolean =
        goalId.isNotBlank() && attempts.any { attempt ->
            attempt.goalId == goalId &&
                attempt.snapshot().unresolvedExternalEffect
        }

    fun snapshot(): List<Attempt> = attempts.map { it.snapshot() }
}
