package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.model.chatgpt.ChatGptAccountSummary
import com.labteto.dshmobile.uicontract.SettingsUiPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Public account summaries only. The original credential owner runs OAuth and account actions. */
@Composable
internal fun NextAccountScene(settings: SettingsUiPort, onBack: () -> Unit) {
    val state by settings.chatGptState.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<ChatGptAccountSummary?>(null) }
    val scope = rememberCoroutineScope()
    fun action(operation: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try { feedback = operation() ?: "操作已提交，实际账户状态请以刷新结果为准" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { feedback = failure.message ?: "账户操作未完成" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ChatGPT 账户", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("连接状态：${state.phase}")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        feedback?.let { Text(it) }
        Row {
            Button(enabled = !busy, onClick = {
                action { settings.connectChatGpt(requestPlanConsent = true); null }
            }) { Text("授权添加账户") }
            TextButton(enabled = !busy, onClick = {
                action { settings.refreshChatGpt(); null }
            }) { Text("刷新") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.accounts, key = { it.id }) { account ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(account.displayName ?: account.email ?: "已保存账户")
                        Text(if (account.signedIn) "已登录" else "需要重新授权")
                        Text(if (account.sharingEnabled) "套餐权限已允许" else "套餐权限未允许")
                        if (state.selectedAccountId == account.id) Text("当前选择的账户")
                        Row {
                            TextButton(enabled = !busy, onClick = {
                                action { settings.selectChatGptAccount(account.id); null }
                            }) { Text("选择") }
                            TextButton(enabled = !busy, onClick = {
                                action { settings.testChatGptAccount(account.id) }
                            }) { Text("实际连接测试") }
                            TextButton(enabled = !busy, onClick = {
                                action { settings.restartChatGptAuthorization(account.id); null }
                            }) { Text("重新授权") }
                        }
                        Row {
                            TextButton(enabled = !busy, onClick = {
                                action { settings.disconnectChatGptAccount(account.id) }
                            }) { Text("断开") }
                            TextButton(enabled = !busy, onClick = { removal = account }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
    removal?.let { account ->
        AlertDialog(onDismissRequest = { removal = null }, title = { Text("移除账户？") },
            text = { Text("从本机移除该账户的授权；其他账户不会受到影响。") },
            confirmButton = { TextButton(onClick = {
                removal = null
                action { settings.removeChatGptAccount(account.id) }
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } })
    }
}
