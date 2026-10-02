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

internal data class LocalContextWindowSnapshot(
    val generation: Int,
    val prefillTokens: Long,
    val prefillSource: String,
    val lastInputTokens: Int,
    val peakInputTokens: Int,
)

/**
 * Last real request projection plus context-window generations.
 *
 * A generation advances only when model-visible history is materially compacted. The first
 * provider-reported input usage in a generation replaces the estimated prefill baseline.
 */
internal class LocalRequestPressureStore {
    private data class MutableWindow(
        var generation: Int = 1,
        var prefillTokens: Long = 0L,
        var prefillSource: String = "estimated",
        var lastInputTokens: Int = 0,
        var peakInputTokens: Int = 0,
        var serverObserved: Boolean = false,
    )

    private val latest = ConcurrentHashMap<String, LocalPromptPressure>()
    private val windows = ConcurrentHashMap<String, MutableWindow>()

    fun record(sessionId: String, pressure: LocalPromptPressure) {
        latest[sessionId] = pressure
        val window = windows.computeIfAbsent(sessionId) {
            MutableWindow(
                prefillTokens = pressure.estimatedInputTokens.toLong(),
                lastInputTokens = pressure.estimatedInputTokens,
                peakInputTokens = pressure.estimatedInputTokens,
            )
        }
        synchronized(window) {
            if (window.prefillTokens <= 0L) window.prefillTokens = pressure.estimatedInputTokens.toLong()
            window.lastInputTokens = pressure.estimatedInputTokens
            window.peakInputTokens = maxOf(window.peakInputTokens, pressure.estimatedInputTokens)
        }
    }

    fun recordReportedUsage(sessionId: String, inputTokens: Long) {
        if (inputTokens <= 0L) return
        val window = windows.computeIfAbsent(sessionId) { MutableWindow() }
        synchronized(window) {
            if (!window.serverObserved) {
                window.prefillTokens = inputTokens
                window.prefillSource = "server"
                window.serverObserved = true
            }
        }
    }

    fun advanceGeneration(sessionId: String, estimatedTokensAfter: Int) {
        val window = windows.computeIfAbsent(sessionId) { MutableWindow() }
        synchronized(window) {
            window.generation += 1
            window.prefillTokens = estimatedTokensAfter.coerceAtLeast(0).toLong()
            window.prefillSource = "estimated"
            window.lastInputTokens = estimatedTokensAfter.coerceAtLeast(0)
            window.peakInputTokens = estimatedTokensAfter.coerceAtLeast(0)
            window.serverObserved = false
        }
    }

    fun latest(sessionId: String): LocalPromptPressure? = latest[sessionId]

    fun window(sessionId: String): LocalContextWindowSnapshot? = windows[sessionId]?.let { value ->
        synchronized(value) {
            LocalContextWindowSnapshot(
                generation = value.generation,
                prefillTokens = value.prefillTokens,
                prefillSource = value.prefillSource,
                lastInputTokens = value.lastInputTokens,
                peakInputTokens = value.peakInputTokens,
            )
        }
    }

    fun clear(sessionId: String) {
        latest.remove(sessionId)
        windows.remove(sessionId)
    }
}
