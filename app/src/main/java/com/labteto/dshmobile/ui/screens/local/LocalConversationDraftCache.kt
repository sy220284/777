package com.labteto.dshmobile.ui.screens.local

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val MAX_LOCAL_SESSION_DRAFTS = 12

/**
 * Small UI-only LRU for per-session composer drafts.
 *
 * SnapshotStateMap deliberately does not define iteration order, so recency is tracked separately
 * and persisted in that order. Blank drafts release their entry immediately.
 */
internal class LocalSessionDraftCache {
    private val values = mutableStateMapOf<String, String>()
    private val recency = mutableStateListOf<String>()

    operator fun get(sessionId: String): String? = values[sessionId]

    fun putBoundedLocalDraft(sessionId: String, value: String) {
        recency.remove(sessionId)
        if (value.isBlank()) {
            values.remove(sessionId)
            return
        }
        values[sessionId] = value
        recency += sessionId
        while (recency.size > MAX_LOCAL_SESSION_DRAFTS) {
            values.remove(recency.removeAt(0))
        }
    }

    fun save(): List<String> = recency.flatMap { sessionId ->
        values[sessionId]?.let { value -> listOf(sessionId, value) }.orEmpty()
    }

    companion object {
        fun restore(saved: List<String>): LocalSessionDraftCache =
            LocalSessionDraftCache().apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2) putBoundedLocalDraft(pair[0], pair[1])
                }
            }
    }
}


/**
 * Original UI 3.0's useful per-session attachment draft behavior, owned here by the native
 * V6 composer instead of a UI Pack/Host. Keeps source references session-scoped and bounded.
 */
internal class LocalSessionAttachmentDraftCache {
    private val values = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<LocalImportedAttachment>>()
    private val recency = mutableStateListOf<String>()

    fun forSession(sessionId: String): androidx.compose.runtime.snapshots.SnapshotStateList<LocalImportedAttachment> {
        values[sessionId]?.let { return it }
        val entries = mutableStateListOf<LocalImportedAttachment>()
        values[sessionId] = entries
        recency += sessionId
        while (recency.size > MAX_LOCAL_SESSION_DRAFTS) {
            values.remove(recency.removeAt(0))
        }
        return entries
    }

    fun save(): List<String> = recency.flatMap { sessionId ->
        values[sessionId]?.takeIf { it.isNotEmpty() }?.let { entries ->
            listOf(sessionId, Json.encodeToString(entries.toList()))
        }.orEmpty()
    }

    companion object {
        fun restore(saved: List<String>): LocalSessionAttachmentDraftCache =
            LocalSessionAttachmentDraftCache().apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size != 2 || pair[0].isBlank()) return@forEach
                    val restored = runCatching {
                        Json.decodeFromString<List<LocalImportedAttachment>>(pair[1])
                    }.getOrNull().orEmpty()
                    val valid = restored.filter { entry ->
                        val path = entry.relativePath
                        path.length in 1..2048 && !path.startsWith('/') &&
                            !path.contains('\\') && !path.contains(':') &&
                            path.none { it.code == 0 } &&
                            path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                            entry.bytes >= 0L && entry.name.isNotBlank() &&
                            entry.mediaType.isNotBlank()
                    }.distinctBy { it.relativePath }
                    if (valid.isNotEmpty()) forSession(pair[0]).addAll(valid)
                }
            }
    }
}
