package com.labteto.dshmobile.local.context

import java.security.MessageDigest

internal enum class PromptLayerStability {
    STABLE,
    DYNAMIC,
    TURN_ONLY,
}

internal data class PromptLayer(
    val id: String,
    val priority: Int,
    val stability: PromptLayerStability,
    val content: String,
)

internal data class PromptLayerComposition(
    val stable: String,
    val dynamic: String,
    val stableFingerprint: String,
)

/**
 * Neutral prompt ordering/cache helper.
 *
 * Feature code owns the meaning of every layer. This composer only keeps stable layers in a
 * deterministic prefix and dynamic/turn-only material at the tail.
 */
internal class PromptLayerComposer {
    fun compose(layers: List<PromptLayer>): PromptLayerComposition {
        val normalized = layers
            .filter { it.content.isNotBlank() }
            .sortedWith(compareBy<PromptLayer> { it.priority }.thenBy { it.id })
        val stable = normalized
            .filter { it.stability == PromptLayerStability.STABLE }
            .joinToString("\n\n") { it.content.trim() }
        val dynamic = normalized
            .filter { it.stability != PromptLayerStability.STABLE }
            .joinToString("\n\n") { it.content.trim() }
        return PromptLayerComposition(
            stable = stable,
            dynamic = dynamic,
            stableFingerprint = sha256(stable),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
