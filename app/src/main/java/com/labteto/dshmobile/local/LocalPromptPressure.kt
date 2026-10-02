package com.labteto.dshmobile.local

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One mutually-exclusive estimate of the next model request.
 *
 * API-reported usage remains the accounting source of truth. This estimate is only a preflight
 * pressure signal used for context compaction, concurrency admission and diagnostics.
 */
internal data class LocalPromptPressure(
    val contextChars: Int,
    val estimatedInputTokens: Int,
    val systemTokens: Int,
    val historyTokens: Int,
    val currentUserTokens: Int,
    val toolDefinitionTokens: Int,
    val operationalLimitTokens: Int,
    val modelContextWindowTokens: Int? = null,
) {
    val remainingOperationalTokens: Int
        get() = (operationalLimitTokens - estimatedInputTokens).coerceAtLeast(0)

    val usageRatio: Double
        get() = if (operationalLimitTokens <= 0) 1.0
        else estimatedInputTokens.toDouble() / operationalLimitTokens.toDouble()
}

internal object LocalPromptPressureMeter {
    fun measure(
        messages: List<JsonObject>,
        tools: JsonArray,
        operationalLimitTokens: Int,
        modelContextWindowTokens: Int? = null,
    ): LocalPromptPressure {
        val latestUser = messages.indexOfLast {
            it["role"]?.jsonPrimitive?.contentOrNull == "user"
        }
        var system = 0
        var history = 0
        var currentUser = 0
        var chars = 0
        messages.forEachIndexed { index, message ->
            val encoded = message.toString()
            val tokens = estimateModelTokens(encoded)
            chars += encoded.length
            when {
                message["role"]?.jsonPrimitive?.contentOrNull == "system" -> system += tokens
                index == latestUser -> currentUser += tokens
                else -> history += tokens
            }
        }
        val toolText = tools.toString()
        val toolTokens = if (tools.isEmpty()) 0 else estimateModelTokens(toolText)
        chars += if (tools.isEmpty()) 0 else toolText.length
        return LocalPromptPressure(
            contextChars = chars,
            estimatedInputTokens = system + history + currentUser + toolTokens,
            systemTokens = system,
            historyTokens = history,
            currentUserTokens = currentUser,
            toolDefinitionTokens = toolTokens,
            operationalLimitTokens = operationalLimitTokens.coerceAtLeast(1),
            modelContextWindowTokens = modelContextWindowTokens,
        )
    }
}

/** Last real request projection per session; used by environment diagnostics instead of raw history size. */
internal class LocalRequestPressureStore {
    private val latest = ConcurrentHashMap<String, LocalPromptPressure>()

    fun record(sessionId: String, pressure: LocalPromptPressure) {
        latest[sessionId] = pressure
    }

    fun latest(sessionId: String): LocalPromptPressure? = latest[sessionId]

    fun clear(sessionId: String) {
        latest.remove(sessionId)
    }
}
