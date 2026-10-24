package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull

/**
 * A model's nested delegation command is untrusted data. Parse it exactly once before
 * reserving a descendant or touching any tool/model runtime.
 */
internal data class LocalNestedDelegationRequest(
    val task: String,
    val instructions: String,
    val maxSteps: Int,
)

internal fun parseLocalNestedDelegation(
    arguments: JsonObject,
    parentMaxSteps: Int,
): LocalNestedDelegationRequest {
    require(parentMaxSteps > 0) { "NESTED_DELEGATION_PARENT_BUDGET_INVALID" }
    require((arguments.keys - setOf("task", "instructions", "role", "max_steps")).isEmpty()) {
        "NESTED_DELEGATION_FIELDS_BLOCKED"
    }
    fun stringField(name: String): String? {
        val raw: JsonElement = arguments[name] ?: return null
        val value = raw as? JsonPrimitive
        require(value?.isString == true) { "NESTED_DELEGATION_FIELD_TYPE_INVALID:$name" }
        return value.content
    }
    val task = stringField("task")?.takeIf(String::isNotBlank)
        ?: error("NESTED_DELEGATION_TASK_REQUIRED")
    require(task.length <= 16_000) { "NESTED_DELEGATION_TASK_TOO_LARGE" }
    val desired = if ("max_steps" in arguments) {
        val value = arguments.getValue("max_steps") as? JsonPrimitive
        require(value != null && !value.isString) { "NESTED_DELEGATION_STEP_TYPE_INVALID" }
        value.intOrNull ?: error("NESTED_DELEGATION_STEP_TYPE_INVALID")
    } else minOf(parentMaxSteps, 12)
    require(desired in 1..minOf(parentMaxSteps, 24)) {
        "NESTED_DELEGATION_STEP_BUDGET_EXCEEDED"
    }
    val role = bindLocalAgentRole(
        requested = stringField("role"),
        userInstructions = stringField("instructions").orEmpty(),
        parentMutationAllowed = false,
    )
    require(!role.allowMutation) { "NESTED_DELEGATION_WRITE_FORBIDDEN" }
    return LocalNestedDelegationRequest(task, role.instructions, desired)
}
