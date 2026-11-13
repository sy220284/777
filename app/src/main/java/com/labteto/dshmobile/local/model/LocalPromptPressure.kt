package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.context.LocalRequestContextAssessment
import com.labteto.dshmobile.local.record
import java.util.LinkedHashMap
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
internal class LocalRequestPressureStore(
    maxSessions: Int = 128,
) {
    private data class MutableWindow(
        var generation: Int = 1,
        var prefillTokens: Long = 0L,
        var prefillSource: String = "estimated",
        var lastInputTokens: Int = 0,
        var peakInputTokens: Int = 0,
        var serverObserved: Boolean = false,
    )

    private data class SessionEntry(
        var latest: LocalPromptPressure? = null,
        val latestContextAssessmentByMode: MutableMap<LocalUsageMode, LocalRequestContextAssessment> = mutableMapOf(),
        val latestRequestPressureByMode: MutableMap<LocalUsageMode, LocalPromptPressure> = mutableMapOf(),
        val latestSourcePressureByMode: MutableMap<LocalUsageMode, LocalPromptPressure> = mutableMapOf(),
        val window: MutableWindow = MutableWindow(),
    )

    private data class ModelEstimateCalibration(var samples: Int = 0, var scale: Double = 1.0)
    private val routeCalibrations = object : LinkedHashMap<String, ModelEstimateCalibration>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ModelEstimateCalibration>?,
        ): Boolean = size > 64
    }

    private val capacity = maxSessions.coerceAtLeast(1)
    private val sessions = object : LinkedHashMap<String, SessionEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SessionEntry>?,
        ): Boolean = size > capacity
    }

    @Synchronized
    fun record(
        sessionId: String,
        pressure: LocalPromptPressure,
        contextAssessment: LocalRequestContextAssessment? = null,
        usageMode: LocalUsageMode? = null,
        sourcePressure: LocalPromptPressure? = null,
    ) {
        val entry = sessions.getOrPut(sessionId) { SessionEntry() }
        entry.latest = pressure
        usageMode?.let { mode ->
            entry.latestRequestPressureByMode[mode] = pressure
            sourcePressure?.let { entry.latestSourcePressureByMode[mode] = it }
        }
        if (contextAssessment != null && usageMode != null) {
            entry.latestContextAssessmentByMode[usageMode] = contextAssessment
        }
        val window = entry.window
        if (window.prefillTokens <= 0L) window.prefillTokens = pressure.estimatedInputTokens.toLong()
        window.lastInputTokens = pressure.estimatedInputTokens
        window.peakInputTokens = maxOf(window.peakInputTokens, pressure.estimatedInputTokens)
    }

    @Synchronized
    fun recordReportedUsage(
        sessionId: String,
        inputTokens: Long,
        routeFingerprint: String? = null,
        rawEstimatedInputTokens: Int? = null,
    ) {
        if (inputTokens <= 0L) return
        val key = routeFingerprint?.takeIf(String::isNotBlank)
        val estimate = rawEstimatedInputTokens?.takeIf { it > 0 }
        if (key != null && estimate != null) {
            val observed = (inputTokens.toDouble() / estimate).coerceIn(0.70, 1.15)
            val calibration = routeCalibrations.getOrPut(key) { ModelEstimateCalibration() }
            calibration.scale = if (calibration.samples == 0) observed
                else calibration.scale * 0.75 + observed * 0.25
            calibration.samples = (calibration.samples + 1).coerceAtMost(Int.MAX_VALUE)
        }
        val window = sessions.getOrPut(sessionId) { SessionEntry() }.window
        if (!window.serverObserved) {
            window.prefillTokens = inputTokens
            window.prefillSource = "server"
            window.serverObserved = true
        }
    }

    /**
     * Only a soft planning input, never a model/provider hard context admission guard.
     * Require repeated same-credential-route observations before reducing Work compaction pressure.
     */
    @Synchronized
    fun forSoftProjection(routeFingerprint: String, pressure: LocalPromptPressure): LocalPromptPressure {
        val calibration = routeCalibrations[routeFingerprint] ?: return pressure
        if (calibration.samples < 4) return pressure
        fun scaled(tokens: Int): Int =
            if (tokens <= 0) 0 else (tokens * calibration.scale).toInt().coerceAtLeast(1)
        return pressure.copy(
            estimatedInputTokens = scaled(pressure.estimatedInputTokens),
            systemTokens = scaled(pressure.systemTokens),
            historyTokens = scaled(pressure.historyTokens),
            currentUserTokens = scaled(pressure.currentUserTokens),
            toolDefinitionTokens = scaled(pressure.toolDefinitionTokens),
        )
    }

    @Synchronized
    fun softProjectionScale(
        routeFingerprint: String,
        rawEstimatedInputTokens: Int = 0,
        operationalLimitTokens: Int = Int.MAX_VALUE,
    ): Double {
        // Above 85% of the raw context boundary, soft planning must not discount tokens.
        if (rawEstimatedInputTokens.toDouble() >= operationalLimitTokens.coerceAtLeast(1) * 0.85) {
            return 1.0
        }
        val calibration = routeCalibrations[routeFingerprint] ?: return 1.0
        return if (calibration.samples >= 4) calibration.scale else 1.0
    }

    @Synchronized
    fun routeCalibration(routeFingerprint: String): Pair<Int, Int>? =
        routeCalibrations[routeFingerprint]?.let {
            it.samples to (it.scale * 1_000).toInt()
        }

    @Synchronized
    fun advanceGeneration(sessionId: String, estimatedTokensAfter: Int) {
        val window = sessions.getOrPut(sessionId) { SessionEntry() }.window
        window.generation += 1
        window.prefillTokens = estimatedTokensAfter.coerceAtLeast(0).toLong()
        window.prefillSource = "estimated"
        window.lastInputTokens = estimatedTokensAfter.coerceAtLeast(0)
        window.peakInputTokens = estimatedTokensAfter.coerceAtLeast(0)
        window.serverObserved = false
    }

    @Synchronized
    fun latest(sessionId: String): LocalPromptPressure? = sessions[sessionId]?.latest

    @Synchronized
    fun latest(sessionId: String, usageMode: LocalUsageMode): LocalPromptPressure? =
        sessions[sessionId]?.latestRequestPressureByMode?.get(usageMode)

    @Synchronized
    fun latestSource(sessionId: String, usageMode: LocalUsageMode): LocalPromptPressure? =
        sessions[sessionId]?.latestSourcePressureByMode?.get(usageMode)

    @Synchronized
    fun latestWork(sessionId: String): LocalPromptPressure? =
        latest(sessionId, LocalUsageMode.WORK)

    @Synchronized
    fun latestWorkSource(sessionId: String): LocalPromptPressure? =
        latestSource(sessionId, LocalUsageMode.WORK)

    @Synchronized
    fun contextAssessment(
        sessionId: String,
        usageMode: LocalUsageMode,
    ): LocalRequestContextAssessment? =
        sessions[sessionId]?.latestContextAssessmentByMode?.get(usageMode)

    @Synchronized
    fun workAssessment(sessionId: String): LocalRequestContextAssessment? =
        contextAssessment(sessionId, LocalUsageMode.WORK)

    @Synchronized
    fun window(sessionId: String): LocalContextWindowSnapshot? = sessions[sessionId]?.window?.let { value ->
        LocalContextWindowSnapshot(
            generation = value.generation,
            prefillTokens = value.prefillTokens,
            prefillSource = value.prefillSource,
            lastInputTokens = value.lastInputTokens,
            peakInputTokens = value.peakInputTokens,
        )
    }

    @Synchronized
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    @Synchronized
    internal fun trackedSessionCount(): Int = sessions.size
}
