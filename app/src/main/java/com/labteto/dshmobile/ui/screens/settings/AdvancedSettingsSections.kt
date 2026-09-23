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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalVisionSettingsSnapshot
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
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
    SettingsCard(stringResource(R.string.adv_project_settings_title), Icons.Outlined.Tune) {
        when {
            state.loading -> Text(stringResource(R.string.adv_project_settings_loading), style = DsType.small13, color = DsTheme.colors.labelTertiary)
            state.error != null -> {
                Text(stringResource(R.string.adv_project_settings_unavailable, state.error.orEmpty()), style = DsType.small13, color = DsTheme.colors.error)
                DsButton(stringResource(R.string.adv_reload), viewModel::refreshRemoteSettings, variant = DsButtonVariant.Ghost)
            }
            state.namespaces.isEmpty() -> Text(stringResource(R.string.adv_project_settings_empty), style = DsType.small13, color = DsTheme.colors.labelTertiary)
            else -> {
                Text(
                    stringResource(if (state.writable) R.string.adv_project_settings_dynamic_hint else R.string.adv_project_settings_readonly_hint),
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
    val secretFallback = stringResource(R.string.adv_secret_fallback)
    val fields = remember(namespace.revision, namespace.schema, namespace.value, namespace.secrets, secretFallback) {
        dynamicFields(namespace, secretFallback)
    }
    DisclosureRow(
        title = namespace.ns,
        summary = when (namespace.applies) {
            "restart" -> stringResource(R.string.adv_restart_required_count, fields.size)
            else -> stringResource(R.string.adv_immediate_count, fields.size)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (fields.isEmpty()) {
            Text(
                stringResource(R.string.adv_no_scalar_fields),
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
    val context = LocalContext.current
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
                        stringResource(if (checked) R.string.adv_enabled else R.string.adv_disabled),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = { next ->
                            viewModel.setRemoteSetting(namespace, field.path, JsonPrimitive(next)) { error ->
                                report(error ?: context.getString(R.string.adv_field_updated, field.title))
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
                                selected.ifBlank { stringResource(R.string.adv_select) },
                                style = DsType.std14,
                                color = colors.labelSecondary,
                                modifier = Modifier.padding(DsSpacing.small),
                            )
                        }
                    },
                    items = field.enumValues.map { option ->
                        MenuItem(displayJsonScalar(option)) {
                            viewModel.setRemoteSetting(namespace, field.path, option) { error ->
                                report(error ?: context.getString(R.string.adv_field_updated, field.title))
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
                                stringResource(if (field.secretSet) R.string.adv_secret_replace_hint else R.string.adv_secret_unset)
                            } else {
                                field.path.joinToString(".")
                            },
                        )
                    },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    DsButton(
                        text = stringResource(R.string.common_save),
                        onClick = {
                            val value = parseScalar(field.type, text)
                            if (value == null) {
                                report(context.getString(R.string.adv_field_invalid, field.title))
                            } else {
                                viewModel.setRemoteSetting(namespace, field.path, value) { error ->
                                    if (error == null && field.secret) text = ""
                                    report(error ?: context.getString(R.string.adv_field_updated, field.title))
                                }
                            }
                        },
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Outline,
                    )
                    if (field.secret) {
                        if (field.secretSet) {
                            DsButton(
                                text = stringResource(R.string.adv_clear_secret),
                                onClick = {
                                    viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                        report(error ?: context.getString(R.string.adv_field_cleared, field.title))
                                    }
                                },
                                size = DsButtonSize.Small,
                                variant = DsButtonVariant.Ghost,
                            )
                        }
                    } else {
                        DsButton(
                            text = stringResource(R.string.adv_restore_default),
                            onClick = {
                                viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                    report(error ?: context.getString(R.string.adv_field_restored, field.title))
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
    SettingsCard(stringResource(R.string.adv_model_services_title), Icons.Outlined.Cloud) {
        Text(
            stringResource(R.string.adv_model_services_hint),
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
        summary = stringResource(if (provider.active) R.string.tools_plugin_enabled else R.string.tools_plugin_disabled),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.adv_provider_config, provider.settingsNs, provider.settingsPath.joinToString("/")),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            DsButton(
                text = stringResource(if (state.loading) R.string.adv_discovering else R.string.adv_discover_models),
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
                            model.contextWindow?.let { append(stringResource(R.string.adv_context_suffix, it.toString())) }
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
internal fun LocalModelSettingsCard(
    local: LocalHarnessState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    var model by remember(local.model) { mutableStateOf(local.model) }
    var baseUrl by remember(local.baseUrl) { mutableStateOf(local.baseUrl) }
    var apiKey by remember { mutableStateOf("") }

    SettingsCard(stringResource(R.string.adv_model_settings_title), Icons.Outlined.Cloud) {
        Text(
            stringResource(if (local.configured) R.string.adv_model_configured_hint else R.string.adv_model_unconfigured_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.adv_default_model)) },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.adv_endpoint)) },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(if (local.configured) R.string.adv_replace_model_secret else R.string.adv_model_secret)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.adv_save_model_settings),
                onClick = {
                    if (!local.configured && apiKey.isBlank()) {
                        report(context.getString(R.string.adv_model_secret_required))
                        return@DsButton
                    }
                    viewModel.configureLocalModel(apiKey, model, baseUrl)
                    apiKey = ""
                    report(context.getString(R.string.adv_model_settings_saved))
                },
                variant = DsButtonVariant.Outline,
            )
            if (local.configured) {
                DsButton(
                    text = stringResource(R.string.adv_clear_model_secret),
                    onClick = {
                        viewModel.clearLocalCredential()
                        report(context.getString(R.string.adv_model_secret_cleared))
                    },
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
    }
}

@Composable
internal fun LocalMemorySettingsCard(
    local: LocalHarnessState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    var userRules by remember(local.userRules) { mutableStateOf(local.userRules) }
    var autoRecall by remember(local.autoRecall) { mutableStateOf(local.autoRecall) }
    var autoMemory by remember(local.autoMemory) { mutableStateOf(local.autoMemory) }

    SettingsCard(stringResource(R.string.adv_memory_settings_title), Icons.Outlined.Memory) {
        OutlinedTextField(
            value = userRules,
            onValueChange = { userRules = it.take(6_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.adv_long_term_rules)) },
            supportingText = { Text(stringResource(R.string.adv_long_term_rules_hint)) },
            minLines = 3,
            maxLines = 6,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.adv_auto_recall), style = DsType.small13Strong, color = colors.labelPrimary)
                Text(stringResource(R.string.adv_auto_recall_hint), style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoRecall, onCheckedChange = { autoRecall = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.adv_auto_memory), style = DsType.small13Strong, color = colors.labelPrimary)
                Text(stringResource(R.string.adv_auto_memory_hint), style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoMemory, onCheckedChange = { autoMemory = it })
        }
        DsButton(
            text = stringResource(R.string.adv_save_memory_settings),
            onClick = {
                viewModel.configureLocalMemory(userRules, autoRecall, autoMemory)
                report(context.getString(R.string.adv_memory_settings_saved))
            },
            variant = DsButtonVariant.Outline,
        )
    }
}

@Composable
internal fun MemoryManagementCard(
    records: List<MemoryRecord>,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.adv_manage_memory), Icons.Outlined.Memory) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.adv_memory_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
            return@SettingsCard
        }

        Text(
            stringResource(R.string.adv_memory_manage_hint),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )

        records.forEach { record ->
            var expanded by remember(record.id) { mutableStateOf(false) }
            var content by remember(record.id, record.updatedAt) { mutableStateOf(record.content) }
            var pinned by remember(record.id, record.updatedAt) { mutableStateOf(record.pinned) }
            DisclosureRow(
                title = record.content.take(54),
                summary = memoryScopeLabel(record.scope) + " · " + memoryKindLabel(record.kind) +
                    if (record.pinned) stringResource(R.string.adv_pinned_suffix) else "",
                expanded = expanded,
                onToggle = { expanded = !expanded },
            ) {
                Column(
                    modifier = Modifier.padding(start = DsSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it.take(2_000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.adv_memory_content)) },
                        minLines = 2,
                        maxLines = 6,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.adv_pin), style = DsType.small13Strong, color = colors.labelPrimary)
                            Text(stringResource(R.string.adv_pin_hint), style = DsType.caption11, color = colors.labelTertiary)
                        }
                        Switch(checked = pinned, onCheckedChange = { pinned = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(
                            text = "保存",
                            onClick = {
                                viewModel.updateMemory(
                                    id = record.id,
                                    content = content,
                                    pinned = pinned,
                                ) { error ->
                                    report(error ?: context.getString(R.string.adv_memory_updated))
                                }
                            },
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Outline,
                        )
                        DsButton(
                            text = stringResource(R.string.adv_disable),
                            onClick = {
                                viewModel.forgetMemory(record.id) { error ->
                                    report(error ?: context.getString(R.string.adv_memory_disabled))
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
private fun memoryScopeLabel(scope: MemoryScope): String = stringResource(when (scope) {
    MemoryScope.GLOBAL -> R.string.adv_scope_global
    MemoryScope.PROJECT -> R.string.adv_scope_project
    MemoryScope.LINEAGE -> R.string.adv_scope_lineage
})

@Composable
private fun memoryKindLabel(kind: MemoryKind): String = stringResource(when (kind) {
    MemoryKind.RULE -> R.string.adv_kind_rule
    MemoryKind.PREFERENCE -> R.string.adv_kind_preference
    MemoryKind.FACT -> R.string.adv_kind_fact
    MemoryKind.DECISION -> R.string.adv_kind_decision
    MemoryKind.CONSTRAINT -> R.string.adv_kind_constraint
    MemoryKind.STATE -> R.string.adv_kind_state
    MemoryKind.SUMMARY -> R.string.adv_kind_summary
})

@Composable
internal fun LocalAgentSettingsCard(
    local: LocalHarnessState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val context = LocalContext.current
    var mainSteps by remember(local.mainMaxSteps) { mutableStateOf(local.mainMaxSteps.toString()) }
    var subagentSteps by remember(local.subagentMaxSteps) { mutableStateOf(local.subagentMaxSteps.toString()) }
    var attempts by remember(local.modelAttempts) { mutableStateOf(local.modelAttempts.toString()) }

    SettingsCard(stringResource(R.string.adv_agent_settings_title), Icons.Outlined.Tune) {
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            OutlinedTextField(
                value = mainSteps,
                onValueChange = { mainSteps = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.adv_main_loop_steps)) },
            )
            OutlinedTextField(
                value = subagentSteps,
                onValueChange = { subagentSteps = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.adv_subagent_steps)) },
            )
        }
        OutlinedTextField(
            value = attempts,
            onValueChange = { attempts = it.filter(Char::isDigit).take(1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.adv_model_retry_count)) },
            supportingText = { Text(stringResource(R.string.adv_agent_limits_hint)) },
        )
        DsButton(
            text = stringResource(R.string.adv_save_agent_settings),
            onClick = {
                viewModel.configureLocalAgent(
                    mainMaxSteps = mainSteps.toIntOrNull() ?: local.mainMaxSteps,
                    subagentMaxSteps = subagentSteps.toIntOrNull() ?: local.subagentMaxSteps,
                    modelAttempts = attempts.toIntOrNull() ?: local.modelAttempts,
                )
                report(context.getString(R.string.adv_agent_settings_saved))
            },
            variant = DsButtonVariant.Outline,
        )
    }
}

@Composable
internal fun LocalVisionSettingsCard(
    vision: LocalVisionSettingsSnapshot,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val context = LocalContext.current
    var model by remember(vision.model) { mutableStateOf(vision.model) }
    var baseUrl by remember(vision.baseUrl) { mutableStateOf(vision.baseUrl) }
    var apiKey by remember { mutableStateOf("") }

    SettingsCard(stringResource(R.string.adv_vision_title), Icons.Outlined.Cloud) {
        Text(
            if (vision.configured) {
                stringResource(R.string.adv_vision_configured_hint)
            } else {
                stringResource(R.string.adv_vision_unconfigured_hint)
            },
            style = DsType.caption11,
            color = DsTheme.colors.labelTertiary,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it.take(200) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.adv_vision_title)) },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.take(1_000) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.adv_vision_endpoint)) },
            supportingText = {
                Text(stringResource(R.string.adv_https_hint))
            },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.take(8_000) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(if (vision.configured) R.string.adv_replace_vision_secret else R.string.adv_vision_secret)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.adv_save_vision),
                onClick = {
                    if (!vision.configured && apiKey.isBlank()) {
                        report(context.getString(R.string.adv_vision_secret_required))
                        return@DsButton
                    }
                    viewModel.configureLocalVision(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                    ) { error ->
                        if (error == null) {
                            apiKey = ""
                            report(context.getString(R.string.adv_vision_saved))
                        } else {
                            report(error)
                        }
                    }
                },
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Outline,
            )
            if (vision.configured) {
                DsButton(
                    text = stringResource(R.string.adv_clear_vision_secret),
                    onClick = {
                        viewModel.clearLocalVisionCredential { error ->
                            report(error ?: context.getString(R.string.adv_vision_secret_cleared))
                        }
                    },
                    size = DsButtonSize.Small,
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
    SettingsCard(stringResource(R.string.adv_device_capabilities_title), Icons.Outlined.PhoneAndroid) {
        CapabilityRow(stringResource(R.string.adv_shizuku_service), state.shizukuAlive)
        CapabilityRow(stringResource(R.string.adv_shizuku_permission), state.shizukuGranted)
        CapabilityRow(stringResource(R.string.adv_accessibility_control), state.accessibility)
        CapabilityRow(stringResource(R.string.adv_notification_access), state.notifications)
        CapabilityRow(stringResource(R.string.adv_virtual_display), state.virtualDisplay)
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (!state.shizukuGranted) {
                DsButton(
                    text = stringResource(R.string.adv_request_shizuku),
                    onClick = viewModel::requestShizukuPermission,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.accessibility) {
                DsButton(
                    text = stringResource(R.string.adv_enable_accessibility),
                    onClick = viewModel::openAccessibilitySettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.notifications) {
                DsButton(
                    text = stringResource(R.string.adv_enable_notifications),
                    onClick = viewModel::openNotificationAccessSettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            DsButton(
                text = stringResource(R.string.adv_refresh_status),
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
        Text(stringResource(if (enabled) R.string.adv_available else R.string.adv_not_authorized), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
    }
}

private fun dynamicFields(namespace: SettingsNamespaceView, secretFallback: String): List<DynamicSettingField> {
    val rootSchema = namespace.schema as? JsonObject ?: return emptyList()
    val secretMap = namespace.secrets.associate { it.path to it.set }
    val fields = flattenSchema(rootSchema, namespace.value, emptyList(), secretMap).toMutableList()
    val known = fields.map { it.path }.toSet()
    namespace.secrets.filterNot { it.path in known }.forEach { secret ->
        fields += DynamicSettingField(
            path = secret.path,
            title = secret.path.lastOrNull() ?: secretFallback,
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
