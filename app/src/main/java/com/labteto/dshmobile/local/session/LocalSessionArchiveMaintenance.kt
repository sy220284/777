package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.observability.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Gradually compress old rotated logs even if their session is never reopened. */
internal class LocalSessionArchiveMaintenance(
    private val root: File,
    private val json: Json,
    private val currentSessionId: () -> String,
) {
    suspend fun run() {
        val rawSegment = Regex("^(.+)\\.events\\.jsonl\\.part-[0-9]+$")
        while (true) {
            val ids = root.listFiles().orEmpty()
                .mapNotNull { rawSegment.matchEntire(it.name)?.groupValues?.get(1) }
                .filter { it != currentSessionId() }
                .distinct()
            if (ids.isEmpty()) return
            var archived = 0
            for (id in ids) {
                if (id == currentSessionId()) continue
                try {
                    archived += SessionEventLog(File(root, "$id.events.jsonl"), json)
                        .archiveLegacySegments()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.error("LocalSessionArchiveMaintenance", "session archive migration failed", error)
                    return // Keep the original data intact; retry after the next app start.
                }
                delay(100)
            }
            if (archived == 0) return
        }
    }

    companion object {
        private val archiveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingPaths = ConcurrentHashMap.newKeySet<String>()

        fun request(file: File, json: Json) {
            val key = file.absolutePath
            if (!pendingPaths.add(key)) return
            archiveScope.launch {
                var completed = false
                try {
                    val log = SessionEventLog(file, json)
                    while (log.archiveLegacySegments(limit = 16) > 0) {
                        delay(25)
                    }
                    completed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.error(
                        "LocalSessionArchiveMaintenance",
                        "session archive background compression failed",
                        error,
                    )
                } finally {
                    pendingPaths.remove(key)
                    if (completed && hasRawSegments(file)) request(file, json)
                }
            }
        }

        private fun hasRawSegments(file: File): Boolean {
            val parent = file.parentFile ?: return false
            val prefix = "${file.name}.part-"
            return parent.listFiles().orEmpty().any { candidate ->
                candidate.isFile &&
                    candidate.name.startsWith(prefix) &&
                    candidate.name.removePrefix(prefix).all(Char::isDigit)
            }
        }

        fun storageStatus(root: File): String {
            val bytes = root.listFiles().orEmpty().filter { it.isFile }.sumOf(File::length)
            val free = root.usableSpace
            val warning = if (bytes >= 256L * 1024 * 1024 || free < 512L * 1024 * 1024) {
                "；空间偏紧，请导出并清理不需要的会话"
            } else ""
            return "会话存储：${bytes / (1024 * 1024)} MiB，设备可用 ${free / (1024 * 1024)} MiB$warning"
        }
    }
}
