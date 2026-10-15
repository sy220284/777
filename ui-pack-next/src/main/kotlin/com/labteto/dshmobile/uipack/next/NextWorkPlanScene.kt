package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.uicontract.WorkReadUiPort

/** Plan and todo statuses are actual Work Owner projections, never a made-up progress %. */
@Composable
internal fun NextWorkPlanScene(owner: WorkReadUiPort, sessionId: String?, onBack: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(owner, sessionId, revision) {
        sessionId?.let(owner::planForUi)
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("工作计划", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        TextButton(onClick = { revision++ }) { Text("刷新真实状态") }
        if (snapshot == null) Text("请打开有效的工作会话查看计划")
        else {
            Text("计划模式：${if (snapshot.planMode) "开启" else "关闭"}")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(snapshot.plan) { index, item ->
                    Text("${index + 1}. $item", style = MaterialTheme.typography.bodyLarge)
                }
                item { HorizontalDivider() }
                itemsIndexed(snapshot.todos) { index, item ->
                    Text("${index + 1}. ${item.content} · 原任务状态：${item.ownerStatus}")
                }
                if (snapshot.plan.isEmpty() && snapshot.todos.isEmpty()) {
                    item { Text("Work 尚未记录计划或待办项") }
                }
            }
        }
    }
}
