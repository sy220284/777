package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NextReplySuggestionsScene(
    owner: ReplySuggestionsUiPort,
    drafts: ConversationDraftUiPort,
    onBack: () -> Unit,
) {
    val snap by owner.snapshots.collectAsStateWithLifecycle(
        initialValue = UiReplySuggestionsSnapshot(null, emptyList()))
    var pending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("回复灵感", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        Text("建议仅用于填写输入草稿，不会自动发送。调用模型可能消耗额度。")
        notice?.let { Text(it) }
        Button(enabled = snap.identity != null && !pending, onClick = {
            val id = snap.identity ?: return@Button
            pending = true
            scope.launch {
                try {
                    notice = when (val receipt = owner.generate(id)) {
                        UiCommandReceipt.Accepted -> "回复建议已生成，请查看实际建议"
                        is UiCommandReceipt.Rejected -> "生成被拒绝：${receipt.reason}"
                        is UiCommandReceipt.Failed -> receipt.message ?: "生成失败"
                        else -> "建议生成未完成"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice = failure.message ?: "生成失败" }
                finally { pending = false }
            }
        }) { Text("生成回复建议") }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(snap.values) { index, suggestion ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(suggestion.label, style = MaterialTheme.typography.titleMedium)
                        Text(suggestion.text)
                        TextButton(enabled = snap.identity != null, onClick = {
                            val sessionId = snap.identity?.sessionId ?: return@TextButton
                            drafts.updateDraft(sessionId, suggestion.text)
                            notice = "已放入输入框；确认后再发送"
                        }) { Text("放入消息草稿") }
                    }
                }
            }
        }
    }
}
