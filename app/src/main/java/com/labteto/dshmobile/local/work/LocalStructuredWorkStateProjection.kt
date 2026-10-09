package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun structuredWorkState(
    snapshot: LocalHarnessState,
    eventLog: LocalSessionEventLog,
    sessionProjection: LocalWorkSessionProjectionRuntime,
): LocalStructuredWorkState {
    if (snapshot.usageMode != LocalUsageMode.WORK) return LocalStructuredWorkState()

    val work = snapshot.work
    val goals = buildList {
        work.goal?.description?.takeIf(String::isNotBlank)?.let(::add)
        work.goal?.note?.takeIf(String::isNotBlank)?.let { add("目标备注：$it") }
    }
    val plan = work.plan
        .map(String::trim)
        .filter(String::isNotBlank)
        .take(MAX_STRUCTURED_PLAN_ITEMS)
    val unfinished = work.todos
        .filter { it.status == "pending" || it.status == "in_progress" }
        .map { todo -> "[${todo.status}] ${todo.content}" }
        .take(MAX_STRUCTURED_TODO_ITEMS)
    val progress = work.todos
        .filter { it.status == "completed" }
        .map { todo -> "[completed] ${todo.content}" }
        .takeLast(MAX_STRUCTURED_TODO_ITEMS)

    val constraints = linkedSetOf<String>()
    val decisions = linkedSetOf<String>()
    val failures = linkedSetOf<String>()
    val facts = linkedSetOf<String>()
    val tools = linkedSetOf<String>()
    val artifacts = linkedSetOf<String>()
    sessionProjection.structuredEventSnapshot(eventLog)
        .state
        .events
        .forEach { event ->
            when (event.type) {
                "user/message" -> {
                    eventText(event.data)
                        ?.let { extractLocalWorkCueSnippet(it, LocalWorkCueKind.CONSTRAINT) }
                        ?.let(constraints::add)
                }
                "assistant/message" -> {
                    val text = eventText(event.data)
                    text?.let { extractLocalWorkCueSnippet(it, LocalWorkCueKind.DECISION) }
                        ?.let(decisions::add)
                    text?.let { extractLocalWorkCueSnippet(it, LocalWorkCueKind.FAILURE) }
                        ?.let(failures::add)
                }
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
                    }.let { fact ->
                        facts += fact
                        if (errorCode != null || isError == "true") failures += fact
                    }
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
        constraints = constraints.toList().takeLast(MAX_STRUCTURED_CONSTRAINTS),
        decisions = decisions.toList().takeLast(MAX_STRUCTURED_DECISIONS),
        failures = failures.toList().takeLast(MAX_STRUCTURED_FAILURES),
        unfinished = unfinished,
        progress = progress,
        facts = facts.toList().takeLast(MAX_STRUCTURED_FACTS),
        artifacts = artifacts.toList().takeLast(MAX_STRUCTURED_ARTIFACTS),
        tools = tools.toList().takeLast(MAX_STRUCTURED_TOOLS),
    )
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun eventText(data: JsonObject): String? {
    data.stringValue("content")?.let { return it }
    for (key in listOf("model_message", "message")) {
        val message = data[key] as? JsonObject ?: continue
        when (val content = message["content"]) {
            is JsonPrimitive -> content.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
            is JsonArray -> {
                val text = content.joinToString("\n") { part ->
                    when (part) {
                        is JsonPrimitive -> part.contentOrNull.orEmpty()
                        is JsonObject -> part.stringValue("text").orEmpty()
                        else -> ""
                    }
                }.trim()
                if (text.isNotBlank()) return text
            }
            else -> Unit
        }
    }
    return null
}

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

private const val MAX_STRUCTURED_PLAN_ITEMS = 12
private const val MAX_STRUCTURED_TODO_ITEMS = 12
private const val MAX_STRUCTURED_CONSTRAINTS = 8
private const val MAX_STRUCTURED_DECISIONS = 6
private const val MAX_STRUCTURED_FAILURES = 6
private const val MAX_STRUCTURED_FACTS = 12
private const val MAX_STRUCTURED_ARTIFACTS = 8
private const val MAX_STRUCTURED_TOOLS = 12
private const val MAX_STRUCTURED_IDENTIFIER_CHARS = 240
