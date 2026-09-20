package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.LocalApproval
import com.labteto.dshmobile.local.LocalHarnessMessage
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsCard
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType

/** Standalone Harness UI backed by the native Android agent loop. */
@Composable
fun LocalHarnessScreen(
    onClose: () -> Unit,
    viewModel: LocalHarnessViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingConfig by rememberSaveable { mutableStateOf(false) }

    when {
        state.loading -> LoadingScreen()
        !state.configured || editingConfig -> LocalConfiguration(
            state = state,
            canCancel = state.configured,
            onCancel = { editingConfig = false },
            onClose = onClose,
            onSave = { key, model, base ->
                viewModel.configure(key, model, base)
                editingConfig = false
            },
            onClearCredential = viewModel::clearCredential,
        )
        else -> LocalChat(
            state = state,
            onClose = onClose,
            onConfigure = { editingConfig = true },
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onNewSession = viewModel::newSession,
            onApprove = viewModel::approve,
            onDeny = viewModel::deny,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DsTheme.colors.brandPrimary)
    }
}

@Composable
private fun LocalConfiguration(
    state: LocalHarnessState,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onClearCredential: () -> Unit,
) {
    val colors = DsTheme.colors
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(DsSpacing.xlarge),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DsButton("返回", if (canCancel) onCancel else onClose, variant = DsButtonVariant.Ghost)
            Text("本机 Harness 配置", style = DsType.large20, color = colors.labelPrimary)
            Spacer(Modifier.size(56.dp))
        }

        DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text("手机直接执行", style = DsType.base16Strong, color = colors.labelPrimary)
            Text(
                "模型循环、工具调用、文件、命令、技能与子代理都在这台手机内运行。电脑关闭后仍可工作。",
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            Text(
                "工作区：${state.workspacePath}",
                style = DsType.xsmall12.copy(fontFamily = FontFamily.Monospace),
                color = colors.labelTertiary,
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.configured) "新的 API 密钥" else "DeepSeek API 密钥") },
            supportingText = { Text("密钥由安卓系统密钥库加密，不写入会话或工作区。") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )

        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
            Text("模型", style = DsType.std14Strong, color = colors.labelPrimary)
            ModelChoice("deepseek-chat", "DeepSeek Chat｜工具执行", model) { model = it }
            ModelChoice("deepseek-reasoner", "DeepSeek Reasoner｜深度推理", model) { model = it }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("接口地址") },
            supportingText = { Text("兼容 OpenAI 聊天补全协议的服务也可使用。") },
            singleLine = true,
        )

        state.error?.let {
            Text(it, style = DsType.small13, color = colors.error)
        }

        DsButton(
            text = "保存并进入本机 Harness",
            onClick = { onSave(apiKey, model, baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotBlank() && baseUrl.isNotBlank(),
        )
        if (state.configured) {
            DsButton(
                text = "清除本机模型密钥",
                onClick = onClearCredential,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Danger,
            )
        }
    }
}

@Composable
private fun ModelChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected == id, onClick = { onSelect(id) })
            .padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == id, onClick = { onSelect(id) })
        Text(label, style = DsType.std14, color = DsTheme.colors.labelPrimary)
    }
}

@Composable
private fun LocalChat(
    state: LocalHarnessState,
    onClose: () -> Unit,
    onConfigure: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewSession: () -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val colors = DsTheme.colors
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().background(colors.bgBase)) {
        Column(
            Modifier.fillMaxWidth().background(colors.bgLayer1)
                .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsButton("返回", onClose, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本机 Harness", style = DsType.base16Strong, color = colors.labelPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StateDot(if (state.running) StateDotState.Running else StateDotState.Done)
                        Text(
                            if (state.running) " 手机正在执行" else " 可离线于电脑运行",
                            style = DsType.caption11,
                            color = colors.labelTertiary,
                        )
                    }
                }
                DsButton("配置", onConfigure, variant = DsButtonVariant.Ghost, size = DsButtonSize.Small)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.model, style = DsType.xsmall12, color = colors.labelTertiary)
                DsButton("新会话", onNewSession, variant = DsButtonVariant.Outline, size = DsButtonSize.Small)
            }
        }

        if (state.plan.isNotEmpty()) {
            DsCard(Modifier.padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)) {
                Text("执行计划", style = DsType.small13Strong, color = colors.labelPrimary)
                state.plan.forEachIndexed { index, item ->
                    Text("${index + 1}. $item", style = DsType.small13, color = colors.labelSecondary)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyLocalHarness(state.workspacePath)
                }
            }
            items(state.messages, key = { it.id }) { message ->
                LocalMessageRow(message)
            }
            if (state.running) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            " 代理循环正在运行…",
                            style = DsType.small13,
                            color = colors.labelTertiary,
                        )
                    }
                }
            }
        }

        state.error?.let {
            Text(
                it,
                style = DsType.small13,
                color = colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium),
            )
        }

        Row(
            Modifier.fillMaxWidth().background(colors.bgLayer1)
                .padding(DsSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("交给手机上的 Harness…") },
                minLines = 1,
                maxLines = 5,
            )
            if (state.running) {
                DsButton("停止", onStop, variant = DsButtonVariant.Danger)
            } else {
                DsButton(
                    "发送",
                    onClick = {
                        val text = input
                        input = ""
                        onSend(text)
                    },
                    enabled = input.isNotBlank(),
                )
            }
        }
    }

    state.pendingApproval?.let {
        ApprovalDialog(it, onApprove, onDeny)
    }
}

@Composable
private fun EmptyLocalHarness(workspacePath: String) {
    val colors = DsTheme.colors
    DsCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
        Text("完整能力已在手机内启动", style = DsType.large20, color = colors.labelPrimary)
        Text(
            "可以直接让它整理资料、创建文件、搜索文本、调用网页、执行安卓命令、读取技能或拆分子任务。写文件和命令执行会先向你确认。",
            style = DsType.std14,
            color = colors.labelSecondary,
        )
        Text(
            workspacePath,
            style = DsType.caption11.copy(fontFamily = FontFamily.Monospace),
            color = colors.labelTertiary,
        )
    }
}

@Composable
private fun LocalMessageRow(message: LocalHarnessMessage) {
    val colors = DsTheme.colors
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isReasoning = message.role == "reasoning"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f),
            shape = DsShapes.block,
            color = when {
                isUser -> colors.brandPrimary.copy(alpha = 0.14f)
                isTool -> colors.bgLayer2
                isReasoning -> colors.hover
                message.role == "system" -> colors.warnTertiary
                else -> colors.bgLayer1
            },
        ) {
            Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(
                    when {
                        isUser -> "你"
                        isTool -> "工具 · ${message.toolName}"
                        isReasoning -> "推理"
                        message.role == "system" -> "系统"
                        else -> "Harness"
                    },
                    style = DsType.caption11Strong,
                    color = colors.labelTertiary,
                )
                SelectionContainer {
                    Text(
                        message.content,
                        style = if (isTool) DsType.mdCode else DsType.bubbleText,
                        color = colors.labelPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalDialog(approval: LocalApproval, onApprove: () -> Unit, onDeny: () -> Unit) {
    val colors = DsTheme.colors
    DsDialog(title = "需要你的批准", onDismiss = onDeny) {
        Text(approval.summary, style = DsType.base16Strong, color = colors.labelPrimary)
        SelectionContainer {
            Text(
                approval.arguments,
                style = DsType.mdCode,
                color = colors.labelSecondary,
                modifier = Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton("允许", onApprove)
            DsButton("拒绝", onDeny, variant = DsButtonVariant.Outline)
        }
    }
}
