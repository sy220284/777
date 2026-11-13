package com.labteto.dshmobile.harness.session

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The only durable event-envelope upgrader for ALL Session readers (hot pages,
 * reverse indexes, export, cold replay). Old logs stay byte-identical on disk.
 *
 * Version 0: explicit legacy seq/event_type/timestamp/payload envelope.
 * Version 1: original sequence/type/createdAt/data envelope, no version tag.
 * Version 2: current canonical envelope with schema_version=2.
 * Future versions are an explicit *blocking* error, not a malformed row that
 * might be silently skipped and trigger duplicate commands or partial history.
 */
class UnsupportedSessionEventSchemaVersion(val encountered: Int) :
    IllegalStateException("Session event schema $encountered is newer than supported version 2")

object SessionEventSchemaCodec {
    const val CURRENT_VERSION: Int = 2

    fun decode(json: Json, raw: String): SessionEvent {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw SerializationException("Session event envelope must be an object")
        val schemaField = obj["schema_version"]
        val version = when (schemaField) {
            null -> 1
            is JsonPrimitive -> schemaField.intOrNull
                ?: throw SerializationException("Session event schema version must be an integer")
            else -> throw SerializationException("Session event schema version must be an integer")
        }
        if (version > CURRENT_VERSION) throw UnsupportedSessionEventSchemaVersion(version)
        require(version in 0..CURRENT_VERSION) { "Unsupported old Session schema version" }
        val migrated = when (version) {
            0 -> buildJsonObject {
                val sequence = obj["seq"]
                    ?: throw SerializationException("Legacy event missing sequence")
                val kind = obj["event_type"]
                    ?: throw SerializationException("Legacy event missing type")
                val timestamp = obj["timestamp"]
                    ?: throw SerializationException("Legacy event missing timestamp")
                val payload = obj["payload"] as? JsonObject
                    ?: throw SerializationException("Legacy event missing object payload")
                put("sequence", sequence)
                put("type", kind)
                put("createdAt", timestamp)
                put("data", payload)
                put("schema_version", CURRENT_VERSION)
            }
            else -> obj
        }
        val event = json.decodeFromJsonElement(SessionEvent.serializer(), migrated)
        require(event.sequence >= 0L && event.type.isNotBlank() && event.createdAt >= 0L) {
            "Malformed Session event envelope"
        }
        return event.copy(schemaVersion = CURRENT_VERSION)
    }
}
