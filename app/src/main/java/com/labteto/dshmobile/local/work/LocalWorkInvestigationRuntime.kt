package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Work's evidence-to-decision bridge. An observation is a bounded projection of a
 * real, settled tool result; it makes no claim that the source was fully inspected.
 * All facts live in the existing Session EventLog, with no additional model call.
 */
internal object LocalWorkInvestigationRuntime {
    private const val SOURCE_EVENT = "work/source-observed"
    private const val FINDING_EVENT = "work/finding"
    private const val WINDOW = 320
    private val searchTools = setOf("grep", "glob", "list_files", "web_search")
    private val partialTools = setOf("read", "read_chunk", "tool_output_read")
    private val documentTools = setOf("web_fetch", "file_inspect")

    private fun JsonObject.text(key: String): String =
        (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.number(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull

    private fun sourceKind(name: String): String? = when {
        name in searchTools || name == "mcp__GitHub__search" -> "search"
        name in partialTools || name == "mcp__GitHub__fetch_file" -> "slice"
        name in documentTools || name == "mcp__GitHub__fetch_pr" ||
            name == "mcp__GitHub__fetch_pr_file_patch" -> "document"
        else -> null
    }

    private fun sourceName(name: String, args: JsonObject): String {
        val path = args.text("path").ifBlank { args.text("file_path") }
        val repo = args.text("repository_full_name").ifBlank { args.text("repo_full_name") }
        val ref = args.text("ref").take(48)
        val url = args.text("url").substringBefore('?').substringBefore('#')
        val value = when {
            name == "tool_output_read" -> "结果回执:" + args.text("call_id")
            repo.isNotBlank() && path.isNotBlank() ->
                if (ref.isBlank()) "$repo/$path" else "$repo@$ref/$path"
            path.isNotBlank() -> path
            url.isNotBlank() -> url
            repo.isNotBlank() -> repo
            else -> name
        }
        return value.replace("\n", " ").take(160)
    }

    private fun region(kind: String, args: JsonObject): String {
        if (kind == "search") return "search-only"
        val first = args.number("start_line")
        val last = args.number("end_line")
        if (first != null || last != null) return "lines:${first ?: 1}-${last ?: "default"}"
        val offset = args.number("start_byte")
        val max = args.number("max_bytes")
        if (offset != null || max != null) return "bytes:${offset ?: 0}+${max ?: "default"}"
        return if (kind == "slice") "range-unspecified" else "document"
    }

    private fun digest(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }

    private fun hasContinuationMarker(name: String, output: String): Boolean {
        if (name !in partialTools && name !in searchTools && name != "mcp__GitHub__fetch_file") return false
        val tail = output.takeLast(1_200)
        return Regex("(?i)(next_start_line|next_start_char|next_byte|next_cursor|next_offset)\\s*[:=]\\s*[\\\"']?[^\\s,}]+")
            .containsMatchIn(tail)
    }

    fun observe(
        log: LocalSessionEventLog,
        goalId: String,
        sourceMessageId: String?,
        callId: String,
        toolName: String,
        args: JsonObject,
        toolDeclaredSequence: Long?,
        resultSequence: Long,
        output: String,
        isError: Boolean,
    ) {
        if (isError || toolDeclaredSequence == null) return
        val kind = sourceKind(toolName) ?: return
        val source = sourceName(toolName, args)
        // Without a stable document or file identity, two unrelated read calls
        // must not appear to be a continuation of the same source.
        if (kind == "slice" && (source == toolName ||
                (toolName == "tool_output_read" && args.text("call_id").isBlank()))) return
        val region = region(kind, args)
        log.append(SOURCE_EVENT, buildJsonObject {
            put("goal_id", goalId)
            put("source_message_id", sourceMessageId.orEmpty())
            put("source_call_id", callId)
            put("tool_declared_sequence", toolDeclaredSequence)
            put("result_sequence", resultSequence)
            put("tool_name", toolName)
            put("source", source)
            put("region", region)
            put("kind", kind)
            put("output_fingerprint", digest(output))
            put("output_chars", output.length)
            put("has_next", hasContinuationMarker(toolName, output))
        })
    }

    private fun scoped(
        log: LocalSessionEventLog,
        goalId: String,
        sourceMessageId: String?,
    ): List<LocalSessionEventLog.Event> = log.pageBeforeChronological(limit = WINDOW)
        .filter {
            it.type in setOf(SOURCE_EVENT, FINDING_EVENT) && if (goalId.isNotBlank()) {
                it.data.text("goal_id") == goalId
            } else {
                sourceMessageId?.isNotBlank() == true &&
                    it.data.text("source_message_id") == sourceMessageId
            }
        }

    /** Dynamic facts are injected into the *next* model request, not durable model history. */
    fun context(log: LocalSessionEventLog, goalId: String, sourceMessageId: String?): String {
        val events = scoped(log, goalId, sourceMessageId)
        if (events.isEmpty()) return ""
        val lastFindings = linkedMapOf<String, JsonObject>()
        val recentSources = linkedMapOf<String, JsonObject>()
        // Important findings outlive the bounded recent-tool display window.
        // Stream only finding events through the indexed event ledger, retaining
        // the latest revision of each item including explicit rejection.
        if (goalId.isNotBlank()) {
            log.forEachOfTypesAfter(-1L, setOf(FINDING_EVENT)) { event ->
                val value = event.data
                val id = value.text("finding_id")
                if (value.text("goal_id") == goalId && id.isNotBlank()) {
                    lastFindings[id] = value
                }
            }
        }
        events.forEach { event ->
            val value = event.data
            if (event.type == FINDING_EVENT) {
                val id = value.text("finding_id")
                if (id.isNotBlank()) lastFindings[id] = value
            } else {
                val identity = value.text("source") + "|" + value.text("region")
                recentSources[identity] = value
            }
        }
        val findings = lastFindings.values.filter { it.text("status") != "rejected" }.takeLast(4)
        val sources = recentSources.values.toList().takeLast(5)
        // The latest reading of a source decides whether a continuation cursor is still open.
        // Earlier pages must not keep requesting a cursor after a later page finished.
        val latestBySource = linkedMapOf<String, JsonObject>()
        recentSources.values.forEach { value ->
            if (value.text("kind") != "search") latestBySource[value.text("source")] = value
        }
        val pendingReads = latestBySource.values.filter { it.text("has_next") == "true" }.takeLast(2)
        if (findings.isEmpty() && sources.isEmpty()) return ""
        return buildString {
            appendLine("【当前调查事实：以下来源仅证明已执行的读取范围，不能推断整个文件或全部数据已检查】")
            sources.forEach { value ->
                append("- ").append(value.text("source"))
                    .append(" [").append(value.text("region")).append("] ")
                    .append(if (value.text("kind") == "search") "仅定位线索" else "已读取相关范围")
                if (value.text("has_next") == "true") append("；返回了可继续读取的游标")
                append("；调用 ").append(value.text("source_call_id")).appendLine()
            }
            if (pendingReads.isNotEmpty()) {
                appendLine("【仍有未读完的材料】")
                pendingReads.forEach { value ->
                    append("- ").append(value.text("source")).append(" [")
                        .append(value.text("region")).appendLine("]：优先使用来源工具返回的下一页游标继续读取。")
                }
            }
            if (sources.isNotEmpty() && sources.all { it.text("kind") == "search" }) {
                appendLine("【来源覆盖不足】目前只有检索定位，还没有读取原始内容；需要据此判断时先补读。")
            }
            findings.forEach { value ->
                append("- 调查发现 ").append(value.text("finding_id"))
                    .append(" [").append(value.text("status")).append("] ")
                    .append(value.text("summary").take(280))
                val action = value.text("next_action")
                if (action.isNotBlank()) append("；待调查：").append(action.take(150))
                appendLine()
            }
            append("发现可能改变任务结论的异常时，优先用现有工具补读、交叉核查并更新线索；")
            append("在原授权内调整 todo/plan，必要时修订已有任务图。")
            append("根据真实变化向用户自然解释重要发现，避免逐条播报工具调用。")
        }.take(2_400)
    }

    /**
     * A failed read may be followed by another successful read after recovery.
     * All failed tools in the current turn must be known, read-only and settled.
     * Unknown or write-like effects are still rejected by the existing Goal
     * recovery ledger; this signal only removes the overly broad failure veto.
     */
    fun recoveredReadOnlyFailure(log: LocalSessionEventLog): Boolean {
        val window = log.pageBeforeChronological(limit = WINDOW)
        val turnStart = window.lastOrNull { it.type == "turn/start" }?.sequence ?: return false
        val results = window.filter { it.type == "tool/result" && it.sequence > turnStart }
        val failures = results.filter { it.data.text("is_error") == "true" }
        if (failures.isEmpty()) return false
        if (failures.any { result ->
                result.data.text("side_effect") != "none" ||
                    result.data.text("error_code") == "TOOL_OUTCOME_UNKNOWN" ||
                    sourceKind(result.data.text("name")) == null
            }) return false
        val lastFailure = failures.maxOf { it.sequence }
        return results.any { result ->
            result.sequence > lastFailure &&
                result.data.text("is_error") == "false" &&
                result.data.text("side_effect") == "none" &&
                sourceKind(result.data.text("name")) != null
        }
    }

    /**
     * Progress identity reflects distinct evidence actually inspected and the latest
     * state of each finding, not elapsed time or a fresh call ID. Repeating the same
     * range/output cannot reset the stalled-work continuation guard.
     */
    fun progressIdentity(log: LocalSessionEventLog, goalId: String): String {
        if (goalId.isBlank()) return ""
        val observations = linkedSetOf<String>()
        val findings = linkedMapOf<String, String>()
        // A rolling display window is insufficient for a durable progress fence:
        // once 320 other events arrive it would silently forget valid discoveries
        // and misclassify old work as fresh progress. Stream the indexed types
        // across the whole Goal instead; no model text or tool content is copied.
        log.forEachOfTypesAfter(-1L, setOf(SOURCE_EVENT, FINDING_EVENT)) { event ->
            val value = event.data
            if (value.text("goal_id") != goalId) return@forEachOfTypesAfter
            if (event.type == SOURCE_EVENT) {
                observations += value.text("source") + "|" + value.text("region") +
                    "|" + value.text("output_fingerprint")
            } else {
                val id = value.text("finding_id")
                if (id.isNotBlank()) findings[id] = value.text("status") + "|" +
                    value.text("summary") + "|" + value.text("source_call_id")
            }
        }
        if (observations.isEmpty() && findings.isEmpty()) return ""
        return digest(observations.sorted().joinToString("\n") + "\n" +
            findings.toSortedMap().entries.joinToString("\n") { it.key + ":" + it.value })
    }
}
