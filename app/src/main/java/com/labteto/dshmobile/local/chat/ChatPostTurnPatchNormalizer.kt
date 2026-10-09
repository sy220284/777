package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Normalizes only safely interpretable model patch values, never the persisted source of truth.
 * An invalid optional field is omitted so the reducer retains the previous authoritative value.
 */
internal fun normalizeChatPostTurnPatch(root: JsonObject): JsonObject {
    val rawState = root["state"] as? JsonObject ?: return root
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
        if (list == null) state.remove(name)
        else state[name] = JsonArray(list.filter { it is JsonPrimitive && it.isString })
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
            if (list == null) fields.remove(key)
            else fields[key] = JsonArray(list.filter { it is JsonPrimitive && it.isString })
        }
        evidenceArrays.forEach { key ->
            val value = fields[key] ?: return@forEach
            val list = value as? JsonArray
            if (list == null) fields.remove(key)
            else fields[key] = JsonArray(list.filterIsInstance<JsonObject>().map { candidate ->
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
        state[name] = JsonObject(fields)
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

    return JsonObject(root.toMutableMap().apply { put("state", JsonObject(state)) })
}
