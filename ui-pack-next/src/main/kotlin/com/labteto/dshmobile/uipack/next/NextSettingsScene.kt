package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Independent settings sections: commands go through the existing Settings/Usage Owners.
 * UI-only state never contains saved credentials. Selection receipts are not model activation.
 */
@Composable
internal fun NextSettingsScene(
    page: String,
    settings: SettingsUiPort,
    usage: UsageUiPort?,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle(settings.initialState)
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf("") }
    var recall by rememberSaveable { mutableStateOf(true) }
    var memory by rememberSaveable { mutableStateOf(true) }
    var limitMain by rememberSaveable { mutableStateOf("128") }
    var limitSub by rememberSaveable { mutableStateOf("128") }
    var attempts by rememberSaveable { mutableStateOf("3") }
    var output by remember { mutableStateOf<String?>(null) }
    var modelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCompact by remember { mutableStateOf(false) }
    // Secrets remain in transient Compose memory; never rememberSaveable or form drafts.
    var modelApiKey by remember { mutableStateOf("") }
    var modelName by rememberSaveable { mutableStateOf("") }
    var modelUrl by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(page, state.loading) {
        if (!state.loading) {
            rules = state.userRules
            recall = state.autoRecall
            memory = state.autoMemory
            limitMain = state.mainMaxSteps.toString()
            limitSub = state.subagentMaxSteps.toString()
            attempts = state.modelAttempts.toString()
        }
    }
    fun dispatch(command: SettingsUiCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                notice = when (val receipt = settings.submit(command)) {
                    SettingsUiReceipt.Applied -> "已写入设置"
                    SettingsUiReceipt.Submitted -> "已提交修改；实际生效状态以设置读取为准"
                    SettingsUiReceipt.Rejected -> "该操作已拒绝，设置未修改"
                    is SettingsUiReceipt.Failed -> receipt.message ?: "设置未修改"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "设置未修改" }
            finally { busy = false }
        }
    }
    fun read(operation: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                output = withContext(Dispatchers.IO) { operation() }
                notice = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "读取未完成" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (page) {
                "route.settings", "settings.root" -> "设置"
                "settings.models" -> "模型"
                "settings.chat" -> "聊天设置"
                "settings.memory", "settings.memory_settings" -> "记忆与个人规则"
                "settings.advanced", "settings.session" -> "运行参数"
                "settings.usage", "settings.model_usage", "settings.usage_log", "settings.usage_detail" -> "模型用量"
                "settings.session_storage" -> "会话存储"
                "settings.diagnostics" -> "诊断"
                else -> "设置"
            }, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) {
            Text("正在读取设置…")
        } else {
            when (page) {
                "route.settings", "settings.root" -> {
                    val destinations = listOf(
                        "settings.models" to "模型配置",
                        "settings.account" to "ChatGPT 账户",
                        "settings.chat" to "聊天",
                        "settings.memory" to "记忆与规则",
                        "settings.advanced" to "运行限制",
                        "settings.usage" to "用量统计",
                        "settings.session_storage" to "会话存储",
                        "settings.diagnostics" to "诊断记录",
                    )
                    LazyColumn {
                        items(destinations) { (id, label) ->
                            TextButton(onClick = { onNavigate(id) }) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                "settings.models" -> {
                    Text("当前模型：${state.model}")
                    Text("选择模型将提交给原模型管理器；是否实际应用以返回状态为准。")
                    OutlinedTextField(modelName, { modelName = it },
                        label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(modelUrl, { modelUrl = it },
                        label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(modelApiKey, { modelApiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row {
                        Button(enabled = !busy && modelName.isNotBlank() && modelUrl.isNotBlank(), onClick = {
                            val key = modelApiKey
                            val name = modelName.trim()
                            val url = modelUrl.trim()
                            modelApiKey = ""
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        notice = when (val result = settings.save(
                                            SettingsUiCommand.SaveModel(key, name, url))) {
                                            SettingsUiReceipt.Applied -> "模型配置已保存"
                                            SettingsUiReceipt.Submitted -> "模型配置已提交"
                                            SettingsUiReceipt.Rejected -> "模型配置被拒绝"
                                            is SettingsUiReceipt.Failed -> result.message ?: "保存失败"
                                        }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { notice = failure.message ?: "保存失败" }
                                    finally { busy = false }
                                }
                            }
                        }) { Text("保存模型配置") }
                        TextButton(enabled = !busy && modelName.isNotBlank() && modelUrl.isNotBlank(), onClick = {
                            val key = modelApiKey
                            val name = modelName.trim()
                            val url = modelUrl.trim()
                            read { settings.testModel(key, name, url) }
                        }) { Text("测试模型") }
                    }
                    LazyColumn {
                        items(state.modelProfiles, key = { it.id }) { model ->
                            Row(Modifier.fillMaxWidth()) {
                                RadioButton(selected = state.modelSelection.activeProfileId == model.id,
                                    onClick = { dispatch(SettingsUiCommand.SelectModel(model.id)) })
                                TextButton(onClick = { dispatch(SettingsUiCommand.SelectModel(model.id)) }) {
                                    Text("${model.provider} · ${model.model}")
                                }
                            }
                        }
                    }
                }
                "settings.chat", "settings.memory", "settings.memory_settings" -> {
                    OutlinedTextField(rules, { rules = it }, label = { Text("自定义规则") },
                        minLines = 4, modifier = Modifier.fillMaxWidth())
                    Row {
                        Checkbox(checked = recall, onCheckedChange = { recall = it })
                        Text("自动调用记忆")
                    }
                    Row {
                        Checkbox(checked = memory, onCheckedChange = { memory = it })
                        Text("自动保存记忆")
                    }
                    Button(onClick = {
                        dispatch(SettingsUiCommand.Personalization(rules, recall, memory))
                    }, enabled = !busy) { Text("保存个人规则与记忆设置") }
                    if (page == "settings.chat") {
                        Row {
                            Checkbox(checked = state.chatStyleGuardEnabled,
                                onCheckedChange = { dispatch(SettingsUiCommand.StyleGuard(it)) })
                            Text("聊天表达风格保护")
                        }
                    }
                }
                "settings.advanced", "settings.session" -> {
                    OutlinedTextField(limitMain, { limitMain = it.filter(Char::isDigit).take(5) },
                        label = { Text("主智能体最大步骤") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(limitSub, { limitSub = it.filter(Char::isDigit).take(5) },
                        label = { Text("子智能体最大步骤") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(attempts, { attempts = it.filter(Char::isDigit).take(3) },
                        label = { Text("模型请求尝试次数") }, modifier = Modifier.fillMaxWidth())
                    val m = limitMain.toIntOrNull()
                    val s = limitSub.toIntOrNull()
                    val a = attempts.toIntOrNull()
                    Button(onClick = {
                        if (m != null && s != null && a != null)
                            dispatch(SettingsUiCommand.RuntimeLimits(m, s, a, state.modelSelection.workerProfileId))
                    }, enabled = !busy && m != null && s != null && a != null &&
                        m in 1..10000 && s in 1..10000 && a in 1..100) { Text("保存运行参数") }
                }
                "settings.usage", "settings.model_usage", "settings.usage_log", "settings.usage_detail" -> {
                    val owner = usage
                    if (owner == null) {
                        Text("用量服务不可用")
                    } else {
                        val revision by owner.revision.collectAsStateWithLifecycle()
                        val figures = remember(owner, revision) { owner.snapshot() }
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(modelFilter ?: "全部模型")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("全部模型") }, onClick = {
                                    modelFilter = null; expanded = false
                                })
                                figures.byModel.keys.sorted().forEach { id ->
                                    DropdownMenuItem(text = { Text(id) }, onClick = {
                                        modelFilter = id; expanded = false
                                    })
                                }
                            }
                        }
                        val filtered = modelFilter?.let { figures.byModel[it] } ?: figures
                        Text("请求数：${filtered.tracked.requestCount}")
                        Text("输入 Token：${filtered.tracked.inputTokens}")
                        Text("输出 Token：${filtered.tracked.outputTokens}")
                        Text("预估费用：¥${"%.4f".format(filtered.tracked.estimatedCostCny)}")
                        if (filtered.tracked.unreportedRequestCount > 0)
                            Text("${filtered.tracked.unreportedRequestCount} 次请求未报告完整用量，费用可能不完整")
                        LazyColumn {
                            items(filtered.recentRecords.take(100), key = { it.requestId }) { record ->
                                Text("${record.model} · ${record.context.action} · ${record.totalTokens} Token")
                                HorizontalDivider()
                            }
                        }
                    }
                }
                "settings.session_storage" -> {
                    Text("会话存储由原 Session Owner 管理，整理前请确认。")
                    Button(enabled = !busy, onClick = { read { settings.sessionStorageStatus().toString() } }) {
                        Text("读取实际存储状态")
                    }
                    TextButton(enabled = !busy, onClick = { confirmCompact = true }) { Text("整理会话存储") }
                }
                "settings.diagnostics" -> {
                    TextButton(enabled = !busy, onClick = { read { settings.environmentInfo() } }) {
                        Text("读取运行环境")
                    }
                    TextButton(enabled = !busy, onClick = { read { settings.diagnosticReport() } }) {
                        Text("读取完整诊断报告")
                    }
                }
            }
            output?.let { value -> SelectionContainer { LazyColumn {
                item { Text(value) }
            } } }
        }
    }
    if (confirmCompact) AlertDialog(onDismissRequest = { confirmCompact = false },
        title = { Text("整理会话存储") },
        text = { Text("由原存储管理器执行，不会替换或伪造恢复记录。确认继续？") },
        confirmButton = { TextButton(onClick = {
            confirmCompact = false
            read { settings.compactSessionStorage().toString() }
        }) { Text("执行整理") } },
        dismissButton = { TextButton(onClick = { confirmCompact = false }) { Text("取消") } })
}
