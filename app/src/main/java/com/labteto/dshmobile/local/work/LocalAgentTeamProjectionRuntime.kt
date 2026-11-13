package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLog
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json

@Serializable
private data class TeamProjectionCheckpointState(val sessionId: String, val state: LocalTeamProjection)

internal class LocalTeamProjectionRebuilding : IllegalStateException("团队历史正在恢复，请稍候")

/** Bounded batches and restart checkpoints; no replay occurs under the global Team command lock. */
internal class LocalAgentTeamProjectionRuntime(
    private val version: Int,
    private val reduce: (String, LocalTeamProjection, LocalSessionEventLog.Event) -> LocalTeamProjection,
    private val scope: CoroutineScope?,
    private val onReady: (String) -> Unit,
) {
    private data class Key(val sessionId: String, val log: LocalSessionEventLog)
    private class Entry(val generation: Long) {
        var initialized = false
        var cursor = -1L
        var state = LocalTeamProjection()
        var rebuild: Job? = null
    }

    private val entries = object : LinkedHashMap<Key, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean {
            if (size <= 32) return false
            eldest.value.rebuild?.cancel()
            return true
        }
    }

    fun project(sessionId: String, log: LocalSessionEventLog): LocalTeamProjection {
        val entry = entryFor(sessionId, log)
        val complete = batch(sessionId, log, entry)
        if (complete != null) return complete
        synchronized(entry) {
            if (scope != null && entry.rebuild?.isActive != true) {
                entry.rebuild = scope.launch(Dispatchers.IO) {
                    awaitReady(sessionId, log)
                    if (!log.isClosed) onReady(sessionId)
                }
            }
        }
        throw LocalTeamProjectionRebuilding()
    }

    suspend fun awaitReady(sessionId: String, log: LocalSessionEventLog) = withContext(Dispatchers.IO) {
        while (true) {
            if (log.isClosed) throw CancellationException("会话已关闭，团队恢复已取消")
            if (batch(sessionId, log, entryFor(sessionId, log)) != null) break
            yield()
        }
    }

    private fun entryFor(sessionId: String, log: LocalSessionEventLog): Entry = synchronized(entries) {
        check(!log.isClosed) { "会话已关闭，团队恢复已取消" }
        val key = Key(sessionId, log)
        entries[key]?.takeIf { it.generation == log.resetGeneration }
            ?: Entry(log.resetGeneration).also { entries[key] = it }
    }

    private fun batch(sessionId: String, log: LocalSessionEventLog, entry: Entry): LocalTeamProjection? = synchronized(entry) {
        if (entry.generation != log.resetGeneration) return@synchronized null
        if (!entry.initialized) {
            log.readProjectionCheckpoint(NAME, version)?.let { checkpoint ->
                try {
                    val saved = Json.decodeFromString<TeamProjectionCheckpointState>(checkpoint.payload)
                    require(saved.sessionId == sessionId) { "团队检查点属于其他会话" }
                    val state = saved.state
                    require(state.asOfSequence <= checkpoint.throughSequence)
                    entry.cursor = checkpoint.throughSequence
                    entry.state = state
                } catch (error: Exception) {
                    AppLog.warn("AgentTeam", "忽略不可读取的团队派生检查点", error)
                }
            }
            entry.initialized = true
        }
        val latest = log.latestSequence()
        if (entry.cursor > latest) {
            entry.cursor = -1L
            entry.state = LocalTeamProjection()
        }
        val previousCursor = entry.cursor
        repeat(MAX_BATCH_PAGES) {
            if (entry.cursor >= latest) return@repeat
            val page = log.pageAfter(entry.cursor, PAGE_SIZE)
            if (page.isEmpty()) { entry.cursor = latest; return@repeat }
            for (event in page) {
                if (event.sequence > latest) break
                entry.state = reduce(sessionId, entry.state, event)
                entry.cursor = event.sequence
            }
        }
        if (entry.generation != log.resetGeneration) return@synchronized null
        if (entry.cursor != previousCursor) {
            try {
                log.writeProjectionCheckpoint(NAME, version, entry.cursor,
                    Json.encodeToString(TeamProjectionCheckpointState.serializer(), TeamProjectionCheckpointState(sessionId, entry.state)), entry.generation)
            } catch (error: Exception) {
                AppLog.warn("AgentTeam", "团队派生检查点写入失败，仍以原始事件为准", error)
            }
        }
        entry.state.takeIf { entry.cursor >= latest }
    }

    companion object {
        const val PAGE_SIZE = 160
        const val MAX_BATCH_PAGES = 4
        private const val NAME = "work.agent-team"
    }
}
