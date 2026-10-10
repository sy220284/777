package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.session.handoffAttachmentNames
import com.labteto.dshmobile.local.session.visibleWorkHandoffMessages
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
    selectableMessages: List<LocalHarnessMessage> = emptyList(),
    selectedMessageIds: List<String> = emptyList(),
    onSelectedMessageIdsChange: (List<String>) -> Unit = {},
    messageSearch: String = "",
    onMessageSearchChange: (String) -> Unit = {},
    hasEarlierMessages: Boolean = false,
    loadingEarlierMessages: Boolean = false,
    onLoadEarlierMessages: (() -> Unit)? = null,
    onPromptChange: (String) -> Unit = {},
    onSummaryChange: (String) -> Unit = {},
    capabilities: List<LocalTaskCapabilityReadiness> = emptyList(),
    onConfigureCapabilities: (() -> Unit)? = null,
    onRefreshCapabilities: (() -> Unit)? = null,
    onConfigureModel: (() -> Unit)? = null,
    onConfigureDevice: (() -> Unit)? = null,
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
        if (selectableMessages.isNotEmpty() || hasEarlierMessages) {
            Text(stringResource(R.string.work_handoff_select_messages),
                style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
            if (selectableMessages.size > 12 || hasEarlierMessages) {
                DsTextField(
                    value = messageSearch, onValueChange = onMessageSearchChange,
                    placeholder = { Text(stringResource(R.string.work_handoff_search_messages)) },
                    modifier = Modifier.fillMaxWidth().testTag("handoff_search"),
                    enabled = !switching, singleLine = true,
                )
            }
            val visible = visibleWorkHandoffMessages(
                selectableMessages, messageSearch, selectedMessageIds,
            )
            if (visible.isEmpty() && messageSearch.isNotBlank()) {
                Text(stringResource(R.string.work_handoff_no_matches),
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            }
            visible.forEach { message ->
                val checked = message.id in selectedMessageIds
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        modifier = Modifier.testTag("handoff_message_${message.id}"),
                        enabled = !switching,
                        onCheckedChange = { isChecked ->
                            onSelectedMessageIdsChange(
                                if (isChecked) (selectedMessageIds + message.id).distinct()
                                else selectedMessageIds - message.id,
                            )
                        },
                    )
                    val speaker = if (message.role == "user")
                        stringResource(R.string.work_handoff_user) else stringResource(R.string.work_handoff_assistant)
                    val itemPreview = message.content.trim().replace(Regex("\\s+"), " ")
                        .take(100).ifBlank {
                            message.handoffAttachmentNames().joinToString("、").take(100)
                        }
                    Text("$speaker · $itemPreview",
                        style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
                }
            }
            if (hasEarlierMessages && onLoadEarlierMessages != null) {
                DsButton(
                    text = stringResource(R.string.work_handoff_load_older),
                    onClick = onLoadEarlierMessages,
                    modifier = Modifier.fillMaxWidth().testTag("handoff_load_older"),
                    enabled = !switching && !loadingEarlierMessages,
                    loading = loadingEarlierMessages,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
        if (capabilities.isNotEmpty()) {
            Text(stringResource(R.string.work_capability_readiness_title),
                style = DsType.std14.withReadingWeight(), color = colors.labelPrimary)
            capabilities.forEach { item ->
                val name = when (item.kind) {
                    LocalTaskCapabilityKind.GITHUB -> R.string.work_capability_readiness_github
                    LocalTaskCapabilityKind.WEB_SEARCH -> R.string.work_capability_readiness_web
                    LocalTaskCapabilityKind.MODEL -> R.string.work_capability_readiness_model
                    LocalTaskCapabilityKind.MCP -> R.string.work_capability_readiness_mcp
                    LocalTaskCapabilityKind.PLUGINS -> R.string.work_capability_readiness_plugins
                    LocalTaskCapabilityKind.ACCESSIBILITY -> R.string.work_capability_readiness_accessibility
                    LocalTaskCapabilityKind.NOTIFICATION_ACCESS -> R.string.work_capability_readiness_notification_access
                    LocalTaskCapabilityKind.SCREEN_CAPTURE -> R.string.work_capability_readiness_screen_capture
                }
                val status = when (item.state) {
                    LocalTaskCapabilityState.CONFIGURED -> R.string.work_capability_readiness_configured
                    LocalTaskCapabilityState.CONNECTION_REQUIRED -> R.string.work_capability_readiness_needs_connection
                    LocalTaskCapabilityState.DISABLED -> R.string.work_capability_readiness_disabled
                    LocalTaskCapabilityState.UNKNOWN -> R.string.work_capability_readiness_unknown
                }
                val statusLabel = when {
                    item.kind == LocalTaskCapabilityKind.MODEL &&
                        item.state == LocalTaskCapabilityState.CONNECTION_REQUIRED ->
                        stringResource(R.string.work_capability_readiness_model_missing)
                    item.kind == LocalTaskCapabilityKind.SCREEN_CAPTURE ->
                        stringResource(R.string.work_capability_readiness_screen_capture_prompt)
                    (item.kind == LocalTaskCapabilityKind.ACCESSIBILITY ||
                        item.kind == LocalTaskCapabilityKind.NOTIFICATION_ACCESS) &&
                        item.state == LocalTaskCapabilityState.CONNECTION_REQUIRED ->
                        stringResource(R.string.work_capability_readiness_device_missing)
                    else -> stringResource(status)
                }
                Text(stringResource(R.string.work_capability_readiness_row,
                    stringResource(name), statusLabel),
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            }
            if (onConfigureCapabilities != null && capabilities.any {
                    it.kind != LocalTaskCapabilityKind.MODEL &&
                        it.kind != LocalTaskCapabilityKind.ACCESSIBILITY &&
                        it.kind != LocalTaskCapabilityKind.NOTIFICATION_ACCESS &&
                        it.kind != LocalTaskCapabilityKind.SCREEN_CAPTURE &&
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
            if (onConfigureModel != null && capabilities.any {
                    it.kind == LocalTaskCapabilityKind.MODEL &&
                        it.state != LocalTaskCapabilityState.CONFIGURED
                }) {
                DsButton(
                    text = stringResource(R.string.work_capability_configure_model),
                    onClick = onConfigureModel,
                    enabled = !switching,
                    modifier = Modifier.fillMaxWidth(),
                    variant = DsButtonVariant.Ghost,
                )
            }
            if (onConfigureDevice != null && capabilities.any {
                    (it.kind == LocalTaskCapabilityKind.ACCESSIBILITY ||
                        it.kind == LocalTaskCapabilityKind.NOTIFICATION_ACCESS) &&
                        it.state != LocalTaskCapabilityState.CONFIGURED
                }) {
                DsButton(
                    text = stringResource(R.string.work_capability_configure_device),
                    onClick = onConfigureDevice,
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
