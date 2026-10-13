package com.labteto.dshmobile.ui.screens.local


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.labteto.dshmobile.R
import com.shenyan.designsystem.components.SyTextField
import com.shenyan.designsystem.components.SyRadioControl
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.shenyan.designsystem.components.SyGroupCard
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.rootSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalConfiguration(
    state: LocalConversationSurfaceState,
    canCancel: Boolean,
    onCancel: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onClearCredential: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var apiKey by remember { mutableStateOf("") }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Column(
        Modifier.fillMaxSize().background(colors.rootSurface()).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState())
            .padding(DsSpacing.xlarge),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.local_harness_title),
                style = DsType.large20.withReadingWeight(),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            if (canCancel) {
                SyButton(stringResource(R.string.common_cancel), onCancel, variant = SyButtonVariant.Ghost)
            }
        }

        SyGlassCard(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            Text(stringResource(R.string.local_on_device_execution_title), style = DsType.base16Strong.withReadingWeight(), color = colors.text)
            Text(
                stringResource(R.string.local_on_device_execution_hint),
                style = DsType.std14.withReadingWeight(),
                color = colors.textSecondary,
            )
        }

        Text(stringResource(R.string.local_model_section_title), style = DsType.std14.withReadingWeight(), color = colors.textTertiary)
        SyGroupCard {
            SyTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (state.configured) R.string.advanced_replace_model_key else R.string.advanced_model_key)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (state.configured) R.string.advanced_model_configured
                            else R.string.advanced_model_unconfigured,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Spacer(Modifier.height(DsSpacing.medium))
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                Text(stringResource(R.string.models_title), style = DsType.std14Strong.withReadingWeight(), color = colors.text)
                ModelChoice("deepseek-flash", stringResource(R.string.local_model_flash_label), model) { model = it }
                ModelChoice("deepseek-v4-pro", stringResource(R.string.local_model_pro_label), model) { model = it }
            }
            Spacer(Modifier.height(DsSpacing.medium))
            SyTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.advanced_endpoint)) },
                supportingText = { Text(stringResource(R.string.local_endpoint_hint)) },
                singleLine = true,
            )
        }

        state.error?.let { Text(it, style = DsType.small13.withReadingWeight(), color = colors.dangerInk) }

        SyButton(
            text = stringResource(R.string.local_model_save_enter),
            onClick = { onSave(apiKey, model, baseUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = (state.configured || apiKey.isNotBlank()) && baseUrl.isNotBlank(),
        )
        if (state.configured) {
            SyButton(
                text = stringResource(R.string.advanced_clear_key),
                onClick = onClearCredential,
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Danger,
            )
        }
    }
}

@Composable
internal fun ModelChoice(id: String, label: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(DsShapes.row)
            .heightIn(min = DsSpacing.touchTarget)
            .selectable(selected = selected == id, onClick = { onSelect(id) })
            .padding(vertical = DsSpacing.tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyRadioControl(selected = selected == id, onClick = { onSelect(id) })
        Text(label, style = DsType.std14.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
    }
}
