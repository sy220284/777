package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun semanticCompactionRequest(
    compaction: LocalHistoryCompaction,
): List<JsonObject> {
    val source = semanticCompactionSource(
        history = compaction.omittedHistory,
        extractiveFallback = compaction.summary,
    )
    return listOf(
        buildJsonObject {
            put("role", "system")
            put(
                "content",
                """
                你负责压缩一个长期工作智能体的较早历史。只保留会改变后续执行的事实，禁止编造。
                输出中文纯文本，固定使用以下六个标题，标题没有内容时写“无”：
                目标与需求：
                约束与边界：
                关键决定与阶段结论：
                失败尝试与风险：
                未完成事项：
                其他阶段进展：
                要保留具体文件名、命令结果、错误原因、用户明确要求和仍待验证的事项。
                不要复述聊天寒暄，不要写 Markdown 代码围栏，不要输出 <compacted-summary> 标签。
                """.trimIndent(),
            )
        },
        buildJsonObject {
            put("role", "user")
            put(
                "content",
                "【本地提取式兜底摘要】\n${compaction.summary}\n\n【较早模型历史】\n$source",
            )
        },
    )
}

internal fun semanticCompactionSource(
    history: List<JsonObject>,
    extractiveFallback: String,
    maxChars: Int = MAX_SEMANTIC_SOURCE_CHARS,
): String {
    if (history.isEmpty()) return extractiveFallback.take(maxChars)
    val rows = history.mapNotNull(::semanticHistoryRow)
    if (rows.isEmpty()) return extractiveFallback.take(maxChars)

    val prefix = "兜底摘要：\n${truncateWithoutSplittingSurrogatePair(extractiveFallback, FALLBACK_PREFIX_CHARS)}\n\n"
    var used = prefix.length
    val retained = ArrayDeque<String>()
    for (row in rows.asReversed()) {
        if (used + row.length + 1 > maxChars) break
        retained.addFirst(row)
        used += row.length + 1
    }
    return prefix + retained.joinToString("\n")
}

private fun semanticHistoryRow(message: JsonObject): String? {
    val role = message["role"]?.jsonPrimitive?.contentOrNull ?: return null
    val text = when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "input_text", "output_text" ->
                    obj["text"]?.jsonPrimitive?.contentOrNull
                "image", "image_url", "input_image" -> "[图片已省略]"
                else -> obj["text"]?.jsonPrimitive?.contentOrNull
            }
        }.joinToString("\n")
        else -> null
    }.orEmpty()

    val calls = (message["tool_calls"] as? JsonArray).orEmpty()
        .mapNotNull { raw ->
            val call = raw as? JsonObject ?: return@mapNotNull null
            val fn = call["function"] as? JsonObject ?: return@mapNotNull null
            fn["name"]?.jsonPrimitive?.contentOrNull
        }
        .take(12)
    val body = buildString {
        append(role)
        append(": ")
        if (text.isNotBlank()) {
            append(truncateWithoutSplittingSurrogatePair(text, MAX_ROW_CHARS))
        }
        if (calls.isNotEmpty()) {
            if (text.isNotBlank()) append(" ")
            append("[工具调用: ").append(calls.joinToString("、")).append("]")
        }
    }.trim()
    return body.takeIf { it.length > role.length + 1 }
}

internal fun sanitizeSemanticCompactionSummary(value: String): String? {
    val clean = value
        .replace("<compacted-summary>", "")
        .replace("</compacted-summary>", "")
        .trim()
        .takeIf(String::isNotBlank)
        ?: return null
    val required = listOf(
        "目标与需求",
        "约束与边界",
        "关键决定与阶段结论",
        "失败尝试与风险",
        "未完成事项",
        "其他阶段进展",
    )
    if (required.count(clean::contains) < 4) return null
    return truncateWithoutSplittingSurrogatePair(clean, MAX_SEMANTIC_SUMMARY_CHARS)
}

private const val MAX_SEMANTIC_SOURCE_CHARS = 120_000
private const val FALLBACK_PREFIX_CHARS = 12_000
private const val MAX_ROW_CHARS = 8_000
private const val MAX_SEMANTIC_SUMMARY_CHARS = 16_000
