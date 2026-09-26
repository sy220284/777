package com.labteto.dshmobile.harness.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class SessionSurfaceOperation {
    APPEND,
    REPLACE,
}

data class SessionSurfaceMutation(
    val surface: String,
    val operation: SessionSurfaceOperation,
    val sourceEventSeqs: List<Long>,
    val replacementEventSeq: Long? = null,
) {
    init {
        require(surface.isNotBlank()) { "Surface 名称不能为空" }
        require(sourceEventSeqs.distinct().size == sourceEventSeqs.size) {
            "Surface 来源事件不能重复"
        }
        require(sourceEventSeqs.all { it >= 0L }) { "Surface 来源事件序号非法" }
        when (operation) {
            SessionSurfaceOperation.APPEND -> require(replacementEventSeq == null) {
                "append 操作不能声明 replacementEventSeq"
            }
            SessionSurfaceOperation.REPLACE -> {
                require(sourceEventSeqs.isNotEmpty()) { "replace 必须声明 sourceEventSeqs" }
                require(replacementEventSeq != null && replacementEventSeq >= 0L) {
                    "replace 必须声明有效 replacementEventSeq"
                }
                require(replacementEventSeq !in sourceEventSeqs) {
                    "替换事件不能同时作为自己的来源"
                }
            }
        }
    }
}

fun encodeSessionSurfaceMutation(mutation: SessionSurfaceMutation): JsonObject = buildJsonObject {
    put("version", SESSION_SURFACE_VERSION)
    put("surface", mutation.surface)
    put("op", mutation.operation.name.lowercase())
    put("source_event_seqs", JsonArray(mutation.sourceEventSeqs.map(::JsonPrimitive)))
    mutation.replacementEventSeq?.let { put("replacement_event_seq", it) }
}

fun decodeSessionSurfaceMutation(data: JsonObject): SessionSurfaceMutation? {
    if ((data["version"] as? JsonPrimitive)?.longOrNull != SESSION_SURFACE_VERSION.toLong()) return null
    val surface = (data["surface"] as? JsonPrimitive)?.contentOrNull
        ?.trim()?.takeIf(String::isNotBlank) ?: return null
    val operation = (data["op"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
        SessionSurfaceOperation.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    } ?: return null
    val sources = (data["source_event_seqs"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        ?: return null
    if (sources.size != (data["source_event_seqs"] as JsonArray).size) return null
    val replacement = (data["replacement_event_seq"] as? JsonPrimitive)?.longOrNull
    return runCatching {
        SessionSurfaceMutation(
            surface = surface,
            operation = operation,
            sourceEventSeqs = sources,
            replacementEventSeq = replacement,
        )
    }.getOrNull()
}

const val SESSION_SURFACE_EVENT_TYPE = "session/surface"
const val MODEL_HISTORY_SURFACE = "model-history"
private const val SESSION_SURFACE_VERSION = 1
