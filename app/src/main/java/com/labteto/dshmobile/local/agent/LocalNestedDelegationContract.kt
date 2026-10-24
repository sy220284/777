package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    val task = arguments["task"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: error("NESTED_DELEGATION_TASK_REQUIRED")
    require(task.length <= 16_000) { "NESTED_DELEGATION_TASK_TOO_LARGE" }
    val desired = arguments["max_steps"]?.jsonPrimitive?.intOrNull
        ?: minOf(parentMaxSteps, 12)
    require(desired in 1..minOf(parentMaxSteps, 24)) {
        "NESTED_DELEGATION_STEP_BUDGET_EXCEEDED"
    }
    val role = bindLocalAgentRole(
        requested = arguments["role"]?.jsonPrimitive?.contentOrNull,
        userInstructions = arguments["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        parentMutationAllowed = false,
    )
    require(!role.allowMutation) { "NESTED_DELEGATION_WRITE_FORBIDDEN" }
    return LocalNestedDelegationRequest(task, role.instructions, desired)
}
