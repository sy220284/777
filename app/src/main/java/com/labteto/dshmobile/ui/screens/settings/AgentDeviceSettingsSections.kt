package com.labteto.dshmobile.ui.screens.settings


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.core.wire.dto.LlmConfigurableProvider
import com.labteto.dshmobile.core.wire.dto.SettingsNamespaceView
import com.labteto.dshmobile.local.memory.MemoryKind
import com.labteto.dshmobile.local.memory.MemoryRecord
import com.labteto.dshmobile.local.memory.MemoryScope
import com.labteto.dshmobile.local.model.DeepSeekBillingSchedule
import com.labteto.dshmobile.local.model.DeepSeekPricePeriod
import com.labteto.dshmobile.local.model.DeepSeekPricingState
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelCapability
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.ui.components.DisclosureRow
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyStatus
import com.labteto.dshmobile.ui.shenyan.SyStatusPill
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun DeviceCapabilitiesCard(
    state: DeviceCapabilitiesState,
    viewModel: SettingsViewModel,
    onRequestPostingPermission: () -> Unit,
) {
    val overallState = when {
        state.loading -> SyStatus.Running
        state.error != null -> SyStatus.Failed
        state.accessibility && state.notifications && state.postingNotifications == true -> SyStatus.Done
        else -> SyStatus.Warning
    }
    val overallLabel = stringResource(
        when {
            state.loading -> R.string.advanced_device_status_checking
            state.error != null -> R.string.advanced_device_status_error
            overallState == SyStatus.Done -> R.string.advanced_device_status_ready
            else -> R.string.advanced_device_status_needs_setup
        },
    )

    SettingsCard(stringResource(R.string.advanced_device_capabilities)) {
        SyStatusPill(state = overallState, label = overallLabel)
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
        state.postingNotifications?.let { enabled ->
            CapabilityRow(
                label = stringResource(R.string.settings_notifications_posting_permission),
                enabled = enabled,
                hint = stringResource(R.string.settings_notifications_posting_hint),
                actionLabel = stringResource(R.string.settings_notifications_request),
                onAction = onRequestPostingPermission,
            )
        }
        CapabilityRow(
            label = stringResource(R.string.advanced_virtual_display),
            enabled = state.virtualDisplay,
            hint = stringResource(R.string.advanced_virtual_display_hint),
            successLabel = stringResource(R.string.settings_device_supported_not_granted),
        )
        SyButton(
            text = stringResource(R.string.common_refresh),
            onClick = viewModel::refreshDeviceCapabilities,
            size = SyButtonSize.Small,
            variant = SyButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk) }
    }
}

/** Screen 28: posting permission is not the notification-listener permission. */
@Composable
internal fun NotificationPostingPermissionCard(
    state: DeviceCapabilitiesState,
    viewModel: SettingsViewModel,
    onRequestPostingPermission: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_notifications_system_group)) {
        CapabilityRow(
            label = stringResource(R.string.settings_notifications_posting_permission),
            enabled = state.postingNotifications == true,
            hint = stringResource(R.string.settings_notifications_posting_hint),
            actionLabel = stringResource(R.string.settings_notifications_request),
            onAction = onRequestPostingPermission,
        )
        if (state.postingNotifications != true) {
            SyButton(
                text = stringResource(R.string.settings_notifications_open_system),
                onClick = viewModel::openAppNotificationSettings,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(R.string.settings_notifications_only_events),
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        )
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    enabled: Boolean,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    successLabel: String? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.xsmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        StateDot(if (enabled) StateDotState.Done else StateDotState.Idle)
        Column(Modifier.weight(1f)) {
            Text(label, style = DsType.std14.withReadingWeight(), color = colors.textSecondary)
            Text(hint, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
        }
        if (enabled) {
            Text(
                successLabel ?: stringResource(R.string.advanced_available),
                style = DsType.caption11.withReadingWeight(),
                color = colors.successInk,
            )
        } else if (actionLabel != null && onAction != null) {
            SyButton(
                text = actionLabel,
                onClick = onAction,
                size = SyButtonSize.Small,
                variant = SyButtonVariant.Outline,
            )
        } else {
            Text(
                stringResource(R.string.advanced_not_authorized),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
        }
    }
}
