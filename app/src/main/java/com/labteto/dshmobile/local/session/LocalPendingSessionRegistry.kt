package com.labteto.dshmobile.local.session

import android.content.Context
import com.labteto.dshmobile.local.LocalUsageMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pending editors have stable identities so encrypted UI drafts can be resumed after process death.
 * This small index is NOT the durable conversation catalog or a second transcript store.
 */
@Singleton
internal class LocalPendingSessionRegistry @Inject constructor(
    @ApplicationContext context: Context,
) {
    @Serializable
    private data class Entry(val id: String, val mode: LocalUsageMode)

    private val preferences = context.getSharedPreferences("local_pending_session_editors_v1", Context.MODE_PRIVATE)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): List<Entry> = preferences.getString("editors", null)?.let { encoded ->
        json.decodeFromString<List<Entry>>(encoded)
    }.orEmpty()

    fun entries(): Map<String, LocalUsageMode> = synchronized(lock) {
        read().associate { it.id to it.mode }
    }

    fun modeFor(id: String): LocalUsageMode? = synchronized(lock) {
        read().firstOrNull { it.id == id }?.mode
    }

    fun latestFor(mode: LocalUsageMode): String? = synchronized(lock) {
        read().lastOrNull { it.mode == mode }?.id
    }

    fun remember(id: String, mode: LocalUsageMode) = synchronized(lock) {
        require(id.isNotBlank())
        val next = read().filterNot { it.id == id } + Entry(id, mode)
        preferences.edit().putString("editors", json.encodeToString(next)).apply()
    }

    fun forget(ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val current = read()
        val remaining = current.filterNot { it.id in ids }
        if (remaining.size != current.size) {
            preferences.edit().putString("editors", json.encodeToString(remaining)).apply()
        }
    }
}
