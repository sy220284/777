package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Uses the Session owner's scoped, opaque search continuations across real sessions. */
@Composable
internal fun NextGlobalSearchScene(
    sessions: SessionUiPort,
    history: UiMessageHistoryPort,
    onOpenSession: () -> Unit,
    onBack: () -> Unit,
) {
    data class Hit(val sessionId: String, val title: String, val message: UiMessageSnapshot)
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<Hit>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var limited by remember { mutableStateOf(false) }
    val sessionState by sessions.snapshots.collectAsStateWithLifecycle(initialValue = sessions.snapshot())
    var awaitingOpen by remember { mutableStateOf<String?>(null) }
    var navigationNotice by remember { mutableStateOf<String?>(null) }

    // Queued only means admitted to the Session owner; navigate after the target is actually active.
    LaunchedEffect(awaitingOpen, sessionState.identity?.sessionId, sessionState.loading, sessionState.error) {
        val target = awaitingOpen ?: return@LaunchedEffect
        when {
            sessionState.identity?.sessionId == target && !sessionState.loading -> {
                awaitingOpen = null
                navigationNotice = null
                onOpenSession()
            }
            !sessionState.loading && sessionState.error != null -> {
                awaitingOpen = null
                navigationNotice = sessionState.error
            }
        }
    }

    // Admission does not guarantee an eventual session activation. Release the pending
    // state after a bounded wait rather than disabling every search result indefinitely.
    LaunchedEffect(awaitingOpen, sessions) {
        val target = awaitingOpen ?: return@LaunchedEffect
        val activated = withTimeoutOrNull(15_000L) {
            sessions.snapshots.first { !it.loading && it.identity?.sessionId == target }
        }
        if (awaitingOpen == target && activated == null) {
            awaitingOpen = null
            navigationNotice = "会话切换尚未确认，请检查当前会话后再重试"
        }
    }

    LaunchedEffect(sessions, history, submitted, revision) {
        if (submitted.isBlank()) return@LaunchedEffect
        results = emptyList()
        error = null
        limited = false
        busy = true
        try {
            val all = sessions.snapshot().sessions
            val found = mutableListOf<Hit>()
            for ((index, session) in all.withIndex()) {
                progress = "搜索会话 ${index + 1} / ${all.size}"
                var cursor: UiMessageSearchCursor? = null
                var pages = 0
                do {
                    val response = withContext(Dispatchers.IO) {
                        history.searchMessages(session.id, submitted, cursor)
                    }
                    when (response) {
                        is UiMessageSearchReceipt.Failed -> {
                            error = response.message ?: "部分会话搜索失败"
                            cursor = null
                        }
                        is UiMessageSearchReceipt.Available -> {
                            val hits = response.result.page.messages.filter {
                                it.id in response.result.hitIds
                            }
                            found += hits.map { Hit(session.id, session.title, it) }
                            results = found.take(250).toList()
                            cursor = response.result.nextCursor
                        }
                    }
                    pages++
                    if (found.size >= 250 || pages >= 10_000) {
                        limited = true
                        break
                    }
                } while (cursor != null)
                if (limited) break
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "历史搜索失败" }
        finally { busy = false; progress = "" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("跨会话搜索", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        OutlinedTextField(value = query, onValueChange = { query = it.take(500) },
            label = { Text("搜索所有会话历史") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            submitted = query.trim()
            revision++
        }, enabled = query.isNotBlank() && !busy) { Text("开始搜索") }
        if (busy) Text(progress)
        navigationNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (limited) Text("结果或分页达到单次检索预算；请缩小关键词再查找，不代表所有历史均已扫描。")
        Text("已找到 ${results.size} 条")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results, key = { it.sessionId + ":" + it.message.id }) { hit ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(hit.message.role.name)
                        SelectionContainer { Text(hit.message.text.take(2000)) }
                        TextButton(enabled = awaitingOpen == null, onClick = {
                            when (val receipt = sessions.submit(SessionUiCommand.Open(hit.sessionId))) {
                                UiCommandReceipt.Accepted -> {
                                    awaitingOpen = hit.sessionId
                                    navigationNotice = "正在打开目标会话"
                                }
                                UiCommandReceipt.Queued -> {
                                    awaitingOpen = hit.sessionId
                                    navigationNotice = "会话切换已排队，等待目标会话真正激活"
                                }
                                is UiCommandReceipt.Failed -> {
                                    awaitingOpen = null
                                    error = receipt.message ?: "会话打开失败"
                                }
                                is UiCommandReceipt.Rejected -> {
                                    awaitingOpen = null
                                    error = "会话不可打开：${receipt.reason}"
                                }
                                else -> {
                                    awaitingOpen = null
                                    error = "会话打开结果未知；请查看当前会话后再重试"
                                }
                            }
                        }) { Text("打开所属会话") }
                    }
                }
            }
        }
    }
}
