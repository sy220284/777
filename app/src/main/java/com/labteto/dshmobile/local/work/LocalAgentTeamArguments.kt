package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.requiredTeamString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        ?: error("TEAM_ARGUMENT_REQUIRED：$key")

internal fun JsonObject.optionalTeamString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

internal fun JsonObject.optionalTeamContext(): LocalTeamMemberContext =
    when (optionalTeamString("context")?.lowercase()) {
        null, "", "fresh" -> LocalTeamMemberContext.FRESH
        "fork" -> LocalTeamMemberContext.FORK
        else -> error("TEAM_MEMBER_CONTEXT_INVALID：context 仅支持 fresh / fork")
    }

internal fun JsonObject.teamStringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.orEmpty()

internal fun JsonObject.teamStringArrayOrNull(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
