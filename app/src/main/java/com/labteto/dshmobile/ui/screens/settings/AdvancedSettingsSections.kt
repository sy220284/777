package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsMenu
import com.labteto.dshmobile.ui.components.MenuItem
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private data class DynamicSettingField(
    val path: List<String>,
    val title: String,
    val description: String?,
    val type: String,
    val enumValues: List<JsonElement>,
    val value: JsonElement?,
    val secret: Boolean,
    val secretSet: Boolean,
)

@Composable
internal fun ProjectSettingsCard(
    state: RemoteProjectSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    if (!state.available && state.error == null && !state.loading) return
    SettingsCard("项目配置", Icons.Outlined.Tune) {
        when {
            state.loading -> Text("正在读取当前 Harness 可配置项目…", style = DsType.small13, color = DsTheme.colors.labelTertiary)
            state.error != null -> {
                Text("项目配置不可用：${state.error}", style = DsType.small13, color = DsTheme.colors.error)
                DsButton("重新读取", viewModel::refreshRemoteSettings, variant = DsButtonVariant.Ghost)
            }
            state.namespaces.isEmpty() -> Text("当前 Harness 没有公开可配置项目。", style = DsType.small13, color = DsTheme.colors.labelTertiary)
            else -> {
                Text(
                    if (state.writable) "配置项由当前 Harness 动态提供；官方新增配置后可自动出现在这里。"
                    else "当前 Harness 返回了配置结构，但本次连接没有写入权限。",
                    style = DsType.caption11,
                    color = DsTheme.colors.labelTertiary,
                )
                state.namespaces.forEach { namespace ->
                    NamespaceSettings(namespace, state.writable, viewModel, report)
                }
            }
        }
    }
}

@Composable
private fun NamespaceSettings(
    namespace: SettingsNamespaceView,
    writable: Boolean,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    var expanded by remember(namespace.ns) { mutableStateOf(false) }
    val fields = remember(namespace.revision, namespace.schema, namespace.value, namespace.secrets) {
        dynamicFields(namespace)
    }
    DisclosureRow(
        title = namespace.ns,
        summary = when (namespace.applies) {
            "restart" -> "修改后重启生效 · ${fields.size} 项"
            else -> "立即生效 · ${fields.size} 项"
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (fields.isEmpty()) {
            Text(
                "该命名空间没有可直接编辑的标量项目。",
                style = DsType.caption11,
                color = DsTheme.colors.labelTertiary,
                modifier = Modifier.padding(start = DsSpacing.large),
            )
        } else {
            Column(
                modifier = Modifier.padding(start = DsSpacing.large),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                fields.forEach { field ->
                    DynamicSettingEditor(namespace, field, writable, viewModel, report)
                }
            }
        }
    }
}

@Composable
private fun DynamicSettingEditor(
    namespace: SettingsNamespaceView,
    field: DynamicSettingField,
    enabled: Boolean,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val currentText = if (field.secret) "" else field.value?.let(::displayJsonScalar).orEmpty()
    var text by remember(namespace.revision, field.path) { mutableStateOf(currentText) }

    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
        Text(field.title, style = DsType.std14, color = colors.labelPrimary)
        field.description?.takeIf(String::isNotBlank)?.let {
            Text(it, style = DsType.caption11, color = colors.labelTertiary)
        }

        when {
            field.type == "boolean" -> {
                val checked = (field.value as? JsonPrimitive)?.booleanOrNull ?: false
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (checked) "已开启" else "已关闭",
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = { next ->
                            viewModel.setRemoteSetting(namespace, field.path, JsonPrimitive(next)) { error ->
                                report(error ?: "已更新 ${field.title}")
                            }
                        },
                    )
                }
            }
            field.enumValues.isNotEmpty() -> {
                val selected = field.value?.let(::displayJsonScalar).orEmpty()
                DsMenu(
                    anchor = {
                        Surface(
                            shape = DsShapes.row,
                            color = colors.bgLayer2,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                selected.ifBlank { "选择…" },
                                style = DsType.std14,
                                color = colors.labelSecondary,
                                modifier = Modifier.padding(DsSpacing.small),
                            )
                        }
                    },
                    items = field.enumValues.map { option ->
                        MenuItem(displayJsonScalar(option)) {
                            viewModel.setRemoteSetting(namespace, field.path, option) { error ->
                                report(error ?: "已更新 ${field.title}")
                            }
                        }
                    },
                )
            }
            else -> {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (field.secret) {
                                if (field.secretSet) "已设置，输入新值可替换" else "尚未设置"
                            } else {
                                field.path.joinToString(".")
                            },
                        )
                    },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    DsButton(
                        text = "保存",
                        onClick = {
                            val value = parseScalar(field.type, text)
                            if (value == null) {
                                report("“${field.title}”的输入格式不正确")
                            } else {
                                viewModel.setRemoteSetting(namespace, field.path, value) { error ->
                                    if (error == null && field.secret) text = ""
                                    report(error ?: "已更新 ${field.title}")
                                }
                            }
                        },
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Outline,
                    )
                    if (field.secret) {
                        if (field.secretSet) {
                            DsButton(
                                text = "清除密钥",
                                onClick = {
                                    viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                        report(error ?: "已清除 ${field.title}")
                                    }
                                },
                                size = DsButtonSize.Small,
                                variant = DsButtonVariant.Ghost,
                            )
                        }
                    } else {
                        DsButton(
                            text = "恢复默认",
                            onClick = {
                                viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                    report(error ?: "已恢复 ${field.title}")
                                }
                            },
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelServicesCard(
    state: ModelServicesState,
    viewModel: SettingsViewModel,
) {
    if (state.providers.isEmpty() && state.error == null && !state.loading) return
    SettingsCard("模型服务", Icons.Outlined.Cloud) {
        Text(
            "服务商的地址、协议与密钥由项目动态配置管理；这里显示状态并执行模型发现。",
            style = DsType.caption11,
            color = DsTheme.colors.labelTertiary,
        )
        state.error?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.error) }
        state.providers.forEach { provider -> ModelProviderRow(provider, state, viewModel) }
    }
}

@Composable
private fun ModelProviderRow(
    provider: LlmConfigurableProvider,
    state: ModelServicesState,
    viewModel: SettingsViewModel,
) {
    val colors = DsTheme.colors
    var expanded by remember(provider.provider) { mutableStateOf(false) }
    val models = state.discovered[provider.provider].orEmpty()
    DisclosureRow(
        title = provider.displayName,
        summary = if (provider.active) "已启用" else "未启用",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                "配置：${provider.settingsNs}/${provider.settingsPath.joinToString("/")}",
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            DsButton(
                text = if (state.loading) "正在发现…" else "发现可用模型",
                onClick = { viewModel.discoverModels(provider) },
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Outline,
            )
            if (models.isNotEmpty()) {
                Text(
                    models.joinToString("\n") { model ->
                        buildString {
                            append(model.name ?: model.id)
                            if (model.name != null && model.name != model.id) append(" · ${model.id}")
                            model.contextWindow?.let { append(" · 上下文 $it") }
                        }
                    },
                    style = DsType.caption11,
                    color = colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
internal fun LocalHarnessSettingsCard(
    local: LocalHarnessState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var languageServerCommand by remember(local.languageServerCommand) { mutableStateOf(local.languageServerCommand) }
    var model by remember(local.model) { mutableStateOf(local.model) }
    var baseUrl by remember(local.baseUrl) { mutableStateOf(local.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var mainSteps by remember(local.mainMaxSteps) { mutableStateOf(local.mainMaxSteps.toString()) }
    var subagentSteps by remember(local.subagentMaxSteps) { mutableStateOf(local.subagentMaxSteps.toString()) }
    var attempts by remember(local.modelAttempts) { mutableStateOf(local.modelAttempts.toString()) }
    var userRules by remember(local.userRules) { mutableStateOf(local.userRules) }
    var autoRecall by remember(local.autoRecall) { mutableStateOf(local.autoRecall) }
    var autoMemory by remember(local.autoMemory) { mutableStateOf(local.autoMemory) }

    SettingsCard("本机 Harness", Icons.Outlined.Memory) {
        Text(
            if (local.configured) "模型密钥已配置。留空密钥可只修改其他参数。" else "尚未配置模型密钥。",
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("默认模型") })
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("接口地址") })
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (local.configured) "替换模型密钥（可留空）" else "模型密钥") },
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = userRules,
            onValueChange = { userRules = it.take(6_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户长期规则") },
            supportingText = { Text("最多保存 6000 字，每轮最多注入 3000 字；适合长期工作规则与回答偏好。") },
            minLines = 3,
            maxLines = 6,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动召回长期记忆", style = DsType.small13Strong, color = colors.labelPrimary)
                Text("按当前问题检索少量相关记忆，不把整个记忆库塞进上下文。", style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoRecall, onCheckedChange = { autoRecall = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动记忆明确长期规则", style = DsType.small13Strong, color = colors.labelPrimary)
                Text("只捕捉“记住、以后、后续都”等明确长期表达；不额外调用模型，敏感信息直接过滤。", style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoMemory, onCheckedChange = { autoMemory = it })
        }
        OutlinedTextField(
            value = languageServerCommand,
            onValueChange = { languageServerCommand = it.take(4_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_lsp_command)) },
            supportingText = { Text(stringResource(R.string.settings_lsp_hint)) },
        )
        DsButton(
            text = stringResource(R.string.settings_lsp_save),
            onClick = { report(viewModel.configureLanguageServer(languageServerCommand)) },
            variant = DsButtonVariant.Outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            OutlinedTextField(
                value = mainSteps,
                onValueChange = { mainSteps = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("主循环步数") },
            )
            OutlinedTextField(
                value = subagentSteps,
                onValueChange = { subagentSteps = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("子代理步数") },
            )
        }
        OutlinedTextField(
            value = attempts,
            onValueChange = { attempts = it.filter(Char::isDigit).take(1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("模型失败重试次数") },
            supportingText = { Text("主循环 4–128；子代理 1–40；重试 1–5。") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = "保存本机配置",
                onClick = {
                    if (!local.configured && apiKey.isBlank()) {
                        report("首次使用请先填写模型密钥")
                        return@DsButton
                    }
                    viewModel.configureLocalHarness(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                        mainMaxSteps = mainSteps.toIntOrNull() ?: local.mainMaxSteps,
                        subagentMaxSteps = subagentSteps.toIntOrNull() ?: local.subagentMaxSteps,
                        modelAttempts = attempts.toIntOrNull() ?: local.modelAttempts,
                        userRules = userRules,
                        autoRecall = autoRecall,
                        autoMemory = autoMemory,
                    )
                    apiKey = ""
                    report("本机 Harness 配置已保存")
                },
                variant = DsButtonVariant.Outline,
            )
            if (local.configured) {
                DsButton(
                    text = "清除密钥",
                    onClick = {
                        viewModel.clearLocalCredential()
                        report("本机模型密钥已清除")
                    },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
internal fun DeviceCapabilitiesCard(
    state: DeviceCapabilitiesState,
    viewModel: SettingsViewModel,
) {
    SettingsCard("设备能力", Icons.Outlined.PhoneAndroid) {
        CapabilityRow("Shizuku 服务", state.shizukuAlive)
        CapabilityRow("Shizuku 授权", state.shizukuGranted)
        CapabilityRow("无障碍控制", state.accessibility)
        CapabilityRow("通知读取", state.notifications)
        CapabilityRow("虚拟屏幕", state.virtualDisplay)
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (!state.shizukuGranted) {
                DsButton(
                    text = "请求 Shizuku 授权",
                    onClick = viewModel::requestShizukuPermission,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.accessibility) {
                DsButton(
                    text = "开启无障碍控制",
                    onClick = viewModel::openAccessibilitySettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.notifications) {
                DsButton(
                    text = "开启通知读取",
                    onClick = viewModel::openNotificationAccessSettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            DsButton(
                text = "刷新状态",
                onClick = viewModel::refreshDeviceCapabilities,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
        }
        state.error?.let { Text(it, style = DsType.caption11, color = DsTheme.colors.error) }
    }
}

@Composable
private fun CapabilityRow(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StateDot(if (enabled) StateDotState.Done else StateDotState.Idle)
        Spacer(Modifier.width(DsSpacing.small))
        Text(label, style = DsType.std14, color = DsTheme.colors.labelSecondary, modifier = Modifier.weight(1f))
        Text(if (enabled) "可用" else "未授权", style = DsType.caption11, color = DsTheme.colors.labelTertiary)
    }
}

private fun dynamicFields(namespace: SettingsNamespaceView): List<DynamicSettingField> {
    val rootSchema = namespace.schema as? JsonObject ?: return emptyList()
    val secretMap = namespace.secrets.associate { it.path to it.set }
    val fields = flattenSchema(rootSchema, namespace.value, emptyList(), secretMap).toMutableList()
    val known = fields.map { it.path }.toSet()
    namespace.secrets.filterNot { it.path in known }.forEach { secret ->
        fields += DynamicSettingField(
            path = secret.path,
            title = secret.path.lastOrNull() ?: "密钥",
            description = null,
            type = "string",
            enumValues = emptyList(),
            value = null,
            secret = true,
            secretSet = secret.set,
        )
    }
    return fields
}

private fun flattenSchema(
    schema: JsonObject,
    value: JsonElement?,
    prefix: List<String>,
    secrets: Map<List<String>, Boolean>,
): List<DynamicSettingField> {
    val properties = schema["properties"] as? JsonObject ?: return emptyList()
    val valueObject = value as? JsonObject
    return properties.flatMap { (name, rawSchema) ->
        val childSchema = rawSchema as? JsonObject ?: return@flatMap emptyList()
        val path = prefix + name
        val childValue = valueObject?.get(name)
        val type = (childSchema["type"] as? JsonPrimitive)?.contentOrNull
            ?: if (childSchema["properties"] is JsonObject) "object" else "string"
        if (type == "object" || childSchema["properties"] is JsonObject) {
            flattenSchema(childSchema, childValue, path, secrets)
        } else {
            val enums = (childSchema["enum"] as? JsonArray).orEmpty()
            listOf(
                DynamicSettingField(
                    path = path,
                    title = (childSchema["title"] as? JsonPrimitive)?.contentOrNull ?: name,
                    description = (childSchema["description"] as? JsonPrimitive)?.contentOrNull,
                    type = type,
                    enumValues = enums,
                    value = childValue,
                    secret = path in secrets,
                    secretSet = secrets[path] == true,
                ),
            )
        }
    }
}

private fun displayJsonScalar(value: JsonElement): String =
    (value as? JsonPrimitive)?.contentOrNull ?: value.toString()

private fun parseScalar(type: String, text: String): JsonPrimitive? = when (type) {
    "integer" -> text.trim().toIntOrNull()?.let(::JsonPrimitive)
    "number" -> text.trim().toDoubleOrNull()?.let(::JsonPrimitive)
    "boolean" -> text.trim().toBooleanStrictOrNull()?.let(::JsonPrimitive)
    else -> JsonPrimitive(text)
}
