package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.agent.AgentToolCall
import java.security.MessageDigest

/**
 * A small run-local progress gate for soft-step extension.
 *
 * Resource health only answers whether the device can continue. This tracker answers whether the
 * Agent has produced new evidence since the previous extension. Repeating the same tool/result does
 * not buy more steps.
 */
internal class LocalAgentProgressTracker {
    private val seen = LinkedHashSet<String>()
    private var progressVersion = 0L
    private var claimedVersion = 0L

    @Synchronized
    fun recordToolResult(call: AgentToolCall, output: String, isError: Boolean) {
        val normalized = buildString {
            append(call.name)
            append('\u0000')
            append(call.arguments.toString())
            append('\u0000')
            append(if (isError) "error" else "ok")
            append('\u0000')
            append(output.take(2_048))
        }
        if (seen.add(sha256(normalized))) {
            progressVersion += 1
        }
    }

    /** A text-only model step can be useful progress even when no tool is involved. */
    @Synchronized
    fun recordAssistant(content: String, toolCallCount: Int) {
        if (toolCallCount != 0 || content.isBlank()) return
        val fingerprint = sha256("assistant\u0000" + content.take(2_048))
        if (seen.add(fingerprint)) {
            progressVersion += 1
        }
    }

    /** Claim progress exactly once for each extension window. */
    @Synchronized
    fun claimExtensionProgress(): Boolean {
        if (progressVersion <= claimedVersion) return false
        claimedVersion = progressVersion
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        progressVersion = progressVersion,
        claimedVersion = claimedVersion,
        uniqueEvidence = seen.size,
    )

    data class Snapshot(
        val progressVersion: Long,
        val claimedVersion: Long,
        val uniqueEvidence: Int,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
