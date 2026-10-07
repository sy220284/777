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
 * User-configurable output filter for chat mode.
 *
 * The master switch lives in settings. When it is off, callers should bypass this gate completely
 * and expose the model stream as-is. When it is on, the same literal phrase set is used by the
 * streaming gate and the final durable reply so the user never sees one answer and stores another.
 */
internal object ChatStyleGuard {
    val bannedPhrases: List<String> = listOf(
        "我理解你的感受",
        "听起来你",
        "如果你愿意的话",
        "值得注意的是",
        "需要说明的是",
        "总体而言",
        "综合来看",
        "以下是",
        "首先、其次、最后",
        "希望这些对你有帮助",
        "如果还有问题随时告诉我",
        "我会一直在这里",
        "谢谢你愿意和我分享",
    )

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

    /**
     * Literal filtering used by the streaming path and final persisted answer.
     *
     * This intentionally does not ask the model for a second rewrite. It removes only configured
     * phrases and keeps all other generated text intact.
     */
    fun filterLiteral(text: String, phrases: Collection<String>): String {
        var result = text
        phrases.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sortedByDescending(String::length)
            .forEach { phrase -> result = result.replace(phrase, "") }
        return result.ifBlank { "……" }
    }

    /**
     * Legacy deterministic scrub kept for compatibility with older callers and tests.
     * New real-time chat uses [filterLiteral] so streamed and durable text stay identical.
     */
    fun scrub(
        text: String,
        extraBannedPhrases: List<String> = emptyList(),
        builtInEnabled: Boolean = true,
    ): String {
        val builtIn = if (builtInEnabled) {
            bannedPhrases.map(String::trim).filter(String::isNotBlank).distinct()
        } else {
            emptyList()
        }
        var result = CHAT_SENTENCE.findAll(text)
            .map { it.value }
            .filterNot { segment ->
                segment.isNotBlank() && builtIn.any { phrase -> phrase in segment }
            }
            .joinToString("")

        extraBannedPhrases
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { phrase -> result = result.replace(phrase, "") }

        return result
            .replace(Regex("""(^|[。！？!?\n])[\s，、；：,:;]+""")) { match ->
                match.groupValues[1]
            }
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .ifBlank { "……" }
    }

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

    private val CHAT_SENTENCE = Regex("[^。！？!?\\n]+[。！？!?]*|\\n+")
}
