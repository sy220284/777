package com.labteto.dshmobile.local.model

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class LocalPromptPrefixContinuity {
    COLD,
    CONTINUOUS,
    BROKEN,
}

internal data class LocalPromptPrefixAssessment(
    val continuity: LocalPromptPrefixContinuity,
    val generation: Int,
    val previousMessageCount: Int,
    val currentMessageCount: Int,
    val toolSurfaceStable: Boolean,
    val messagePrefixStable: Boolean,
)

/**
 * Tracks only hashes of successful model-visible requests. It never retains prompt text.
 *
 * A cache-sensitive request is continuous when the tool surface is byte-stable and the previous
 * successful message sequence is an exact prefix of the current sequence. This mirrors the
 * property provider prefix caches actually need while keeping diagnostics content-free.
 */
internal class LocalPromptCacheContinuityStore {
    private data class Entry(
        val generation: Int,
        val messageCount: Int,
        val messageFingerprint: String,
        val toolFingerprint: String,
    )

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_SERIES
    }

    @Synchronized
    fun assess(
        sessionId: String,
        routeFingerprint: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        nativeTools: JsonArray = JsonArray(emptyList()),
        diagnosticSurface: String = "main",
    ): LocalPromptPrefixAssessment {
        val key = seriesKey(sessionId, routeFingerprint, diagnosticSurface)
        val previous = entries[key]
            ?: return LocalPromptPrefixAssessment(
                continuity = LocalPromptPrefixContinuity.COLD,
                generation = 1,
                previousMessageCount = 0,
                currentMessageCount = messages.size,
                toolSurfaceStable = false,
                messagePrefixStable = false,
            )
        val toolsStable = previous.toolFingerprint == fingerprintToolSurface(tools, nativeTools)
        val prefixStable =
            messages.size >= previous.messageCount &&
                previous.messageFingerprint == fingerprintMessages(messages.take(previous.messageCount))
        val continuous = toolsStable && prefixStable
        return LocalPromptPrefixAssessment(
            continuity = if (continuous) {
                LocalPromptPrefixContinuity.CONTINUOUS
            } else {
                LocalPromptPrefixContinuity.BROKEN
            },
            generation = if (continuous) previous.generation else previous.generation + 1,
            previousMessageCount = previous.messageCount,
            currentMessageCount = messages.size,
            toolSurfaceStable = toolsStable,
            messagePrefixStable = prefixStable,
        )
    }

    @Synchronized
    fun recordSuccess(
        sessionId: String,
        routeFingerprint: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        generation: Int,
        nativeTools: JsonArray = JsonArray(emptyList()),
        diagnosticSurface: String = "main",
    ) {
        entries[seriesKey(sessionId, routeFingerprint, diagnosticSurface)] = Entry(
            generation = generation.coerceAtLeast(1),
            messageCount = messages.size,
            messageFingerprint = fingerprintMessages(messages),
            toolFingerprint = fingerprintToolSurface(tools, nativeTools),
        )
    }

    @Synchronized
    fun clearSession(sessionId: String) {
        val prefix = sessionId + "\u0000"
        entries.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    internal fun trackedSeriesCount(): Int = entries.size

    private fun seriesKey(sessionId: String, routeFingerprint: String, diagnosticSurface: String): String =
        sessionId + "\u0000" + routeFingerprint + "\u0000" + diagnosticSurface

    private fun fingerprintMessages(messages: List<JsonObject>): String =
        fingerprint(messages.joinToString("\u0000") { it.toString() })

    private fun fingerprintToolSurface(tools: JsonArray, nativeTools: JsonArray): String =
        fingerprint(buildJsonObject {
            put("schemas", tools)
            put("native_tools", nativeTools)
        }.toString())

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SERIES = 256
    }
}
