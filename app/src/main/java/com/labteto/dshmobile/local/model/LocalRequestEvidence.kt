package com.labteto.dshmobile.local.model

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stable, bounded evidence for one model-visible request surface.
 *
 * Full conversation history remains owned by Session EventLog / checkpoints. This evidence records
 * deterministic digests for the final request plus the exact system/developer context slice and tool
 * schema surface so a replay can prove whether it reconstructed the same model-visible envelope.
 */
internal data class LocalRequestEvidence(
    val messageDigest: String,
    val toolSchemaDigest: String,
    val resolvedToolSurfaceDigest: String,
    val contextDigest: String,
    val contextMessages: JsonArray,
)

internal fun buildLocalRequestEvidence(
    messages: List<JsonObject>,
    tools: JsonArray,
    nativeTools: JsonArray = JsonArray(emptyList()),
): LocalRequestEvidence {
    val contextMessages = JsonArray(
        messages.filter { message ->
            message["role"]?.jsonPrimitive?.contentOrNull in MODEL_CONTEXT_ROLES
        },
    )
    val resolvedToolSurface = buildJsonObject {
        put("schemas", tools)
        put("native_tools", nativeTools)
    }
    return LocalRequestEvidence(
        messageDigest = stableJsonSha256(JsonArray(messages)),
        toolSchemaDigest = stableJsonSha256(tools),
        resolvedToolSurfaceDigest = stableJsonSha256(resolvedToolSurface),
        contextDigest = stableJsonSha256(contextMessages),
        contextMessages = contextMessages,
    )
}

internal fun stableJsonSha256(value: JsonElement): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private val MODEL_CONTEXT_ROLES = setOf("system", "developer")
