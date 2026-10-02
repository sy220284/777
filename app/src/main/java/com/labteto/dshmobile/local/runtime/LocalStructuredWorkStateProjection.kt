package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun structuredWorkState(
    snapshot: LocalHarnessState,
    eventLog: LocalSessionEventLog,
): LocalStructuredWorkState {
    if (snapshot.usageMode != LocalUsageMode.WORK) return LocalStructuredWorkState()

    val goals = buildList {
        snapshot.goal?.description?.takeIf(String::isNotBlank)?.let(::add)
        snapshot.goal?.note?.takeIf(String::isNotBlank)?.let { add("目标备注：$it") }
    }
    val plan = snapshot.plan
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(MAX_STRUCTURED_PLAN_ITEMS)
    val unfinished = snapshot.todos
        .filter { it.status == "pending" || it.status == "in_progress" }
        .map { todo -> "[${todo.status}] ${todo.content}" }
        .take(MAX_STRUCTURED_TODO_ITEMS)
    val progress = snapshot.todos
        .filter { it.status == "completed" }
        .map { todo -> "[completed] ${todo.content}" }
        .takeLast(MAX_STRUCTURED_TODO_ITEMS)

    val facts = linkedSetOf<String>()
    val tools = linkedSetOf<String>()
    val artifacts = linkedSetOf<String>()
    eventLog.pageBefore(limit = MAX_STRUCTURED_EVENT_SCAN)
        .asReversed()
        .forEach { event ->
            when (event.type) {
                "tool/call", "subagent/tool-call" -> {
                    val name = event.data.stringValue("name") ?: event.data.stringValue("tool_name")
                    val callId = event.data.stringValue("id") ?: event.data.stringValue("call_id")
                    name?.let(tools::add)
                    buildString {
                        append("工具调用")
                        name?.let { append(" tool=").append(it) }
                        callId?.let { append(" call=").append(it) }
                        safeIdentifiers(event.data).takeIf(String::isNotEmpty)
                            ?.let { append(" ").append(it) }
                    }.takeIf { it != "工具调用" }?.let(facts::add)
                    collectArtifacts(event.data, artifacts)
                }
                "tool/result", "subagent/tool-result" -> {
                    val name = event.data.stringValue("name") ?: event.data.stringValue("tool_name")
                    val callId = event.data.stringValue("id") ?: event.data.stringValue("call_id")
                    val errorCode = event.data.stringValue("error_code") ?: event.data.stringValue("code")
                    val isError = event.data.stringValue("is_error")
                    name?.let(tools::add)
                    buildString {
                        append("工具结果")
                        name?.let { append(" tool=").append(it) }
                        callId?.let { append(" call=").append(it) }
                        errorCode?.let { append(" code=").append(it) }
                        isError?.let { append(" is_error=").append(it) }
                        safeIdentifiers(event.data).takeIf(String::isNotEmpty)
                            ?.let { append(" ").append(it) }
                    }.let(facts::add)
                    collectArtifacts(event.data, artifacts)
                }
                "request/header" -> {
                    val model = event.data.stringValue("model")
                    val profile = event.data.stringValue("profile_id")
                    val protocol = event.data.stringValue("protocol")
                    val requestId = event.data.stringValue("request_id")
                    buildString {
                        append("模型路由")
                        profile?.let { append(" profile=").append(it) }
                        model?.let { append(" model=").append(it) }
                        protocol?.let { append(" protocol=").append(it) }
                        requestId?.let { append(" request=").append(it) }
                    }.takeIf { it != "模型路由" }?.let(facts::add)
                }
            }
        }

    return LocalStructuredWorkState(
        goals = goals,
        plan = plan,
        unfinished = unfinished,
        progress = progress,
        facts = facts.toList().takeLast(MAX_STRUCTURED_FACTS),
        artifacts = artifacts.toList().takeLast(MAX_STRUCTURED_ARTIFACTS),
        tools = tools.toList().takeLast(MAX_STRUCTURED_TOOLS),
    )
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun safeIdentifiers(data: JsonObject): String = SAFE_FACT_KEYS
    .mapNotNull { key ->
        data.stringValue(key)
            ?.take(MAX_STRUCTURED_IDENTIFIER_CHARS)
            ?.let { value -> "$key=$value" }
    }
    .joinToString(" ")

private fun collectArtifacts(data: JsonObject, target: MutableSet<String>) {
    ARTIFACT_FACT_KEYS.forEach { key ->
        data.stringValue(key)
            ?.take(MAX_STRUCTURED_IDENTIFIER_CHARS)
            ?.let(target::add)
    }
}

private val SAFE_FACT_KEYS = listOf(
    "path",
    "url",
    "repository",
    "branch",
    "commit_sha",
    "pr_number",
    "issue_number",
    "request_id",
    "status",
)

private val ARTIFACT_FACT_KEYS = listOf(
    "path",
    "url",
    "commit_sha",
)

private const val MAX_STRUCTURED_EVENT_SCAN = 80
private const val MAX_STRUCTURED_PLAN_ITEMS = 12
private const val MAX_STRUCTURED_TODO_ITEMS = 12
private const val MAX_STRUCTURED_FACTS = 12
private const val MAX_STRUCTURED_ARTIFACTS = 8
private const val MAX_STRUCTURED_TOOLS = 12
private const val MAX_STRUCTURED_IDENTIFIER_CHARS = 240
