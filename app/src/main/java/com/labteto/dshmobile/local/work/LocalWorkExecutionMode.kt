package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_WORK_EXECUTION_MODE_KEY = "777_execution_mode"

/** Durable mode is metadata; only the latest user turn contributes request-scoped guidance. */
internal fun withLocalWorkExecutionMode(history: List<JsonObject>): List<JsonObject> {
    val latestUser = history.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
    val team = latestUser?.get(LOCAL_WORK_EXECUTION_MODE_KEY)?.jsonPrimitive?.contentOrNull == "agent_team"
    val messages = history.map { JsonObject(it - LOCAL_WORK_EXECUTION_MODE_KEY) }
    if (!team) return messages
    val instruction = buildJsonObject {
        put("role", "system")
        put("content", LOCAL_AGENT_TEAM_DIRECTIVE)
    }
    return if (messages.firstOrNull()?.get("role")?.jsonPrimitive?.contentOrNull == "system") {
        listOf(buildJsonObject {
            put("role", "system")
            put("content", messages.first()["content"]?.jsonPrimitive?.contentOrNull.orEmpty() + "\n\n" + LOCAL_AGENT_TEAM_DIRECTIVE)
        }) + messages.drop(1)
    } else listOf(instruction) + messages
}

/** 最新用户回合决定集群工具权限，不能从旧对话推断。 */
internal fun isLocalAgentTeamTurn(history: List<JsonObject>): Boolean =
    history.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        ?.get(LOCAL_WORK_EXECUTION_MODE_KEY)?.jsonPrimitive?.contentOrNull == "agent_team"
