package com.labteto.dshmobile.ui.screens.local

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TEXT_KEY_PREFIX = "text:"
private const val FILES_KEY_PREFIX = "files:"

/**
 * Per-session drafts are stored in the app's private SharedPreferences, not in a
 * twelve-item UI LRU or a potentially oversized saved-instance-state Bundle.
 * Saved state remains a migration path for drafts opened before this storage existed.
 */
internal class LocalSessionDraftCache {
    private val values = mutableStateMapOf<String, String>()
    private var preferences: SharedPreferences? = null

    operator fun get(sessionId: String): String? = values[sessionId]

    fun attach(store: SharedPreferences) {
        if (preferences === store) return
        val pending = values.toMap()
        store.all.forEach { (key, raw) ->
            if (key.startsWith(TEXT_KEY_PREFIX) && raw is String) {
                val id = key.removePrefix(TEXT_KEY_PREFIX)
                if (id.isNotBlank() && id !in pending && raw.isNotBlank()) values[id] = raw
            }
        }
        preferences = store
        val edit = store.edit()
        pending.forEach { (id, text) ->
            if (id.isNotBlank()) {
                if (text.isBlank()) edit.remove(TEXT_KEY_PREFIX + id)
                else edit.putString(TEXT_KEY_PREFIX + id, text)
            }
        }
        edit.apply()
    }

    fun putLocalDraft(sessionId: String, value: String) {
        if (value.isBlank()) values.remove(sessionId) else values[sessionId] = value
        if (sessionId.isNotBlank()) {
            preferences?.edit()?.apply {
                if (value.isBlank()) remove(TEXT_KEY_PREFIX + sessionId)
                else putString(TEXT_KEY_PREFIX + sessionId, value)
            }?.apply()
        }
    }

    // After persistent storage attaches, keep large drafts out of Android's Bundle.
    fun save(): List<String> = if (preferences != null) emptyList() else
        values.entries.flatMap { (id, text) -> listOf(id, text) }

    companion object {
        fun restore(saved: List<String>): LocalSessionDraftCache =
            LocalSessionDraftCache().apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2) putLocalDraft(pair[0], pair[1])
                }
            }
    }
}

/** Session-scoped references. File contents remain in the existing local attachment store. */
internal class LocalSessionAttachmentDraftCache {
    private val values = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateList<LocalImportedAttachment>>()
    private var preferences: SharedPreferences? = null

    fun attach(store: SharedPreferences) {
        if (preferences === store) return
        val pending = values.mapValues { it.value.toList() }
        store.all.forEach { (key, raw) ->
            if (key.startsWith(FILES_KEY_PREFIX) && raw is String) {
                val id = key.removePrefix(FILES_KEY_PREFIX)
                if (id.isNotBlank() && id !in pending) {
                    decodeValid(raw).takeIf { it.isNotEmpty() }?.let { files ->
                        values[id] = mutableStateListOf<LocalImportedAttachment>().apply { addAll(files) }
                    }
                }
            }
        }
        preferences = store
        val edit = store.edit()
        pending.forEach { (id, files) ->
            if (id.isNotBlank()) {
                if (files.isEmpty()) edit.remove(FILES_KEY_PREFIX + id)
                else edit.putString(FILES_KEY_PREFIX + id, Json.encodeToString(files))
            }
        }
        edit.apply()
    }

    fun forSession(sessionId: String): androidx.compose.runtime.snapshots.SnapshotStateList<LocalImportedAttachment> =
        values.getOrPut(sessionId) { mutableStateListOf() }

    /** Called from snapshotFlow for the current session after actual attachment mutations. */
    fun persistSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val store = preferences ?: return
        val files = values[sessionId]?.toList().orEmpty()
        val edit = store.edit()
        if (files.isEmpty()) edit.remove(FILES_KEY_PREFIX + sessionId)
        else edit.putString(FILES_KEY_PREFIX + sessionId, Json.encodeToString(files))
        edit.apply()
    }

    fun save(): List<String> = if (preferences != null) emptyList() else
        values.entries.flatMap { (id, files) ->
            if (files.isEmpty()) emptyList() else listOf(id, Json.encodeToString(files.toList()))
        }

    companion object {
        private fun decodeValid(raw: String): List<LocalImportedAttachment> {
            val parsed = runCatching {
                Json.decodeFromString<List<LocalImportedAttachment>>(raw)
            }.getOrNull().orEmpty()
            return parsed.filter { entry ->
                val path = entry.relativePath
                path.length in 1..2048 && !path.startsWith('/') &&
                    !path.contains('\\') && !path.contains(':') &&
                    path.none { it.code == 0 } &&
                    path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                    entry.bytes >= 0L && entry.name.isNotBlank() &&
                    entry.mediaType.isNotBlank()
            }.distinctBy { it.relativePath }
        }

        fun restore(saved: List<String>): LocalSessionAttachmentDraftCache =
            LocalSessionAttachmentDraftCache().apply {
                saved.chunked(2).forEach { pair ->
                    if (pair.size == 2 && pair[0].isNotBlank()) {
                        forSession(pair[0]).addAll(decodeValid(pair[1]))
                    }
                }
            }
    }
}
