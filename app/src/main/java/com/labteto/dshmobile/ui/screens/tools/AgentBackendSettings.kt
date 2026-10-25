package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SySwitchControl
import com.shenyan.designsystem.foundation.SyMetric
import com.labteto.dshmobile.ui.shenyan.SyButton

/** The connector token is never placed in saved instance state, task arguments or tool events. */
@Composable
internal fun AgentBackendSettings(viewModel: ToolsViewModel) {
    val status by viewModel.agentBackendStatus.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8443") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshAgentBackend() }
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
        Text("代理执行器")
        Text(status)
        SyButton(text = if (expanded) "收起远端设置" else "配置远端执行器", onClick = { expanded = !expanded; token = "" })
        if (expanded) {
            SyTextField(value = host, onValueChange = { host = it }, label = { Text("主机名") }, singleLine = true)
            SyTextField(value = port, onValueChange = { port = it }, label = { Text("TLS 端口") }, singleLine = true)
            SyTextField(value = pin, onValueChange = { pin = it }, label = { Text("证书 SHA-256（64 位十六进制）") }, singleLine = true)
            SyTextField(value = token, onValueChange = { token = it }, label = { Text("执行器连接口令") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row {
                SySwitchControl(checked = consent, onCheckedChange = { consent = it })
                Text("允许此执行器接收任务上下文及工具结果；模型凭据保留在本机。")
            }
            SyButton(text = "保存并授权", enabled = consent && token.isNotBlank(), onClick = {
                viewModel.configureAgentBackend(host.trim(), port.toIntOrNull() ?: 0, pin.trim(), token, consent)
                token = ""; consent = false
            })
            SyButton(text = "撤销远端授权并停止相关执行", onClick = { viewModel.revokeAgentBackend(); token = "" })
        }
    }
}
