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
    SettingsCard(stringResource(R.string.advanced_project_config), Icons.Outlined.Tune) {
        when {
            state.loading -> Text(stringResource(R.string.advanced_project_loading), style = DsType.small13, color = DsTheme.colors.labelTertiary)
            state.error != null -> {
                Text(stringResource(R.string.advanced_project_unavailable, state.error.orEmpty()), style = DsType.small13, color = DsTheme.colors.error)
                DsButton(stringResource(R.string.common_retry), viewModel::refreshRemoteSettings, variant = DsButtonVariant.Ghost)
            }
            state.namespaces.isEmpty() -> Text(stringResource(R.string.advanced_project_empty), style = DsType.small13, color = DsTheme.colors.labelTertiary)
            else -> {
                Text(
                    if (state.writable) stringResource(R.string.advanced_project_dynamic)
                    else stringResource(R.string.advanced_project_read_only),
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
    val secretFallback = stringResource(R.string.advanced_secret)
    val fields = remember(namespace.revision, namespace.schema, namespace.value, namespace.secrets, secretFallback) {
        dynamicFields(namespace, secretFallback)
    }
    DisclosureRow(
        title = namespace.ns,
        summary = when (namespace.applies) {
            "restart" -> stringResource(R.string.advanced_project_restart_summary, fields.size)
            else -> stringResource(R.string.advanced_project_immediate_summary, fields.size)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (fields.isEmpty()) {
            Text(
                stringResource(R.string.advanced_project_no_scalar),
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
    val updatedFieldMessage = stringResource(R.string.advanced_updated_field, field.title)
    val badFormatMessage = stringResource(R.string.advanced_bad_format, field.title)
    val clearedFieldMessage = stringResource(R.string.advanced_cleared_field, field.title)
    val restoredFieldMessage = stringResource(R.string.advanced_restored_field, field.title)
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
                        if (checked) stringResource(R.string.common_enabled) else stringResource(R.string.common_disabled),
                        style = DsType.small13,
                        color = colors.labelSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = { next ->
                            viewModel.setRemoteSetting(namespace, field.path, JsonPrimitive(next)) { error ->
                                report(error ?: updatedFieldMessage)
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
                                selected.ifBlank { stringResource(R.string.advanced_select) },
                                style = DsType.std14,
                                color = colors.labelSecondary,
                                modifier = Modifier.padding(DsSpacing.small),
                            )
                        }
                    },
                    items = field.enumValues.map { option ->
                        MenuItem(displayJsonScalar(option)) {
                            viewModel.setRemoteSetting(namespace, field.path, option) { error ->
                                report(error ?: updatedFieldMessage)
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
                                if (field.secretSet) stringResource(R.string.advanced_secret_replace_hint) else stringResource(R.string.advanced_secret_unset)
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
                                report(badFormatMessage)
                            } else {
                                viewModel.setRemoteSetting(namespace, field.path, value) { error ->
                                    if (error == null && field.secret) text = ""
                                    report(error ?: updatedFieldMessage)
                                }
                            }
                        },
                        size = DsButtonSize.Small,
                        variant = DsButtonVariant.Outline,
                    )
                    if (field.secret) {
                        if (field.secretSet) {
                            DsButton(
                                text = stringResource(R.string.advanced_clear_key),
                                onClick = {
                                    viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                        report(error ?: clearedFieldMessage)
                                    }
                                },
                                size = DsButtonSize.Small,
                                variant = DsButtonVariant.Ghost,
                            )
                        }
                    } else {
                        DsButton(
                            text = stringResource(R.string.advanced_restore_default),
                            onClick = {
                                viewModel.unsetRemoteSetting(namespace, field.path) { error ->
                                    report(error ?: restoredFieldMessage)
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
    SettingsCard(stringResource(R.string.advanced_model_services), Icons.Outlined.Cloud) {
        Text(
            stringResource(R.string.advanced_model_services_hint),
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
    val contextWindowLabel = stringResource(R.string.advanced_context_window_label)
    var expanded by remember(provider.provider) { mutableStateOf(false) }
    val models = state.discovered[provider.provider].orEmpty()
    DisclosureRow(
        title = provider.displayName,
        summary = stringResource(if (provider.active) R.string.common_enabled else R.string.common_disabled),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(start = DsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Text(
                stringResource(R.string.advanced_provider_config, provider.settingsNs, provider.settingsPath.joinToString("/")),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            DsButton(
                text = stringResource(if (state.loading) R.string.advanced_discovering_models else R.string.advanced_discover_models),
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
                            model.contextWindow?.let { append(" · $contextWindowLabel $it") }
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
    val modelKeyRequiredMessage = stringResource(R.string.advanced_model_key_required)
    val modelSavedMessage = stringResource(R.string.advanced_model_saved)
    val modelKeyClearedMessage = stringResource(R.string.advanced_model_key_cleared)
    val colors = DsTheme.colors
    var model by remember(local.model) { mutableStateOf(local.model) }
    var baseUrl by remember(local.baseUrl) { mutableStateOf(local.baseUrl) }
    var apiKey by remember { mutableStateOf("") }

    SettingsCard(stringResource(R.string.advanced_model_settings), Icons.Outlined.Cloud) {
        Text(
            if (local.configured) stringResource(R.string.advanced_model_configured) else stringResource(R.string.advanced_model_unconfigured),
            style = DsType.caption11,
            color = colors.labelTertiary,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_default_model)) },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_endpoint)) },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(if (local.configured) R.string.advanced_replace_model_key else R.string.advanced_model_key)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.advanced_save_model_settings),
                onClick = {
                    if (!local.configured && apiKey.isBlank()) {
                        report(modelKeyRequiredMessage)
                        return@DsButton
                    }
                    viewModel.configureLocalModel(apiKey, model, baseUrl)
                    apiKey = ""
                    report(modelSavedMessage)
                },
                variant = DsButtonVariant.Outline,
            )
            if (local.configured) {
                DsButton(
                    text = stringResource(R.string.advanced_clear_key),
                    onClick = {
                        viewModel.clearLocalCredential()
                        report(modelKeyClearedMessage)
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
    val memorySavedMessage = stringResource(R.string.advanced_memory_saved)
    val colors = DsTheme.colors
    var userRules by remember(local.userRules) { mutableStateOf(local.userRules) }
    var autoRecall by remember(local.autoRecall) { mutableStateOf(local.autoRecall) }
    var autoMemory by remember(local.autoMemory) { mutableStateOf(local.autoMemory) }

    SettingsCard(stringResource(R.string.advanced_memory_settings), Icons.Outlined.Memory) {
        OutlinedTextField(
            value = userRules,
            onValueChange = { userRules = it.take(6_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.advanced_user_rules)) },
            supportingText = { Text(stringResource(R.string.advanced_user_rules_hint)) },
            minLines = 3,
            maxLines = 6,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.advanced_auto_recall), style = DsType.small13Strong, color = colors.labelPrimary)
                Text(stringResource(R.string.advanced_auto_recall_hint), style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoRecall, onCheckedChange = { autoRecall = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.advanced_auto_memory), style = DsType.small13Strong, color = colors.labelPrimary)
                Text(stringResource(R.string.advanced_auto_memory_hint), style = DsType.caption11, color = colors.labelTertiary)
            }
            Switch(checked = autoMemory, onCheckedChange = { autoMemory = it })
        }
        DsButton(
            text = stringResource(R.string.advanced_save_memory_settings),
            onClick = {
                viewModel.configureLocalMemory(userRules, autoRecall, autoMemory)
                report(memorySavedMessage)
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
    val memoryUpdatedMessage = stringResource(R.string.advanced_memory_updated)
    val memoryDeactivatedMessage = stringResource(R.string.advanced_memory_deactivated)
    val colors = DsTheme.colors
    SettingsCard(stringResource(R.string.advanced_manage_memory), Icons.Outlined.Memory) {
        if (records.isEmpty()) {
            Text(
                stringResource(R.string.advanced_memory_empty),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
            return@SettingsCard
        }

        Text(
            stringResource(R.string.advanced_memory_manage_hint),
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
                    if (record.pinned) stringResource(R.string.advanced_pinned_suffix) else "",
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
                        label = { Text(stringResource(R.string.advanced_memory_content)) },
                        minLines = 2,
                        maxLines = 6,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.advanced_pin), style = DsType.small13Strong, color = colors.labelPrimary)
                            Text(stringResource(R.string.advanced_pin_hint), style = DsType.caption11, color = colors.labelTertiary)
                        }
                        Switch(checked = pinned, onCheckedChange = { pinned = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(
                            text = stringResource(R.string.common_save),
                            onClick = {
                                viewModel.updateMemory(
                                    id = record.id,
                                    content = content,
                                    pinned = pinned,
                                ) { error ->
                                    report(error ?: memoryUpdatedMessage)
                                }
                            },
                            size = DsButtonSize.Small,
                            variant = DsButtonVariant.Outline,
                        )
                        DsButton(
                            text = stringResource(R.string.advanced_deactivate),
                            onClick = {
                                viewModel.forgetMemory(record.id) { error ->
                                    report(error ?: memoryDeactivatedMessage)
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
private fun memoryScopeLabel(scope: MemoryScope): String = stringResource(
    when (scope) {
        MemoryScope.GLOBAL -> R.string.advanced_scope_global
        MemoryScope.PROJECT -> R.string.advanced_scope_project
        MemoryScope.LINEAGE -> R.string.advanced_scope_lineage
    },
)

@Composable
private fun memoryKindLabel(kind: MemoryKind): String = stringResource(
    when (kind) {
        MemoryKind.RULE -> R.string.advanced_kind_rule
        MemoryKind.PREFERENCE -> R.string.advanced_kind_preference
        MemoryKind.FACT -> R.string.advanced_kind_fact
        MemoryKind.DECISION -> R.string.advanced_kind_decision
        MemoryKind.CONSTRAINT -> R.string.advanced_kind_constraint
        MemoryKind.STATE -> R.string.advanced_kind_state
        MemoryKind.SUMMARY -> R.string.advanced_kind_summary
    },
)

@Composable
internal fun LocalAgentSettingsCard(
    local: LocalHarnessState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val agentSavedMessage = stringResource(R.string.advanced_agent_saved)
    var mainSteps by remember(local.mainMaxSteps) { mutableStateOf(local.mainMaxSteps.toString()) }
    var subagentSteps by remember(local.subagentMaxSteps) { mutableStateOf(local.subagentMaxSteps.toString()) }
    var attempts by remember(local.modelAttempts) { mutableStateOf(local.modelAttempts.toString()) }

    SettingsCard(stringResource(R.string.advanced_agent_settings), Icons.Outlined.Tune) {
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            OutlinedTextField(
                value = mainSteps,
                onValueChange = { mainSteps = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.advanced_main_steps)) },
            )
            OutlinedTextField(
                value = subagentSteps,
                onValueChange = { subagentSteps = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.advanced_subagent_steps)) },
            )
        }
        OutlinedTextField(
            value = attempts,
            onValueChange = { attempts = it.filter(Char::isDigit).take(1) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_model_attempts)) },
            supportingText = { Text(stringResource(R.string.advanced_agent_limits_hint)) },
        )
        DsButton(
            text = stringResource(R.string.advanced_save_agent_settings),
            onClick = {
                viewModel.configureLocalAgent(
                    mainMaxSteps = mainSteps.toIntOrNull() ?: local.mainMaxSteps,
                    subagentMaxSteps = subagentSteps.toIntOrNull() ?: local.subagentMaxSteps,
                    modelAttempts = attempts.toIntOrNull() ?: local.modelAttempts,
                )
                report(agentSavedMessage)
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
    val visionKeyRequiredMessage = stringResource(R.string.advanced_vision_key_required)
    val visionSavedMessage = stringResource(R.string.advanced_vision_saved)
    val visionKeyClearedMessage = stringResource(R.string.advanced_vision_key_cleared)
    var model by remember(vision.model) { mutableStateOf(vision.model) }
    var baseUrl by remember(vision.baseUrl) { mutableStateOf(vision.baseUrl) }
    var apiKey by remember { mutableStateOf("") }

    SettingsCard(stringResource(R.string.advanced_vision_model), Icons.Outlined.Cloud) {
        Text(
            if (vision.configured) {
                stringResource(R.string.advanced_vision_configured)
            } else {
                stringResource(R.string.advanced_vision_optional)
            },
            style = DsType.caption11,
            color = DsTheme.colors.labelTertiary,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it.take(200) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_vision_model)) },
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.take(1_000) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.advanced_vision_endpoint)) },
            supportingText = {
                Text(stringResource(R.string.advanced_vision_endpoint_hint))
            },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.take(8_000) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(if (vision.configured) R.string.advanced_replace_vision_key else R.string.advanced_vision_key)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.advanced_save_vision),
                onClick = {
                    if (!vision.configured && apiKey.isBlank()) {
                        report(visionKeyRequiredMessage)
                        return@DsButton
                    }
                    viewModel.configureLocalVision(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                    ) { error ->
                        if (error == null) {
                            apiKey = ""
                            report(visionSavedMessage)
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
                    text = stringResource(R.string.advanced_clear_vision_key),
                    onClick = {
                        viewModel.clearLocalVisionCredential { error ->
                            report(error ?: visionKeyClearedMessage)
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
    SettingsCard(stringResource(R.string.advanced_device_capabilities), Icons.Outlined.PhoneAndroid) {
        CapabilityRow(stringResource(R.string.advanced_shizuku_service), state.shizukuAlive)
        CapabilityRow(stringResource(R.string.advanced_shizuku_permission), state.shizukuGranted)
        CapabilityRow(stringResource(R.string.advanced_accessibility), state.accessibility)
        CapabilityRow(stringResource(R.string.advanced_notification_access), state.notifications)
        CapabilityRow(stringResource(R.string.advanced_virtual_display), state.virtualDisplay)
        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            if (!state.shizukuGranted) {
                DsButton(
                    text = stringResource(R.string.advanced_request_shizuku),
                    onClick = viewModel::requestShizukuPermission,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.accessibility) {
                DsButton(
                    text = stringResource(R.string.advanced_enable_accessibility),
                    onClick = viewModel::openAccessibilitySettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            if (!state.notifications) {
                DsButton(
                    text = stringResource(R.string.advanced_enable_notifications),
                    onClick = viewModel::openNotificationAccessSettings,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Outline,
                )
            }
            DsButton(
                text = stringResource(R.string.common_refresh),
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
        Text(stringResource(if (enabled) R.string.advanced_available else R.string.advanced_not_authorized), style = DsType.caption11, color = DsTheme.colors.labelTertiary)
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
