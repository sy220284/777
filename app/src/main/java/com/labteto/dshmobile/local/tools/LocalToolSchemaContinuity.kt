package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.model.LocalModelPromptUpdateMode
import com.labteto.dshmobile.local.model.LocalModelRuntimeCapabilities
import com.labteto.dshmobile.local.model.LocalRunModelSurface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Keeps an APPEND_ONLY provider tool surface byte-stable inside one run.
 *
 * A frozen [LocalRunModelSurface] is preferred so changing UI model selection cannot mutate an
 * already running Agent's provider capability semantics.
 */
internal class LocalRunToolSurface(
    private val surface: LocalRunModelSurface? = null,
) {
    private var visible: JsonArray? = null

    fun next(current: JsonArray): JsonArray {
        val frozen = requireNotNull(surface) { "Frozen model surface is required" }
        return stableRunToolSchemas(
            previous = visible,
            current = current,
            capabilities = frozen.capabilities,
        ).also { visible = it }
    }
}

internal fun stableRunToolSchemas(
    previous: JsonArray?,
    current: JsonArray,
    surface: LocalRunModelSurface,
): JsonArray = stableRunToolSchemas(
    previous = previous,
    current = current,
    capabilities = surface.capabilities,
)

private fun stableRunToolSchemas(
    previous: JsonArray?,
    current: JsonArray,
    capabilities: LocalModelRuntimeCapabilities,
): JsonArray {
    previous ?: return current
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
    if (previousByName.size != previous.size || currentByName.size != current.size) return current
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
