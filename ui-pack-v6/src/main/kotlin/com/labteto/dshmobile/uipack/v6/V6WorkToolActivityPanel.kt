package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Show exact Work Owner execution facts, never derive success from an arbitrary JSON payload. */
@Composable
internal fun V6WorkToolActivityPanel(
    reads: WorkReadUiPort,
    evidence: WorkEvidenceUiPort,
    identity: UiSessionIdentity,
) {
    var activities by remember(identity) { mutableStateOf<List<LocalToolActivityUiItem>>(emptyList()) }
    var selected by remember(identity) { mutableStateOf<LocalToolActivityUiItem?>(null) }
    var detail by remember(identity) { mutableStateOf<List<String>>(emptyList()) }
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
                            detail = if (raw == null) listOf("来源事件已失效或不可用")
                                else v6StructuredToolResult(raw)
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
            item.errorCode?.let { Text("错误码：$it") }
            detail.take(64).forEach { Text(it) }
        }
        failure?.let { Text(it) }
    }
}
