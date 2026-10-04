package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.resolveLocalModelProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps an APPEND_ONLY provider tool surface byte-stable inside one run.
 *
 * Additions are appended after the previously exposed schemas. Any removal or schema mutation is
 * treated as an authoritative capability/security change and replaces the surface immediately.
 */
internal fun stableRunToolSchemas(
    previous: JsonArray?,
    current: JsonArray,
    state: LocalHarnessState,
): JsonArray {
    previous ?: return current
    val profile = state.modelSelection.activeProfile
    val protocol = profile?.let {
        resolveLocalModelProtocol(it.authKind, it, state.model, state.baseUrl)
    } ?: LocalModelPresets.protocolFor(state.model, state.baseUrl)
    val capabilities = LocalModelPresets.runtimeCapabilitiesFor(
        model = state.model,
        baseUrl = state.baseUrl,
        protocol = protocol,
        authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
    )
    if (capabilities.toolUpdateMode != LocalModelPromptUpdateMode.APPEND_ONLY) return current
    return appendOnlyToolSchemas(previous, current)
}

internal fun appendOnlyToolSchemas(previous: JsonArray, current: JsonArray): JsonArray {
    val previousByName = previous.mapNotNull { element ->
        toolSchemaName(element)?.let { it to element }
    }.toMap()
    val currentByName = current.mapNotNull { element ->
        toolSchemaName(element)?.let { it to element }
    }.toMap()
    if (previousByName.keys.any { it !in currentByName }) return current
    if (previousByName.any { (name, schema) -> currentByName[name] != schema }) return current

    val existing = previousByName.keys
    return JsonArray(previous + current.filter { toolSchemaName(it) !in existing })
}

private fun toolSchemaName(element: kotlinx.serialization.json.JsonElement): String? {
    val obj = element as? JsonObject ?: return null
    val function = obj["function"] as? JsonObject
    return function?.get("name")?.jsonPrimitive?.contentOrNull
        ?: obj["name"]?.jsonPrimitive?.contentOrNull
}
