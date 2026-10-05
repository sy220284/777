package com.labteto.dshmobile.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.presentation.LocalHarnessSettingsState
import com.labteto.dshmobile.local.settings.LocalAgentRuntimeLimits
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight

@Composable
internal fun LocalAgentSettingsCard(
    local: LocalHarnessSettingsState,
    viewModel: SettingsViewModel,
    report: (String) -> Unit,
) {
    val agentSavedMessage = stringResource(R.string.advanced_agent_saved)
    val maximumLabel = stringResource(R.string.common_maximum)
    val reportSaved = { report(agentSavedMessage) }

    SettingsCard(stringResource(R.string.advanced_agent_settings), Icons.Outlined.Tune) {
        StepperRow(
            label = stringResource(R.string.advanced_main_steps),
            hint = stringResource(R.string.advanced_agent_limits_hint),
            value = local.mainMaxSteps,
            range = LocalAgentRuntimeLimits.MAIN_MIN_STEPS..LocalAgentRuntimeLimits.MAX_CONFIGURED_STEPS,
            maximumLabel = maximumLabel,
            onValueChange = {
                viewModel.configureLocalAgent(
                    it,
                    local.subagentMaxSteps,
                    local.modelAttempts,
                    local.modelSelection.workerProfileId,
                )
            },
            onCommitted = reportSaved,
        )
        StepperRow(
            label = stringResource(R.string.advanced_subagent_steps),
            hint = null,
            value = local.subagentMaxSteps,
            range = LocalAgentRuntimeLimits.SUBAGENT_MIN_STEPS..LocalAgentRuntimeLimits.MAX_CONFIGURED_STEPS,
            maximumLabel = maximumLabel,
            onValueChange = {
                viewModel.configureLocalAgent(
                    local.mainMaxSteps,
                    it,
                    local.modelAttempts,
                    local.modelSelection.workerProfileId,
                )
            },
            onCommitted = reportSaved,
        )
        StepperRow(
            label = stringResource(R.string.advanced_model_attempts),
            hint = null,
            value = local.modelAttempts,
            range = LocalAgentRuntimeLimits.MODEL_ATTEMPTS_MIN..LocalAgentRuntimeLimits.MODEL_ATTEMPTS_MAX,
            onValueChange = {
                viewModel.configureLocalAgent(
                    local.mainMaxSteps,
                    local.subagentMaxSteps,
                    it,
                    local.modelSelection.workerProfileId,
                )
            },
            onCommitted = reportSaved,
        )

        AgentWorkerModelSettingRow(local) {
            viewModel.configureLocalAgent(
                local.mainMaxSteps,
                local.subagentMaxSteps,
                local.modelAttempts,
                it,
            )
        }
    }
}

/** 数值行：支持单击、长按连续步进、直接输入；步数项可一键设为最大值。 */
@Composable
private fun StepperRow(
    label: String,
    hint: String?,
    value: Int,
    range: IntRange,
    maximumLabel: String? = null,
    onValueChange: (Int) -> Unit,
    onCommitted: () -> Unit,
) {
    val colors = DsTheme.colors
    val focusManager = LocalFocusManager.current
    var draft by remember(value) { mutableStateOf(value.toString()) }

    fun applyValue(candidate: Int, announce: Boolean) {
        val next = candidate.coerceIn(range.first, range.last)
        draft = next.toString()
        if (next == value) return
        onValueChange(next)
        if (announce) onCommitted()
    }

    fun applyDelta(delta: Int, announce: Boolean) {
        val base = draft.toIntOrNull()?.coerceIn(range.first, range.last) ?: value
        applyValue(base + delta, announce)
    }

    fun commitDraft(announce: Boolean) {
        val parsed = draft.toIntOrNull()
        if (parsed == null) {
            draft = value.toString()
            return
        }
        applyValue(parsed, announce)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = "−",
                onClick = { applyDelta(-1, announce = true) },
                onHoldRepeat = { applyDelta(-1, announce = false) },
                enabled = value > range.first,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
            Surface(
                modifier = Modifier.width(64.dp),
                shape = DsShapes.buttonSmall,
                color = colors.wallpaperSurface(WallpaperSurfaceLevel.FLOATING, base = colors.bgLayer2),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { next ->
                            if (next.length <= 9 && next.all { it.isDigit() }) draft = next
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (!focus.isFocused) commitDraft(announce = false)
                            }
                            .padding(horizontal = DsSpacing.xsmall),
                        singleLine = true,
                        textStyle = DsType.std14Strong.withReadingWeight().copy(
                            color = colors.labelPrimary,
                            textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                commitDraft(announce = true)
                                focusManager.clearFocus()
                            },
                        ),
                        cursorBrush = SolidColor(colors.accent),
                    )
                }
            }
            DsButton(
                text = "＋",
                onClick = { applyDelta(1, announce = true) },
                onHoldRepeat = { applyDelta(1, announce = false) },
                enabled = value < range.last,
                size = DsButtonSize.Small,
                variant = DsButtonVariant.Ghost,
            )
            maximumLabel?.let { labelText ->
                DsButton(
                    text = labelText,
                    onClick = { applyValue(range.last, announce = true) },
                    enabled = value < range.last,
                    size = DsButtonSize.Small,
                    variant = DsButtonVariant.Ghost,
                )
            }
        }
        hint?.let {
            Text(
                it,
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
                modifier = Modifier.padding(top = DsSpacing.tiny),
            )
        }
    }
}
