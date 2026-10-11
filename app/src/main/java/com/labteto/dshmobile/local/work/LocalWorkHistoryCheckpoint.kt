package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.LocalContextCheckpointKind
import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import com.labteto.dshmobile.observability.AppLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalStructuredWorkState(
    val goals: List<String> = emptyList(),
    val plan: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val decisionEpoch: Long = 0L,
    val constraintEpoch: Long = 0L,
    val failures: List<String> = emptyList(),
    val unfinished: List<String> = emptyList(),
    val progress: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val toolCallIds: List<String> = emptyList(),
) : com.labteto.dshmobile.local.model.LocalHistorySummaryInput

internal data class LocalWorkCheckpoint(
    val goals: List<String>,
    val plan: List<String> = emptyList(),
    val constraints: List<String>,
    val decisions: List<String>,
    val decisionEpoch: Long = 0L,
    val constraintEpoch: Long = 0L,
    val userDecisionsVerified: Boolean = false,
    val failures: List<String>,
    val unfinished: List<String>,
    val progress: List<String>,
    val facts: List<String> = emptyList(),
    val artifacts: List<String>,
    val tools: List<String>,
    val toolCallIds: List<String> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", 5)
        put("goals", JsonArray(goals.map(::JsonPrimitive)))
        put("plan", JsonArray(plan.map(::JsonPrimitive)))
        put("constraints", JsonArray(constraints.map(::JsonPrimitive)))
        put("decisions", JsonArray(decisions.map(::JsonPrimitive)))
        put("decision_epoch", decisionEpoch)
        put("constraint_epoch", constraintEpoch)
        put("user_decisions_verified", userDecisionsVerified)
        put("failures", JsonArray(failures.map(::JsonPrimitive)))
        put("unfinished", JsonArray(unfinished.map(::JsonPrimitive)))
        put("progress", JsonArray(progress.map(::JsonPrimitive)))
        put("facts", JsonArray(facts.map(::JsonPrimitive)))
        put("artifacts", JsonArray(artifacts.map(::JsonPrimitive)))
        put("tools", JsonArray(tools.map(::JsonPrimitive)))
        put("tool_call_ids", JsonArray(toolCallIds.map(::JsonPrimitive)))
    }

    fun toModelBlock(): String = buildString {
        append("<work-checkpoint>\n")
        append(toJsonObject().toString())
        append("\n</work-checkpoint>")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun latestFrom(messages: List<JsonObject>): LocalWorkCheckpoint? =
            messages.asReversed().firstNotNullOfOrNull { message ->
                if (!isTrustedContextCheckpointModelMessage(message, LocalContextCheckpointKind.WORK)) {
                    return@firstNotNullOfOrNull null
                }
                val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
                // Trusted checkpoint messages always have one outer wrapper emitted by us.
                // Payload fields may legitimately contain the same literal text, so the outer
                // opening tag must be the first occurrence while the closing tag stays the last.
                val start = content.indexOf("<work-checkpoint>")
                val end = content.lastIndexOf("</work-checkpoint>")
                if (start < 0 || end <= start) return@firstNotNullOfOrNull null
                val payload = content
                    .substring(start + "<work-checkpoint>".length, end)
                    .trim()
                runCatching {
                    fromJsonObject(json.parseToJsonElement(payload) as JsonObject)
                }.onFailure { error ->
                    AppLog.warn("WorkCheckpoint",
                        "failure=true operation=decode_work_checkpoint cause_type=${error.javaClass.simpleName}")
                }.getOrNull()
            }

        private fun fromJsonObject(value: JsonObject): LocalWorkCheckpoint {
            fun list(key: String): List<String> =
                (value[key] as? JsonArray).orEmpty()
                    .mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
                    .filter(String::isNotBlank)
            return LocalWorkCheckpoint(
                goals = list("goals"),
                plan = list("plan"),
                constraints = list("constraints"),
                decisions = list("decisions"),
                decisionEpoch = value["decision_epoch"]?.jsonPrimitive?.longOrNull ?: 0L,
                constraintEpoch = value["constraint_epoch"]?.jsonPrimitive?.longOrNull ?: 0L,
                userDecisionsVerified = value["user_decisions_verified"]?.jsonPrimitive?.contentOrNull == "true",
                failures = list("failures"),
                unfinished = list("unfinished"),
                progress = list("progress"),
                facts = list("facts"),
                artifacts = list("artifacts"),
                tools = list("tools"),
                toolCallIds = list("tool_call_ids"),
            )
        }
    }
}
