package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.math.roundToInt

/** Identical request-policy and UI capability assessment, never a guessed model tier. */
internal fun composerReasoningLabel(
    mode: LocalReasoningUiMode,
    modes: List<LocalReasoningUiMode>,
    needsBasicReasoning: Boolean = false,
): Int = when (LocalReasoningControls.effectiveMode(mode, modes, needsBasicReasoning)) {
    LocalReasoningUiMode.DEFAULT -> R.string.local_composer_reasoning_default
    LocalReasoningUiMode.FAST ->
        if (needsBasicReasoning) R.string.local_composer_reasoning_basic
        else R.string.local_composer_reasoning_short_off
    LocalReasoningUiMode.LOW -> R.string.local_composer_reasoning_low
    LocalReasoningUiMode.DEEP -> R.string.local_composer_reasoning_high
    LocalReasoningUiMode.MAX -> R.string.local_composer_reasoning_max
}

private fun reasoningModeLabel(mode: LocalReasoningUiMode, needsBasicReasoning: Boolean): Int =
    when (mode) {
        LocalReasoningUiMode.DEFAULT -> R.string.local_composer_reasoning_default
        LocalReasoningUiMode.FAST ->
            if (needsBasicReasoning) R.string.local_composer_reasoning_basic
            else R.string.local_composer_reasoning_off
        LocalReasoningUiMode.LOW -> R.string.local_composer_reasoning_low
        LocalReasoningUiMode.DEEP -> R.string.local_composer_reasoning_high
        LocalReasoningUiMode.MAX -> R.string.local_composer_reasoning_max
    }

/** Both actions share a popup anchor. DropdownMenu floats ABOVE the keyboard when needed,
 * rather than changing the text field height and scroll position. */
@Composable
internal fun LocalComposerCapabilityActions(
    profile: LocalModelProfile?,
    usageMode: LocalUsageMode,
    reasoningMode: LocalReasoningUiMode,
    networkSearchEnabled: Boolean,
    running: Boolean,
    showLabels: Boolean,
    openPanel: String?,
    onSelectPanel: (String?) -> Unit,
    onReasoningModeChange: (LocalReasoningUiMode) -> Unit,
    onNetworkSearchChange: (Boolean) -> Unit,
    onWorkSearchRequested: () -> Unit,
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    val basicReasoning = LocalReasoningControls.requiresBasicReasoning(profile, usageMode)
    val webAvailable = usageMode == LocalUsageMode.WORK
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsComposerAction(
                icon = FeatherIcons.Gauge,
                contentDescription = stringResource(
                    R.string.local_composer_reasoning_action,
                    stringResource(composerReasoningLabel(reasoningMode, modes, basicReasoning)),
                ),
                onClick = { onSelectPanel(if (openPanel == "reasoning") null else "reasoning") },
                enabled = !running,
                tint = colors.labelPrimary,
                containerColor = Color.Transparent,
            )
            if (showLabels) {
                Text(
                    text = stringResource(composerReasoningLabel(reasoningMode, modes, basicReasoning)),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
            DsComposerAction(
                icon = FeatherIcons.Globe,
                contentDescription = stringResource(when {
                    !webAvailable -> R.string.local_composer_web_work_only
                    networkSearchEnabled -> R.string.local_composer_web_on_action
                    else -> R.string.local_composer_web_off_action
                }),
                onClick = { onSelectPanel(if (openPanel == "web") null else "web") },
                enabled = !running,
                tint = if (webAvailable && networkSearchEnabled) colors.labelPrimary else colors.labelTertiary,
                containerColor = Color.Transparent,
            )
            if (showLabels) {
                Text(
                    text = stringResource(when {
                        !webAvailable -> R.string.local_composer_web_work_label
                        networkSearchEnabled -> R.string.local_composer_web_online
                        else -> R.string.local_composer_web_offline
                    }),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
        }
        DsPopupMenu(
            expanded = openPanel != null && !running,
            onDismiss = { onSelectPanel(null) },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
            focusable = false,
        ) {
            openPanel?.let { panel ->
                LocalComposerCapabilityPanel(
                    panel = panel,
                    profile = profile,
                    usageMode = usageMode,
                    reasoningMode = reasoningMode,
                    onReasoningModeChange = {
                        onReasoningModeChange(it)
                        onSelectPanel(null)
                    },
                    networkSearchEnabled = networkSearchEnabled,
                    onNetworkSearchChange = {
                        onNetworkSearchChange(it)
                        onSelectPanel(null)
                    },
                    onWorkSearchRequested = {
                        onSelectPanel(null)
                        onWorkSearchRequested()
                    },
                )
            }
        }
    }
}

@Composable
internal fun LocalComposerCapabilityPanel(
    panel: String,
    profile: LocalModelProfile?,
    usageMode: LocalUsageMode,
    reasoningMode: LocalReasoningUiMode,
    onReasoningModeChange: (LocalReasoningUiMode) -> Unit,
    networkSearchEnabled: Boolean,
    onNetworkSearchChange: (Boolean) -> Unit,
    onWorkSearchRequested: () -> Unit,
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    val basicReasoning = LocalReasoningControls.requiresBasicReasoning(profile, usageMode)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
    ) {
        if (panel == "reasoning") {
            Text(
                stringResource(R.string.local_composer_reasoning_title),
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary,
            )
            if (modes.isEmpty()) {
                Text(stringResource(R.string.local_composer_reasoning_unsupported),
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
            } else {
                val effectiveMode = LocalReasoningControls.effectiveMode(reasoningMode, modes, basicReasoning)
                var chosen by remember(profile?.id, usageMode, reasoningMode, modes) {
                    mutableFloatStateOf(modes.indexOf(effectiveMode).toFloat())
                }
                val sliderTitle = stringResource(R.string.local_composer_reasoning_title)
                val sliderState = stringResource(reasoningModeLabel(
                    modes[chosen.roundToInt().coerceIn(0, modes.lastIndex)], basicReasoning,
                ))
                Slider(
                    modifier = Modifier.semantics {
                        contentDescription = sliderTitle
                        stateDescription = sliderState
                    },
                    value = chosen,
                    onValueChange = { chosen = it },
                    onValueChangeFinished = {
                        onReasoningModeChange(modes[chosen.roundToInt().coerceIn(0, modes.lastIndex)])
                    },
                    valueRange = 0f..modes.lastIndex.toFloat(),
                    steps = (modes.size - 2).coerceAtLeast(0),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    modes.forEach { mode ->
                        Text(stringResource(reasoningModeLabel(mode, basicReasoning)),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    }
                }
                if (effectiveMode == LocalReasoningUiMode.DEFAULT) {
                    Text(stringResource(R.string.local_composer_reasoning_default_tip),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
            }
        } else {
            Text(
                stringResource(R.string.local_composer_web_title),
                style = DsType.std14Strong.withReadingWeight(), color = colors.labelPrimary,
            )
            if (usageMode != LocalUsageMode.WORK) {
                Text(stringResource(R.string.local_composer_web_work_only),
                    style = DsType.small13.withReadingWeight(), color = colors.labelSecondary)
                TextButton(onClick = onWorkSearchRequested) {
                    Text(stringResource(R.string.local_composer_web_to_work))
                }
            } else {
                var selected by remember(networkSearchEnabled) {
                    mutableFloatStateOf(if (networkSearchEnabled) 1f else 0f)
                }
                val sliderTitle = stringResource(R.string.local_composer_web_title)
                val sliderState = stringResource(
                    if (selected >= 0.5f) R.string.local_composer_web_on else R.string.local_composer_web_off,
                )
                Slider(
                    modifier = Modifier.semantics {
                        contentDescription = sliderTitle
                        stateDescription = sliderState
                    },
                    value = selected,
                    onValueChange = { selected = it },
                    onValueChangeFinished = { onNetworkSearchChange(selected >= 0.5f) },
                    valueRange = 0f..1f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.local_composer_web_off),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text(stringResource(R.string.local_composer_web_on),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
                Text(stringResource(R.string.local_composer_web_tip),
                    style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
            }
        }
    }
}
