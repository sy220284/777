package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Single boundary for model-owned chat state fields. System-owned scene, evolution,
 * provenance and counters are intentionally ignored; the runtime alone maintains them.
 */
internal val CHAT_MODEL_WRITABLE_STATE_FIELDS: Set<String> = setOf(
    "physicalState", "mood", "relationshipState", "currentFocus",
    "activeGoal", "currentAgenda", "internalConflict", "immediateConcern",
    "unresolvedThreads", "currentUserImpression", "initiative", "shareDesire",
    "dynamics", "userPattern", "continuity",
)

internal val CHAT_MODEL_WRITABLE_NESTED_FIELDS: Map<String, Set<String>> = mapOf(
    "dynamics" to setOf(
        "stage", "warmth", "trust", "reciprocity", "tension", "stability",
        "unresolvedConflict", "facts", "hypotheses", "unknowns",
        "sharedMoments", "sharedObjects",
    ),
    "userPattern" to setOf("replyLength", "directness", "playfulness",
        "initiative", "emojiStyle", "preferredTone"),
    "continuity" to setOf("recentEvents", "recurringEvents", "decisions", "unfinished"),
)

/**
 * Normalizes only safely interpretable model patch values, never the persisted source of truth.
 * An invalid optional field is omitted so the reducer retains the previous authoritative value.
 */
internal fun normalizeChatPostTurnPatch(root: JsonObject): JsonObject {
    // An invalid optional state block must not discard valid suggestions or diary data.
    val rawState = root["state"] as? JsonObject ?: JsonObject(emptyMap())
    val state = rawState.toMutableMap()

    fun string(name: String) {
        val value = state[name] ?: return
        if (value !is JsonPrimitive || !value.isString) state.remove(name)
    }
    fun integer(name: String) {
        val value = state[name] ?: return
        val parsed = (value as? JsonPrimitive)?.intOrNull
        if (parsed == null) state.remove(name) else state[name] = JsonPrimitive(parsed)
    }
    fun strings(name: String) {
        val value = state[name] ?: return
        val list = value as? JsonArray
        if (list == null) {
            state.remove(name)
        } else {
            val valid = list.filterIsInstance<JsonPrimitive>().filter { it.isString }
            // Preserve valid entries even when another entry drifts in type.
            // A nonempty but wholly invalid array must not erase stored history.
            if (list.isNotEmpty() && valid.isEmpty()) state.remove(name)
            else state[name] = JsonArray(valid)
        }
    }

    listOf("physicalState", "mood", "relationshipState", "currentFocus",
        "activeGoal", "currentAgenda", "internalConflict", "immediateConcern",
        "currentUserImpression").forEach(::string)
    listOf("initiative", "shareDesire").forEach(::integer)
    strings("unresolvedThreads")

    fun nested(name: String, numeric: Set<String>, textual: Set<String>,
               stringArrays: Set<String>, evidenceArrays: Set<String> = emptySet()) {
        val source = state[name] ?: return
        val objectValue = source as? JsonObject ?: run {
            state.remove(name)
            return
        }
        val fields = objectValue.toMutableMap()
        numeric.forEach { key ->
            val value = fields[key] ?: return@forEach
            val number = (value as? JsonPrimitive)?.intOrNull
            if (number == null) fields.remove(key) else fields[key] = JsonPrimitive(number)
        }
        textual.forEach { key ->
            val value = fields[key] ?: return@forEach
            if (value !is JsonPrimitive || !value.isString) fields.remove(key)
        }
        stringArrays.forEach { key ->
            val value = fields[key] ?: return@forEach
            val list = value as? JsonArray
            if (list == null) {
                fields.remove(key)
            } else {
                val valid = list.filterIsInstance<JsonPrimitive>().filter { it.isString }
                if (list.isNotEmpty() && valid.isEmpty()) fields.remove(key)
                else fields[key] = JsonArray(valid)
            }
        }
        evidenceArrays.forEach { key ->
            val value = fields[key] ?: return@forEach
            val list = value as? JsonArray
            if (list == null || (list.isNotEmpty() && list.none { it is JsonObject })) {
                // A malformed evidence array must not clear previously validated facts.
                fields.remove(key)
            } else fields[key] = JsonArray(list.filterIsInstance<JsonObject>().map { candidate ->
                val normalizedEvidence = candidate.toMutableMap()
                listOf("text", "source").forEach { field ->
                    val value = normalizedEvidence[field]
                    if (value != null && (value !is JsonPrimitive || !value.isString)) {
                        normalizedEvidence.remove(field)
                    }
                }
                normalizedEvidence["confidence"]?.let { value ->
                    val score = (value as? JsonPrimitive)?.intOrNull
                    if (score == null) normalizedEvidence.remove("confidence")
                    else normalizedEvidence["confidence"] = JsonPrimitive(score)
                }
                JsonObject(normalizedEvidence)
            })
            // String facts are not converted to evidence: provenance must be verified.
        }
        // These sub-objects also contain system-maintained counters and provenance.
        // Model output may update only fields accepted by the state reducer.
        val permitted = CHAT_MODEL_WRITABLE_NESTED_FIELDS[name].orEmpty()
        state[name] = JsonObject(fields.filterKeys { it in permitted })
    }

    nested("dynamics",
        setOf("warmth", "trust", "reciprocity", "tension", "stability"),
        setOf("stage", "unresolvedConflict"),
        setOf("unknowns", "sharedMoments", "sharedObjects"),
        setOf("facts", "hypotheses"))
    nested("userPattern",
        setOf("directness", "playfulness", "initiative", "observedTurns", "averageMessageChars"),
        setOf("replyLength", "emojiStyle", "preferredTone"), emptySet())
    nested("continuity", emptySet(), emptySet(),
        setOf("recentEvents", "recurringEvents", "decisions", "unfinished"))

    // Decode only model-owned patch fields. The reducer computes the rest from durable history.
    val normalizedState = JsonObject(state.filterKeys { it in CHAT_MODEL_WRITABLE_STATE_FIELDS })
    val result = root.toMutableMap().apply { put("state", normalizedState) }
    result["turnSignificance"]?.let { value ->
        if (value !is JsonPrimitive || !value.isString) result.remove("turnSignificance")
    }
    result["suggestions"]?.let { value ->
        val entries = value as? JsonArray
        if (entries == null) {
            result.remove("suggestions")
        } else {
            result["suggestions"] = JsonArray(entries.mapNotNull { element ->
                val candidate = element as? JsonObject ?: return@mapNotNull null
                val label = candidate["label"] as? JsonPrimitive
                if (label?.isString != true) return@mapNotNull null
                val fields = candidate.toMutableMap()
                listOf("text", "style").forEach { key ->
                    fields[key]?.let { field ->
                        if (field !is JsonPrimitive || !field.isString) fields.remove(key)
                    }
                }
                fields["bold"]?.let { field ->
                    val parsed = (field as? JsonPrimitive)?.booleanOrNull
                    if (parsed == null) fields.remove("bold")
                    else fields["bold"] = JsonPrimitive(parsed)
                }
                JsonObject(fields)
            })
        }
    }
    result["diaryDelta"]?.let { value ->
        if (value !is JsonObject && value !is kotlinx.serialization.json.JsonNull) {
            result.remove("diaryDelta")
        } else if (value is JsonObject) {
            result["diaryDelta"] = normalizeChatDiaryDelta(value)
        }
    }
    return JsonObject(result)
}

/** The standalone suggestion generator shares the same lenient optional-field contract. */
internal fun normalizeChatReplySuggestionPayload(root: JsonObject): JsonObject {
    val withState = JsonObject(root + ("state" to JsonObject(emptyMap())))
    return JsonObject(normalizeChatPostTurnPatch(withState).filterKeys { it != "state" })
}

/** Shared Chat/Group observer diary normalization. Invalid optional values retain defaults. */
internal fun normalizeChatDiaryDelta(raw: JsonObject): JsonObject {
    val fields = raw.toMutableMap()
    listOf("event", "feeling", "innerThought", "relationshipMeaning",
        "unresolvedEcho", "disclosure").forEach { key ->
        fields[key]?.let { value ->
            if (value !is JsonPrimitive || !value.isString) fields.remove(key)
        }
    }
    fields["importance"]?.let { value ->
        val score = (value as? JsonPrimitive)?.intOrNull
        if (score == null) fields.remove("importance")
        else fields["importance"] = JsonPrimitive(score)
    }
    return JsonObject(fields)
}
