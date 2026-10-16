package com.labteto.dshmobile.harness.session

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
class ToolAttemptLedger {
    data class Attempt(
        val id: String,
        val name: String?,
        val step: Int?,
        val goalId: String?,
        val started: Boolean,
        val settled: Boolean,
        val outcomeUncertain: Boolean,
    ) {
        val unresolvedExternalEffect: Boolean
            get() = outcomeUncertain || (started && !settled)
    }

    private data class MutableAttempt(
        val id: String,
        var name: String? = null,
        var step: Int? = null,
        var goalId: String? = null,
        var started: Boolean = false,
        var settled: Boolean = false,
        var uncertain: Boolean = false,
    ) {
        fun snapshot() = Attempt(id, name, step, goalId, started, settled, uncertain)
    }

    private val attempts = linkedMapOf<String, MutableAttempt>()

    fun observe(event: SessionEvent, stepHint: Int? = null) {
        observe(event.type, event.data, stepHint)
    }

    fun observe(type: String, data: JsonObject, stepHint: Int? = null) {
        fun readString(obj: JsonObject, key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

        fun track(id: String): MutableAttempt =
            attempts.getOrPut(id) { MutableAttempt(id) }

        when (type) {
            "assistant/message" -> {
                val neutral = data["model_tool_calls"] as? JsonArray
                if (neutral != null) {
                    for (element in neutral) {
                        val call = element as? JsonObject ?: continue
                        val id = readString(call, "id") ?: continue
                        track(id).apply {
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
                        track(id).apply {
                            name = function?.let { readString(it, "name") } ?: name
                            step = stepHint ?: step
                        }
                    }
                }
            }
            "tool/call" -> {
                val id = readString(data, "id") ?: return
                track(id).apply {
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
                track(id).apply {
                    name = readString(data, "name") ?: name
                    step = stepHint ?: step
                    val attributes = data["attributes"] as? JsonObject
                    goalId = attributes?.let { readString(it, "goal_id") } ?: goalId
                    started = true
                }
            }
            "tool/result" -> {
                val id = readString(data, "id") ?: return
                track(id).apply {
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
        attempts.values.filter { !it.settled }.map { it.snapshot() }

    /** Never allow an unknown mutation to be masked by a later successful tool. */
    fun hasUnresolvedExternalEffects(goalId: String): Boolean =
        goalId.isNotBlank() && attempts.values.any { attempt ->
            attempt.goalId == goalId &&
                (attempt.uncertain || (attempt.started && !attempt.settled))
        }

    fun snapshot(): List<Attempt> = attempts.values.map { it.snapshot() }
}
