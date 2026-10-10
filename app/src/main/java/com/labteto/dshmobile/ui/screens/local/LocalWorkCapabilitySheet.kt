package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityKind
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityReadiness
import com.labteto.dshmobile.local.tools.LocalTaskCapabilityState
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight

/** The user explicitly enters the surface that owns executable capabilities. */
@Composable
internal fun LocalWorkCapabilitySheet(
    switching: Boolean,
    failed: Boolean,
    enabled: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    prompt: String = "",
    summary: String = "",
    onPromptChange: (String) -> Unit = {},
    onSummaryChange: (String) -> Unit = {},
    capabilities: List<LocalTaskCapabilityReadiness> = emptyList(),
    onConfigureCapabilities: (() -> Unit)? = null,
    onRefreshCapabilities: (() -> Unit)? = null,
) {
    val colors = DsTheme.colors
    DsBottomSheet(
        title = stringResource(R.string.work_capability_title),
        onDismiss = onDismiss,
        dismissEnabled = !switching,
        scrollable = true,
        footer = {
            DsButton(
                text = stringResource(R.string.work_capability_continue),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                size = DsButtonSize.Large,
                enabled = enabled && !switching,
                loading = switching,
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                enabled = !switching,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        },
    ) {
        Text(stringResource(R.string.work_capability_hint), style = DsType.std14.withReadingWeight(), color = colors.labelSecondary)
        DsTextField(
            value = prompt, onValueChange = onPromptChange,
            label = { Text(stringResource(R.string.work_handoff_task)) }, enabled = !switching,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
        )
        DsTextField(
            value = summary, onValueChange = onSummaryChange,
            label = { Text(stringResource(R.string.work_handoff_summary)) }, enabled = !switching,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
        )
        Text(stringResource(R.string.work_handoff_scope), style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
        if (capabilities.isNotEmpty()) {
            Text(stringResource(R.string.work_capability_readiness_title),
                style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
            capabilities.forEach { item ->
                val name = when (item.kind) {
                    LocalTaskCapabilityKind.GITHUB -> R.string.work_capability_readiness_github
                    LocalTaskCapabilityKind.WEB_SEARCH -> R.string.work_capability_readiness_web
                }
                val status = when (item.state) {
                    LocalTaskCapabilityState.CONFIGURED -> R.string.work_capability_readiness_configured
                    LocalTaskCapabilityState.CONNECTION_REQUIRED -> R.string.work_capability_readiness_needs_connection
                    LocalTaskCapabilityState.DISABLED -> R.string.work_capability_readiness_disabled
                    LocalTaskCapabilityState.UNKNOWN -> R.string.work_capability_readiness_unknown
                }
                Text(stringResource(R.string.work_capability_readiness_row,
                    stringResource(name), stringResource(status)),
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            }
            if (onConfigureCapabilities != null && capabilities.any {
                    it.state != LocalTaskCapabilityState.CONFIGURED
                }) {
                DsButton(
                    text = stringResource(R.string.work_capability_configure),
                    onClick = onConfigureCapabilities,
                    enabled = !switching,
                    modifier = Modifier.fillMaxWidth(),
                    variant = DsButtonVariant.Ghost,
                )
            }
            if (onRefreshCapabilities != null) {
                DsButton(
                    text = stringResource(R.string.common_refresh),
                    onClick = onRefreshCapabilities,
                    enabled = !switching,
                    modifier = Modifier.fillMaxWidth(),
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
        if (failed) Text(stringResource(R.string.work_capability_failed), style = DsType.small13.withReadingWeight(), color = colors.error)
    }
}
