package com.labteto.dshmobile.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.shenyan.designsystem.components.SySwitchControl
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.foundation.SyMetric

/**
 * Mirrors the runtime's endpoint, certificate and token requirements. No secret is persisted
 * in Compose saveable state, a task argument or a tool event.
 */
internal data class AgentBackendFormErrors(
    val host: String? = null,
    val port: String? = null,
    val pin: String? = null,
    val token: String? = null,
    val consent: String? = null,
) {
    val valid: Boolean get() = host == null && port == null && pin == null && token == null && consent == null
}

internal fun validateAgentBackendForm(
    host: String,
    port: String,
    pin: String,
    token: String,
    consent: Boolean,
): AgentBackendFormErrors = AgentBackendFormErrors(
    host = when {
        host.isBlank() -> "请输入执行器主机名"
        host != host.trim() || host.any { it.isWhitespace() || it in "/@?#" } ->
            "主机名不能包含空格、路径或 URL 参数"
        else -> null
    },
    port = if (port.toIntOrNull() in 1..65535) null else "端口范围为 1–65535",
    pin = if (pin.trim().matches(Regex("[0-9a-fA-F]{64}"))) null
        else "请输入 64 位十六进制的证书 SHA-256",
    token = if (token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 })
        null else "连接口令需为 1–4096 位不含空格的 ASCII 字符",
    consent = if (consent) null else "请先确认允许传输任务上下文和工具结果",
)

@Composable
internal fun AgentBackendSettings(viewModel: ToolsViewModel) {
    val status by viewModel.agentBackendStatus.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8443") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val errors = if (attempted) validateAgentBackendForm(host, port, pin, token, consent)
        else AgentBackendFormErrors()

    LaunchedEffect(Unit) { viewModel.refreshAgentBackend() }
    Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm)) {
        Text("代理执行器")
        Text("远端负责控制循环；模型请求、审批和工具执行保留在本机。")
        Text(status)
        if (status.contains("已保存远端授权") || status.contains("验证成功") || status.contains("连接验证失败")) {
            SyButton(text = "检查远端连接和证书", onClick = viewModel::probeAgentBackend)
        }
        SyButton(
            text = if (expanded) "收起远端设置" else "配置远端执行器",
            enabled = !saving,
            onClick = { expanded = !expanded; token = ""; attempted = false },
        )
        if (expanded) {
            SyTextField(
                value = host, onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("主机名") }, singleLine = true, enabled = !saving,
                isError = errors.host != null,
                supportingText = { errors.host?.let { Text(it) } },
            )
            SyTextField(
                value = port, onValueChange = { port = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("TLS 端口") }, singleLine = true, enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errors.port != null,
                supportingText = { errors.port?.let { Text(it) } },
            )
            SyTextField(
                value = pin, onValueChange = { pin = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("证书 SHA-256（64 位十六进制）") },
                singleLine = true, enabled = !saving,
                isError = errors.pin != null,
                supportingText = { errors.pin?.let { Text(it) } },
            )
            SyTextField(
                value = token, onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("执行器连接口令") },
                singleLine = true, enabled = !saving,
                visualTransformation = PasswordVisualTransformation(),
                isError = errors.token != null,
                supportingText = { errors.token?.let { Text(it) } },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SySwitchControl(
                    checked = consent, onCheckedChange = { consent = it },
                    enabled = !saving,
                    modifier = Modifier.semantics {
                        contentDescription = "允许执行器接收任务上下文及工具结果"
                    },
                )
                Text("允许此执行器接收任务上下文及工具结果；模型凭据保留在本机。")
            }
            errors.consent?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            SyButton(
                text = if (saving) "正在保存授权…" else "保存并授权",
                enabled = !saving,
                loading = saving,
                onClick = {
                    attempted = true
                    if (validateAgentBackendForm(host, port, pin, token, consent).valid) {
                        saving = true
                        viewModel.configureAgentBackend(
                            host.trim(), port.toInt(), pin.trim(), token, consent,
                        ) { success ->
                            saving = false
                            if (success) {
                                token = ""
                                consent = false
                                expanded = false
                                attempted = false
                            }
                        }
                    }
                },
            )
            SyButton(
                text = "撤销远端授权并停止相关执行",
                enabled = !saving,
                onClick = { viewModel.revokeAgentBackend(); token = "" },
            )
        }
    }
}
