package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Explicit user gesture invokes the existing app Update Owner, not a new updater. */
@Composable
internal fun NextUpdateScene(
    checkForUpdate: () -> Unit,
    updateStatus: StateFlow<String?>,
    onBack: () -> Unit,
) {
    val status by updateStatus.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("版本与更新", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        Text("仅在你主动检查时联网下载。安装需由 Android 系统确认。")
        status?.let { Text(it) }
        Button(onClick = checkForUpdate) { Text("检查应用更新") }
    }
}
