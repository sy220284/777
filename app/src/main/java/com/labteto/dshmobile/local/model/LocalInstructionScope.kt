package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Runtime provenance, retained locally and stripped by provider serialization. */
internal enum class LocalInstructionScope(val inheritedByChild: Boolean) {
    BASE(true), SESSION(true), AGENT(false), TURN(false), APPROVAL(false),
}

internal const val LOCAL_INSTRUCTION_SCOPE = "_dsh_instruction_scope"

internal fun JsonObject.withInstructionScope(scope: LocalInstructionScope): JsonObject =
    JsonObject(this + (LOCAL_INSTRUCTION_SCOPE to JsonPrimitive(scope.name)))
