package com.labteto.dshmobile.local.session

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persists only UI-filtered, visible Chat text to the existing Session EventLog.
 * Each chunk is append-only and numbered. No tokens are replayed as model history.
 */
internal class LocalChatStreamCheckpointWriter(
    private val log: LocalSessionEventLog,
    private val requestUid: String,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (Exception) -> Unit = {},
    private val intervalMs: Long = 3_000L,
    private val chunkChars: Int = 4_096,
    private val totalCharsLimit: Int = 262_144,
) {
    private val pending = StringBuilder()
    private var persistedChars = 0
    private var nextIndex = 0
    private var lastFlushedMs: Long? = null
    private var saturated = false

    init {
        require(requestUid.isNotBlank() && intervalMs >= 0 && chunkChars >= 2 && totalCharsLimit >= chunkChars)
    }

    fun append(delta: String) {
        if (delta.isEmpty() || saturated) return
        val available = totalCharsLimit - persistedChars - pending.length
        if (available <= 0) {
            saturated = true
            return
        }
        var accepted = minOf(available, delta.length)
        if (accepted < delta.length && accepted > 0 &&
            Character.isHighSurrogate(delta[accepted - 1]) &&
            Character.isLowSurrogate(delta[accepted])
        ) accepted--
        if (accepted > 0) pending.append(delta, 0, accepted)
        if (accepted < delta.length) saturated = true
        val now = clockMs()
        if (pending.length >= chunkChars || lastFlushedMs == null ||
            now - lastFlushedMs!! >= intervalMs) flush()
    }

    /** Call on success, interruption and cancellation before deleting the ephemeral preview. */
    fun flush() {
        val now = clockMs()
        while (pending.isNotEmpty()) {
            var size = minOf(chunkChars, pending.length)
            if (size < pending.length && Character.isHighSurrogate(pending[size - 1]) &&
                Character.isLowSurrogate(pending[size])) size--
            val content = pending.substring(0, size)
            try {
                log.append(CHAT_STREAM_CHECKPOINT_EVENT, buildJsonObject {
                    put("request_uid", requestUid)
                    put("mode", "chat")
                    put("chunk_index", nextIndex)
                    put("content", content)
                })
            } catch (failure: Exception) {
                onFailure(failure)
                return // Keep pending bytes for a later attempt; never fail the model stream.
            }
            pending.delete(0, size)
            persistedChars += size
            nextIndex++
            lastFlushedMs = now
        }
    }
}
