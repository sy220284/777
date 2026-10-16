package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Lossless native media-aware replacement of reply display text. Routing and usage remain unchanged. */
internal fun withLocalReplyContent(reply: LocalModelReply, content: String): LocalModelReply {
    // Most replies pass the guard unchanged. Preserve their original block boundaries and
    // text/image interleaving instead of normalizing several text blocks into one.
    if (reply.content == content) return reply
    val existing = reply.message["content"]
    val nextContent = if (existing is JsonArray) {
        var inserted = false
        buildJsonArray {
            existing.forEach { part ->
                val obj = part as? JsonObject
                val kind = obj?.get("type")?.jsonPrimitive?.contentOrNull
                if (kind in setOf("text", "input_text", "output_text")) {
                    if (!inserted && content.isNotBlank()) {
                        add(JsonObject(requireNotNull(obj).toMutableMap().apply {
                            put("text", JsonPrimitive(content))
                        }))
                        inserted = true
                    }
                } else {
                    add(part)
                }
            }
            if (!inserted && content.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", content)
                })
            }
        }
    } else {
        JsonPrimitive(content)
    }
    val message = JsonObject(reply.message + ("content" to nextContent))
    val canonical = reply.canonicalMessage?.let { source ->
        var inserted = false
        source.copy(
            content = buildList {
                source.content.forEach { block ->
                    if (block is LocalCanonicalContent.Text) {
                        if (!inserted && content.isNotBlank()) {
                            add(LocalCanonicalContent.Text(content))
                            inserted = true
                        }
                    } else {
                        add(block)
                    }
                }
                if (!inserted && content.isNotBlank()) {
                    add(0, LocalCanonicalContent.Text(content))
                }
            },
        )
    }
    return reply.copy(
        message = message,
        content = content,
        canonicalMessage = canonical,
    )
}
