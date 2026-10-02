package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.DeepSeekPricePeriod
import com.labteto.dshmobile.local.DeepSeekPricingState
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.LocalModelCapability
import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelPresets
import com.labteto.dshmobile.local.LocalModelProtocol
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsPill
import com.labteto.dshmobile.ui.components.DsStatus
import com.labteto.dshmobile.ui.components.DsStatusPill
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun LocalAgentSettingsCard(
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val agentSavedMessage = stringResource(R.string.advanced_agent_saved)

    // 步进即保存：不再积攒草稿等“保存”按钮，误触也不可能（有边界钳制）
    fun clampUpdate(current: Int, delta: Int, min: Int, max: Int, apply: (Int) -> Unit) {
        val next = (current + delta).coerceIn(min, max)
        if (next != current) {
            apply(next)
            report(agentSavedMessage)
        }
    }

    SettingsCard(stringResource(R.string.advanced_agent_settings), Icons.Outlined.Tune) {
        StepperRow(
            label = stringResource(R.string.advanced_main_steps),
            hint = stringResource(R.string.advanced_agent_limits_hint),
            value = local.mainMaxSteps,
            range = 4..128,
            onDelta = { delta ->
                clampUpdate(local.mainMaxSteps, delta, 4, 128) {
                    viewModel.configureLocalAgent(it, local.subagentMaxSteps, local.modelAttempts, local.modelSelection.workerProfileId)
                }
            },
        )
        StepperRow(
            label = stringResource(R.string.advanced_subagent_steps),
            hint = null,
            value = local.subagentMaxSteps,
            range = 1..128,
            onDelta = { delta ->
                clampUpdate(local.subagentMaxSteps, delta, 1, 128) {
                    viewModel.configureLocalAgent(local.mainMaxSteps, it, local.modelAttempts, local.modelSelection.workerProfileId)
                }
            },
        )
        StepperRow(
            label = stringResource(R.string.advanced_model_attempts),
            hint = null,
            value = local.modelAttempts,
            range = 1..5,
            onDelta = { delta ->
                clampUpdate(local.modelAttempts, delta, 1, 5) {
                    viewModel.configureLocalAgent(local.mainMaxSteps, local.subagentMaxSteps, it, local.modelSelection.workerProfileId)
                }
            },
        )

        AgentWorkerModelSettingRow(local) { viewModel.configureLocalAgent(local.mainMaxSteps, local.subagentMaxSteps, local.modelAttempts, it) }
    }
}

/** 数值行：标签 + 说明在左，−/值/＋ 在右。 */
@Composable
private fun StepperRow(
    label: String,
    hint: String?,
    value: Int,
    range: IntRange,
    onDelta: (Int) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = DsType.small13Strong.withReadingWeight(), color = colors.labelPrimary)
            hint?.let {
                Text(it, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
            }
        }
        DsButton(
            text = "−",
            onClick = { onDelta(-1) },
            enabled = value > range.first,
            size = DsButtonSize.Small,
            variant = DsButtonVariant.Ghost,
        )
        Text(
            value.toString(),
            style = DsType.std14Strong.withReadingWeight(),
            color = colors.labelPrimary,
            modifier = Modifier.widthIn(min = 30.dp),
            textAlign = TextAlign.Center,
        )
        DsButton(
            text = "＋",
            onClick = { onDelta(1) },
            enabled = value < range.last,
            size = DsButtonSize.Small,
            variant = DsButtonVariant.Ghost,
        )
    }
}

@Composable
internal fun DeviceCapabilitiesCard(
    state: DeviceCapabilitiesState,
    viewModel: SettingsViewModel,
) {
    val overallState = when {
        state.loading -> DsStatus.Running
        state.error != null -> DsStatus.Failed
        state.accessibility && state.notifications && state.virtualDisplay -> DsStatus.Done
        else -> DsStatus.Warning
    }
    val overallLabel = stringResource(
        when {
            state.loading -> R.string.advanced_device_status_checking
            state.error != null -> R.string.advanced_device_status_error
            overallState == DsStatus.Done -> R.string.advanced_device_status_ready
            else -> R.string.advanced_device_status_needs_setup
        },
    )

    SettingsCard(stringResource(R.string.advanced_device_capabilities), Icons.Outlined.PhoneAndroid) {
        DsStatusPill(state = overallState, label = overallLabel)
        CapabilityRow(
            label = stringResource(R.string.advanced_accessibility),
            enabled = state.accessibility,
            hint = stringResource(R.string.advanced_accessibility_hint),
            actionLabel = stringResource(R.string.advanced_enable_accessibility),
            onAction = viewModel::openAccessibilitySettings,
        )
        CapabilityRow(
            label = stringResource(R.string.advanced_notification_access),
            enabled = state.notifications,
            hint = stringResource(R.string.advanced_notification_access_hint),
            actionLabel = stringResource(R.string.advanced_enable_notifications),
            onAction = viewModel::openNotificationAccessSettings,
        )
        CapabilityRow(
            label = stringResource(R.string.advanced_virtual_display),
            enabled = state.virtualDisplay,
            hint = stringResource(R.string.advanced_virtual_display_hint),
        )
        DsButton(
            text = stringResource(R.string.common_refresh),
            onClick = viewModel::refreshDeviceCapabilities,
            size = DsButtonSize.Small,
            variant = DsButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.error) }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    enabled: Boolean,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        StateDot(if (enabled) StateDotState.Done else StateDotState.Idle)
        Column(Modifier.weight(1f)) {
            Text(label, style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
            Text(hint, style = DsType.caption11.withReadingWeight(), color = colors.labelCaption)
        }
        if (enabled) {
            Text(
                stringResource(R.string.advanced_available),
                style = DsType.caption11.withReadingWeight(),
                color = colors.success,
            )
        } else if (actionLabel != null && onAction != null) {
            DsButton(
                text = actionLabel,
                onClick = onAction,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Outline,
            )
        } else {
            Text(
                stringResource(R.string.advanced_not_authorized),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
        }
    }
}
