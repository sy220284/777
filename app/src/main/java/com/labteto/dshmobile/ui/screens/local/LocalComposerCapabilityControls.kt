package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
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
): Int = when {
    modes.isEmpty() || (mode != LocalReasoningUiMode.DEFAULT && mode !in modes) ->
        R.string.local_composer_reasoning_default
    mode == LocalReasoningUiMode.DEFAULT && LocalReasoningUiMode.DEFAULT !in modes ->
        R.string.local_composer_reasoning_high
    mode == LocalReasoningUiMode.DEFAULT -> R.string.local_composer_reasoning_default
    mode == LocalReasoningUiMode.FAST ->
        if (needsBasicReasoning) R.string.local_composer_reasoning_basic
        else R.string.local_composer_reasoning_short_off
    mode == LocalReasoningUiMode.LOW -> R.string.local_composer_reasoning_low
    mode == LocalReasoningUiMode.DEEP -> R.string.local_composer_reasoning_high
    else -> R.string.local_composer_reasoning_max
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
        DropdownMenu(
            expanded = openPanel != null && !running,
            onDismissRequest = { onSelectPanel(null) },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
            shape = DsShapes.menu,
            containerColor = colors.wallpaperSurface(WallpaperSurfaceLevel.MENU),
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, colors.borderL1),
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = true,
            ),
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
                val effectiveMode = if (reasoningMode == LocalReasoningUiMode.DEFAULT && reasoningMode !in modes) {
                    LocalReasoningUiMode.DEEP.takeIf { it in modes } ?: modes.first()
                } else reasoningMode.takeIf { it in modes } ?: modes.first()
                var chosen by remember(profile?.id, usageMode, reasoningMode, modes) {
                    mutableFloatStateOf(modes.indexOf(effectiveMode).toFloat())
                }
                Slider(
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
                if (reasoningMode == LocalReasoningUiMode.DEFAULT) {
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
                Slider(
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
