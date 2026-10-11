package com.labteto.dshmobile.local.chat

import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalModelReply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Explicit expression-preference diagnostics and lossless media-aware text replacement.
 *
 * Preferences are supplied before generation. Diagnostics never delete completed speech.
 */
internal object ChatStyleGuard {
    // Only the user's explicit phrase choices participate in filtering.
    val bannedPhrases: List<String> = emptyList()

    fun activePhrases(
        customPhrases: List<String> = emptyList(),
        personaPhrases: List<String> = emptyList(),
        enabled: Boolean = true,
    ): List<String> {
        if (!enabled) return emptyList()
        return (bannedPhrases + customPhrases + personaPhrases)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }

    fun violations(
        text: String,
        extraBannedPhrases: List<String> = emptyList(),
        builtInEnabled: Boolean = true,
    ): List<String> =
        ((if (builtInEnabled) bannedPhrases else emptyList()) + extraBannedPhrases)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .filter { phrase -> phrase in text }
            .toList()

    fun violations(text: String, phrases: Collection<String>): List<String> =
        phrases.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .filter { phrase -> phrase in text }
            .toList()

    fun withContent(reply: LocalModelReply, content: String): LocalModelReply {
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

}
