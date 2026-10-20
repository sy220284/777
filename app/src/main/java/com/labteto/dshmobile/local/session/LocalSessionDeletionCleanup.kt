package com.labteto.dshmobile.local.session

import java.io.File
import java.io.FileOutputStream

/**
 * A durable deletion intent is written before unlinking a session. If the process dies
 * after the session disappears but before its event/tool sidecars are cleared, startup
 * can finish only those explicitly requested deletions.
 */
internal class LocalSessionDeletionCleanup(
    private val sessionsRoot: File,
    private val clearEvents: (Set<String>) -> Unit,
    private val clearToolOutputs: (String) -> Unit,
) {
    private val journal = File(sessionsRoot, ".pending-deletions")

    fun markIntent(id: String) {
        require(VALID_ID.matches(id)) { "非法会话编号：$id" }
        check(journal.isDirectory || journal.mkdirs()) { "无法创建会话删除记录目录" }
        FileOutputStream(File(journal, "$id.pending")).use { it.fd.sync() }
    }

    fun cleanupCommitted(id: String) {
        require(VALID_ID.matches(id)) { "非法会话编号：$id" }
        check(!hasSnapshot(id)) { "会话快照仍存在，禁止清除关联数据：$id" }
        clearEvents(setOf(id))
        clearToolOutputs(id)
        sessionsRoot.listFiles().orEmpty()
            .filter { entry ->
                entry.name == "$id.events.jsonl" ||
                    entry.name == "$id.events.jsonl.writer.lock" ||
                    entry.name.startsWith("$id.events.jsonl.part-") ||
                    entry.name.startsWith("$id.events.jsonl.projection-")
            }
            .forEach { entry ->
                check(entry.delete() || !entry.exists()) { "会话事件文件删除失败：${entry.name}" }
            }
        // These recovery/summary copies are also private session data. Partial unlink on the
        // preceding process must never leave them outside the completed deletion receipt.
        sessionsRoot.listFiles().orEmpty()
            .filter { entry ->
                entry.name.startsWith("$id.checkpoint-v") ||
                    entry.name.startsWith("$id.corrupt-")
            }
            .forEach { entry ->
                check(entry.delete() || !entry.exists()) { "会话历史副本删除失败：${entry.name}" }
            }
        val summaries = File(sessionsRoot, ".summaries")
        listOf(File(summaries, "$id.summary"), File(summaries, "$id.summary.tmp"))
            .forEach { entry ->
                check(!entry.exists() || entry.delete()) { "会话摘要清理失败：${entry.name}" }
            }
        val marker = File(journal, "$id.pending")
        check(!marker.exists() || marker.delete()) { "无法清除已完成的会话删除记录：$id" }
    }

    /** Return errors to the caller so boot can surface a real retryable failure. */
    fun recoverPending(): List<Pair<String, Throwable>> {
        val failures = mutableListOf<Pair<String, Throwable>>()
        journal.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".pending") }
            .forEach { marker ->
                val id = marker.name.removeSuffix(".pending")
                if (!VALID_ID.matches(id)) return@forEach
                // A failed primary deletion must never consume a recoverable session's history.
                if (hasSnapshot(id)) return@forEach
                try {
                    cleanupCommitted(id)
                } catch (failure: Exception) {
                    failures += id to failure
                }
            }
        return failures
    }

    private fun hasSnapshot(id: String): Boolean =
        File(sessionsRoot, "$id.json").exists() ||
            File(sessionsRoot, "$id.backup.json").exists()

    private companion object {
        val VALID_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
