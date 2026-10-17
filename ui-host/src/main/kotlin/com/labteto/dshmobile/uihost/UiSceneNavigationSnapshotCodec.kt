package com.labteto.dshmobile.uihost

import com.labteto.dshmobile.uicontract.UiSceneId
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Data-only, versioned Host back-stack memento. It carries neither executable actions nor
 * capability claims. Decode is intentionally permissive about known IDs; the *current*
 * UiHostSceneNavigation.restore() must validate the pack, scene catalog and legal history.
 *
 * A stale or corrupt snapshot fails closed and must never silently route to V6.
 */
object UiSceneNavigationSnapshotCodec {
    const val MAX_STACK_DEPTH: Int = 32
    private const val MAX_ENCODED_LENGTH = 8_192
    private const val MAX_PACK_BYTES = 128
    private const val MAX_SCENE_BYTES = 256
    private const val VERSION = "ui-nav-v1"

    fun encode(snapshot: UiSceneNavigationState): String {
        require(snapshot.stack.size in 1..MAX_STACK_DEPTH) { "Invalid navigation depth" }
        fun item(value: String, limit: Int): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(value.isNotBlank() && bytes.size <= limit && bytes.none { it == 0.toByte() }) {
                "Invalid navigation identity"
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        val encoded = (listOf(VERSION, item(snapshot.packId, MAX_PACK_BYTES)) +
            snapshot.stack.map { item(it.value, MAX_SCENE_BYTES) }).joinToString(".")
        require(encoded.length <= MAX_ENCODED_LENGTH) { "Navigation snapshot budget exceeded" }
        return encoded
    }

    /** Untrusted SavedState/Bundle input: malformed, oversized and unknown versions are rejected. */
    fun decodeOrNull(encoded: String?): UiSceneNavigationState? {
        if (encoded.isNullOrEmpty() || encoded.length > MAX_ENCODED_LENGTH) return null
        val parts = encoded.split('.')
        if (parts.firstOrNull() != VERSION || parts.size !in 3..(MAX_STACK_DEPTH + 2))
            return null
        fun item(raw: String, limit: Int): String? = runCatching {
            if (raw.isEmpty()) return null
            val bytes = Base64.getUrlDecoder().decode(raw)
            if (bytes.isEmpty() || bytes.size > limit || bytes.any { it == 0.toByte() }) return null
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != raw) return null
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
        val packId = item(parts[1], MAX_PACK_BYTES) ?: return null
        val scenes = parts.drop(2).map { item(it, MAX_SCENE_BYTES) ?: return null }
        return UiSceneNavigationState(packId, scenes.map(::UiSceneId))
    }
}
