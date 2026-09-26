package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import com.labteto.dshmobile.harness.agent.AgentModelRoute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val LOCAL_MODEL_ROUTE_EVENT_TYPE = "session/model-route"

internal fun encodeLocalModelRouteEvent(route: AgentModelRoute): JsonObject = buildJsonObject {
    put("provider", route.provider)
    put("base_url", route.baseUrl)
    put("model", route.model)
    put("protocol", route.protocol.name.lowercase())
}

internal fun decodeLocalModelRouteEvent(data: JsonObject): AgentModelRoute? {
    fun string(key: String): String? =
        (data[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.trim()?.takeIf(String::isNotBlank)

    val baseUrl = string("base_url") ?: return null
    val model = string("model") ?: return null
    val provider = string("provider") ?: return null
    val protocol = string("protocol")?.let { encoded ->
        AgentModelProtocol.entries.firstOrNull { it.name.equals(encoded, ignoreCase = true) }
    } ?: return null
    return AgentModelRoute(
        provider = provider,
        baseUrl = normalizeModelBaseUrl(baseUrl),
        model = model,
        protocol = protocol,
    )
}

internal fun recoverSessionModelRoute(
    snapshot: LocalHarnessSession,
    latestRouteEvent: LocalSessionEventLog.Event?,
    fallbackBaseUrl: String,
    fallbackModel: String,
): AgentModelRoute =
    latestRouteEvent
        ?.takeIf { it.type == LOCAL_MODEL_ROUTE_EVENT_TYPE }
        ?.let { decodeLocalModelRouteEvent(it.data) }
        ?: resolveSessionModelRoute(snapshot, fallbackBaseUrl, fallbackModel)
