package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*

@Composable
internal fun NextErrorScene(
    chat: ChatUiPort, work: WorkConversationUiPort,
    onBack: () -> Unit, onDiagnostics: () -> Unit,
) {
    val chatState by chat.snapshots.collectAsStateWithLifecycle(initialValue = chat.snapshot())
    val workState by work.snapshots.collectAsStateWithLifecycle(initialValue = work.snapshot())
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("执行与会话状态", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        Text("人物聊天：${chatState.status} · ${chatState.phase}")
        chatState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("工作会话：${workState.status} · ${workState.phase}")
        workState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (chatState.error == null && workState.error == null) Text("当前 Owner 未报告前台错误。历史请查看诊断记录。")
        TextButton(onClick = onDiagnostics) { Text("打开诊断记录") }
    }
}
