package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.presentation.LocalToolActivityUiItem
import com.labteto.dshmobile.local.presentation.LocalToolUiPhase
import com.labteto.dshmobile.uicontract.UiSessionIdentity
import com.labteto.dshmobile.uicontract.WorkEvidenceUiPort
import com.labteto.dshmobile.uicontract.WorkReadUiPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * A read-only bounded projection of a tool result. JSON strings are display data,
 * not commands, schema extensions or permission grants. Deeply nested structures
 * stop at an explicit budget instead of allocating an unlimited UI tree.
 */
internal fun v6StructuredToolResult(raw: String, maxLines: Int = 64): List<String> {
    require(maxLines in 1..128)
    val rendered = mutableListOf<String>()
    fun emit(value: String) {
        if (rendered.size < maxLines)
            rendered += value.take(512)
    }
    fun visit(name: String, value: JsonElement, depth: Int) {
        if (rendered.size >= maxLines) return
        if (depth >= 6) {
            emit("$name：…（嵌套显示达到上限）")
            return
        }
        when (value) {
            is JsonObject -> {
                emit(if (name.isEmpty()) "对象" else "$name：对象")
                value.entries.take(32).forEach { (key, child) ->
                    visit((if (name.isEmpty()) "" else "$name.") + key.take(80), child, depth + 1)
                }
                if (value.size > 32) emit("…省略其他对象字段")
            }
            is JsonArray -> {
                emit("$name：数组（${value.size} 项）")
                value.take(32).forEachIndexed { index, child ->
                    visit("$name[$index]", child, depth + 1)
                }
                if (value.size > 32) emit("…省略其他数组条目")
            }
            JsonNull -> emit("$name：null")
            is JsonPrimitive -> emit("$name：${value.content.take(320)}")
        }
    }
    if (raw.length > 16_384) return listOf("工具结果超过安全展示预算，请从原始事件按来源检查")
    val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    if (parsed != null) visit("", parsed, 0)
    else raw.lineSequence().take(maxLines).forEach { emit(it) }
    return rendered
}

/**
 * Exact paths are kept as tokens, not dot-joined strings. This prevents two
 * different nested properties ({"a.b":1} vs {"a":{"b":1}}) from looking like
 * the same source location. This projection is render-only, never a tool draft.
 */
internal enum class V6ToolResultKind {
    OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, TEXT, TRUNCATED,
}

internal data class V6ToolResultNode(
    val path: List<String>,
    val label: String,
    val kind: V6ToolResultKind,
    val depth: Int,
    val preview: String,
)

internal fun v6StructuredToolResultNodes(
    raw: String,
    maxNodes: Int = 64,
    maxDepth: Int = 6,
): List<V6ToolResultNode> {
    require(maxNodes in 1..128 && maxDepth in 1..8)
    val nodes = ArrayList<V6ToolResultNode>(minOf(64, maxNodes))
    var truncated = false
    fun add(path: List<String>, kind: V6ToolResultKind, depth: Int, preview: String) {
        if (nodes.size >= maxNodes) {
            truncated = true
            return
        }
        nodes += V6ToolResultNode(path,
            path.lastOrNull()?.take(80) ?: "工具结果", kind, depth,
            preview.take(320))
    }
    if (raw.toByteArray(Charsets.UTF_8).size > 16_384) {
        add(emptyList(), V6ToolResultKind.TRUNCATED, 0, "来源结果超过安全展示预算")
        return nodes
    }
    val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    if (parsed == null) {
        raw.lineSequence().take(maxNodes).forEachIndexed { index, line ->
            add(listOf(index.toString()), V6ToolResultKind.TEXT, 0, line.take(320))
        }
        return nodes
    }
    fun walk(path: List<String>, element: JsonElement, depth: Int) {
        if (nodes.size >= maxNodes) {
            truncated = true
            return
        }
        when (element) {
            is JsonObject -> {
                add(path, V6ToolResultKind.OBJECT, depth, "对象 · ${element.size} 个字段")
                if (depth >= maxDepth) {
                    if (element.isNotEmpty()) add(path + "…",
                        V6ToolResultKind.TRUNCATED, depth, "达到可展示的最大嵌套深度")
                    return
                }
                element.entries.take(32).forEach { (key, value) ->
                    walk(path + key, value, depth + 1)
                }
                if (element.size > 32) add(path + "…", V6ToolResultKind.TRUNCATED,
                    depth + 1, "其余字段未展示")
            }
            is JsonArray -> {
                add(path, V6ToolResultKind.ARRAY, depth, "数组 · ${element.size} 项")
                if (depth >= maxDepth) {
                    if (element.isNotEmpty()) add(path + "…",
                        V6ToolResultKind.TRUNCATED, depth, "达到可展示的最大嵌套深度")
                    return
                }
                element.take(32).forEachIndexed { index, child ->
                    walk(path + "[$index]", child, depth + 1)
                }
                if (element.size > 32) add(path + "…", V6ToolResultKind.TRUNCATED,
                    depth + 1, "其余数组项未展示")
            }
            JsonNull -> add(path, V6ToolResultKind.NULL, depth, "null")
            is JsonPrimitive -> {
                val kind = when {
                    element.isString -> V6ToolResultKind.STRING
                    element.content in setOf("true", "false") -> V6ToolResultKind.BOOLEAN
                    else -> V6ToolResultKind.NUMBER
                }
                add(path, kind, depth, element.content)
            }
        }
    }
    walk(emptyList(), parsed, 0)
    // Budget truncation must be explicit. A long event cannot appear complete
    // merely because the projection stopped after 64 rendered rows.
    if (truncated)
        nodes[maxNodes - 1] = V6ToolResultNode(
            nodes.last().path, "…", V6ToolResultKind.TRUNCATED,
            nodes.last().depth, "展示节点预算已满，请按来源事件核查")
    return nodes
}

/** Show exact Work Owner execution facts, never derive success from an arbitrary JSON payload. */
@Composable
internal fun V6WorkToolActivityPanel(
    reads: WorkReadUiPort,
    evidence: WorkEvidenceUiPort,
    identity: UiSessionIdentity,
) {
    var activities by remember(identity) { mutableStateOf<List<LocalToolActivityUiItem>>(emptyList()) }
    var selected by remember(identity) { mutableStateOf<LocalToolActivityUiItem?>(null) }
    var detail by remember(identity) { mutableStateOf<List<V6ToolResultNode>>(emptyList()) }
    var failure by remember(identity) { mutableStateOf<String?>(null) }
    var loading by remember(identity) { mutableStateOf(false) }
    var activityEpoch by remember(identity) { mutableLongStateOf(0L) }
    var detailEpoch by remember(identity) { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reads, evidence, identity) {
        evidence.changes(identity).onStart { emit(Unit) }.collectLatest {
            val revision = ++activityEpoch
            try {
                val next = withContext(Dispatchers.IO) {
                    reads.toolActivitiesForUi(identity.sessionId).takeLast(32)
                }
                if (revision == activityEpoch) {
                    activities = next
                    if (selected != null && next.none {
                        it.callId == selected?.callId && it.executionId == selected?.executionId &&
                            it.finishedSequence == selected?.finishedSequence
                    }) {
                        ++detailEpoch
                        loading = false
                        selected = null
                        detail = emptyList()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == activityEpoch) failure = error.message ?: "工具活动无法读取"
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Work 工具执行记录（仅来自原始事件）")
        activities.takeLast(12).asReversed().forEach { item ->
            val state = when (item.phase) {
                LocalToolUiPhase.DECLARED -> "待执行"
                LocalToolUiPhase.RUNNING -> "运行中"
                LocalToolUiPhase.COMPLETED -> "已完成调用"
                LocalToolUiPhase.FAILED -> "调用失败"
                LocalToolUiPhase.OUTCOME_UNKNOWN -> "结果未确认"
                LocalToolUiPhase.CANCELLED -> "已取消"
            }
            val source = item.finishedSequence ?: item.startedSequence ?: item.declaredSequence
            TextButton(onClick = {
                if (loading || source == null) return@TextButton
                val request = ++detailEpoch
                loading = true
                selected = item
                detail = emptyList()
                scope.launch {
                    try {
                        val raw = withContext(Dispatchers.IO) {
                            reads.toolEvidenceForUi(identity.sessionId, item.callId, source)
                        }
                        if (request == detailEpoch) {
                            detail = if (raw == null) listOf(V6ToolResultNode(
                                emptyList(), "来源", V6ToolResultKind.TEXT, 0, "事件已失效或不可用"))
                                else v6StructuredToolResultNodes(raw)
                            failure = null
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (request == detailEpoch) failure = error.message ?: "来源读取失败"
                    } finally {
                        if (request == detailEpoch) loading = false
                    }
                }
            }, enabled = source != null && !loading) {
                Text("${item.name} · $state · #${source ?: "?"}")
            }
        }
        selected?.let { item ->
            Text("执行：${item.executionId ?: "未开始"} · 调用：${item.callId}")
            Text(when (item.authorizationGranted) {
                true -> "本次调用已获准入；此记录不授权后续调用"
                false -> "本次调用未获授权"
                null -> "本次调用没有可核实的授权记录"
            })
            item.errorCode?.let { Text("错误码：$it") }
            detail.take(64).forEach { node ->
                Text(node.label + " · " + node.kind.name + "：" + node.preview,
                    modifier = Modifier.padding(start = (node.depth.coerceAtMost(6) * 12).dp))
            }
        }
        failure?.let { Text(it) }
    }
}
