package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal data class ParsedChatPostTurnPlan(
    val plan: ChatPostTurnPlan,
    val rawState: JsonObject?,
)

internal class ChatInteractionPlanParser(
    private val json: Json,
) {
    fun parseSuggestions(text: String): List<ChatReplySuggestion>? {
        val body = extractJsonObject(text) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        val normalized = normalizeChatReplySuggestionPayload(root)
        val decoded = runCatching {
            json.decodeFromString(ChatReplySuggestionPlan.serializer(), normalized.toString())
        }.getOrNull() ?: return null
        return sanitizeSuggestions(decoded.suggestions)
    }

    fun parseJsonObjectEnvelope(text: String): JsonObject? =
        extractJsonObject(text)?.let { body ->
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }

    fun parsePlan(text: String): ParsedChatPostTurnPlan? = parsePlanWithFailure(text).first

    /** Content-free error label suitable for exported diagnostics. */
    fun parseFailureKind(text: String): String = parsePlanWithFailure(text).second

    private fun parsePlanWithFailure(text: String): Pair<ParsedChatPostTurnPlan?, String> {
        val body = extractJsonObject(text) ?: return null to
            if (text.contains('{')) "incomplete_json" else "missing_json_object"
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null to "invalid_json"
        val normalized = normalizeChatPostTurnPatch(root)
        val originalState = root["state"] as? JsonObject
        val normalizedState = normalized["state"] as? JsonObject
        // A malformed model patch must not silently become a successful empty update:
        // the caller would otherwise advance its durable pending-turn cursor.
        // Genuine no-change state {} and independently valid diary/suggestions remain usable.
        val hasStateUpdate = normalizedState?.any { (key, field) ->
            key !in setOf("dynamics", "userPattern", "continuity") ||
                (field as? JsonObject)?.isNotEmpty() == true
        } == true
        val diary = normalized["diaryDelta"] as? JsonObject
        val diaryEvent = (diary?.get("event") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content.orEmpty()
        val diaryImportance = (diary?.get("importance") as? kotlinx.serialization.json.JsonPrimitive)
            ?.intOrNull ?: 0
        val diaryHasPerspective = listOf("feeling", "innerThought").any { name ->
            (diary?.get(name) as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.isNotBlank() == true
        }
        // Only a substantive diary can independently justify processing a pending turn.
        // Reply suggestions are generated in a separate flow and cannot mask a bad patch.
        val hasDiary = diaryEvent.trim().length >= 6 && diaryImportance >= 2 &&
            diaryHasPerspective
        // A model can include system-owned fields (scene, evolution, lifeState) as
        // decoration. Those fields are intentionally ignored and must not turn a
        // valid no-op result into a retry loop. Reject only an unusable requested
        // model-owned patch, so truly malformed types still preserve pending work.
        val requestedModelStateChange = originalState?.any { (key, value) ->
            when {
                key !in CHAT_MODEL_WRITABLE_STATE_FIELDS -> false
                key !in CHAT_MODEL_WRITABLE_NESTED_FIELDS -> true
                value !is JsonObject -> true
                else -> value.keys.any { it in CHAT_MODEL_WRITABLE_NESTED_FIELDS[key].orEmpty() }
            }
        } == true
        val discardedRequestedState =
            (requestedModelStateChange && !hasStateUpdate) ||
                (root.containsKey("state") && originalState == null)
        if (discardedRequestedState && !hasDiary) {
            return null to "state_patch_unusable"
        }
        // A bare {} or significance-only response contains no actionable state and
        // must not consume the persisted pending-turn cursor as a successful refresh.
        if (!root.containsKey("state") && !hasDiary) {
            return null to "state_patch_missing"
        }
        val decoded = runCatching {
            json.decodeFromString(ChatPostTurnPlan.serializer(), normalized.toString())
        }.getOrNull() ?: return null to "schema_mismatch"
        val rawState = normalized["state"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return ParsedChatPostTurnPlan(
            plan = decoded.copy(suggestions = sanitizeSuggestions(decoded.suggestions)),
            rawState = rawState,
        ) to "none"
    }

    private fun sanitizeSuggestions(
        suggestions: List<ChatReplySuggestion>,
    ): List<ChatReplySuggestion> =
        suggestions.asSequence()
            .map { suggestion ->
                val style = suggestion.style.trim().take(12)
                ChatReplySuggestion(
                    label = suggestion.label.trim().take(12),
                    text = suggestion.text.trim().take(320),
                    style = style,
                    bold = suggestion.bold || style == "放飞",
                )
            }
            .filter { it.label.isNotBlank() && it.text.isNotBlank() }
            .distinctBy { normalizeChatInteractionText(it.text) }
            .take(4)
            .toList()

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()
            .removePrefix("~~~json")
            .removePrefix("~~~")
            .removeSuffix("~~~")
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until trimmed.length) {
            val char = trimmed[index]
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && char == '{' -> depth++
                !quoted && char == '}' -> {
                    depth--
                    if (depth == 0) {
                        return escapeUnquotedJsonControls(trimmed.substring(start, index + 1))
                    }
                }
            }
        }
        return null
    }

    // A literal newline in a model-generated JSON string is still unambiguous text.
    // Escape it rather than paying for another entire model inference.
    private fun escapeUnquotedJsonControls(body: String): String = buildString {
        var quoted = false
        var escaped = false
        body.forEach { char ->
            when {
                escaped -> {
                    append(char)
                    escaped = false
                }
                quoted && char == '\\' -> {
                    append(char)
                    escaped = true
                }
                char == '"' -> {
                    append(char)
                    quoted = !quoted
                }
                quoted && char.code < 32 -> {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                }
                else -> append(char)
            }
        }
    }

}
