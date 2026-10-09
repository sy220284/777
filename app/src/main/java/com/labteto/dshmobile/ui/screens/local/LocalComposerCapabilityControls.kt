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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.ui.components.DsComposerAction
import com.labteto.dshmobile.ui.components.DsPopupMenu
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberHapticTickFeedback
import com.labteto.dshmobile.ui.components.rememberHapticPulse
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
    temperatureLevel: Int = 2,
    temperaturePosition: Int? = null,
    temperatureEnabled: Boolean = true,
    temperatureSaveFailed: Boolean = false,
    temperatureSaving: Boolean = false,
    temperatureGroupChat: Boolean = false,
    onTemperatureLevelChange: (Int) -> Unit = {},
    running: Boolean,
    showLabels: Boolean,
    openPanel: String?,
    onSelectPanel: (String?) -> Unit,
    onReasoningModeChange: (LocalReasoningUiMode) -> Unit,
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    val basicReasoning = LocalReasoningControls.requiresBasicReasoning(profile, usageMode)
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

        }
        DsPopupMenu(
            expanded = openPanel == "reasoning" && !running,
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
                    temperatureLevel = temperatureLevel,
                    temperaturePosition = temperaturePosition,
                    temperatureEnabled = temperatureEnabled,
                    temperatureSaveFailed = temperatureSaveFailed,
                    temperatureSaving = temperatureSaving,
                    temperatureGroupChat = temperatureGroupChat,
                    onTemperatureLevelChange = onTemperatureLevelChange,
                    onReasoningModeChange = onReasoningModeChange,
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
    temperatureLevel: Int = 2,
    temperaturePosition: Int? = null,
    temperatureEnabled: Boolean = true,
    temperatureSaveFailed: Boolean = false,
    temperatureSaving: Boolean = false,
    temperatureGroupChat: Boolean = false,
    onTemperatureLevelChange: (Int) -> Unit = {},
) {
    val colors = DsTheme.colors
    val modes = LocalReasoningControls.availableModes(profile, usageMode)
    val basicReasoning = LocalReasoningControls.requiresBasicReasoning(profile, usageMode)
    val effectiveMode = LocalReasoningControls.effectiveMode(reasoningMode, modes, basicReasoning)
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
                var chosen by remember(profile?.id, usageMode, reasoningMode, modes) {
                    mutableFloatStateOf(modes.indexOf(effectiveMode).toFloat())
                }
                val tick = rememberHapticTickFeedback(modes.indexOf(effectiveMode))
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
                    onValueChange = { next ->
                        chosen = next
                        tick(next.roundToInt().coerceIn(0, modes.lastIndex))
                    },
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
            // Sampling is independently controlled but governed by the same model/reasoning route.
            Text(
                stringResource(R.string.local_composer_temperature_title),
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.labelPrimary,
            )
            val range = profile?.let {
                LocalModelPresets.chatTemperatureRangeFor(it.model, it.baseUrl)
            }
            // Mirror LocalModelGateway: explicit LOW/DEEP/MAX never transmit temperature;
            // DeepSeek also needs thinking explicitly disabled for sampling to take effect.
            val thinkingBlocksSampling =
                effectiveMode in setOf(
                    LocalReasoningUiMode.LOW,
                    LocalReasoningUiMode.DEEP,
                    LocalReasoningUiMode.MAX,
                ) || (range?.requiresDisabledThinking == true &&
                    effectiveMode != LocalReasoningUiMode.FAST)
            if (range == null) {
                Text(
                    stringResource(R.string.local_composer_temperature_unavailable),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            } else {
                val selectedLevel = temperatureLevel.coerceIn(0, 4)
                var temperatureSelection by remember(
                    profile?.id, usageMode, selectedLevel, temperaturePosition, temperatureEnabled,
                ) {
                    mutableFloatStateOf(selectedLevel.toFloat())
                }
                var temperatureInteracted by remember(
                    profile?.id, usageMode, selectedLevel, temperaturePosition, temperatureEnabled,
                ) { androidx.compose.runtime.mutableStateOf(false) }
                val temperatureTick = rememberHapticTickFeedback(selectedLevel)
                val temperatureText = stringResource(R.string.local_composer_temperature_title)
                // A persona may have a fine 0..100 value between composer detents.
                // Show that actual value until the user touches this five-stop slider.
                val visiblePosition = if (
                    usageMode == LocalUsageMode.CHAT && !temperatureInteracted && temperaturePosition != null
                ) temperaturePosition.coerceIn(0, 100)
                else temperatureSelection.roundToInt().coerceIn(0, 4) * 25
                val temperatureSample = range.at(visiblePosition)
                val temperatureState = if (range.omitAtChatDefault && visiblePosition == 50) {
                    stringResource(R.string.local_composer_temperature_provider_default, temperatureSample)
                } else {
                    stringResource(R.string.local_composer_temperature_value, temperatureSample)
                }
                val allowTemperature = temperatureEnabled && !thinkingBlocksSampling
                Text(
                    temperatureState,
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelSecondary,
                )
                Slider(
                    modifier = Modifier
                        .testTag("local-composer-temperature-slider")
                        .semantics {
                            contentDescription = temperatureText
                            stateDescription = temperatureState
                        },
                    value = temperatureSelection,
                    enabled = allowTemperature,
                    onValueChange = { next ->
                        temperatureSelection = next
                        temperatureInteracted = true
                        temperatureTick(next.roundToInt().coerceIn(0, 4))
                    },
                    onValueChangeFinished = {
                        onTemperatureLevelChange(temperatureSelection.roundToInt().coerceIn(0, 4))
                    },
                    valueRange = 0f..4f,
                    steps = 3,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(
                        if (usageMode == LocalUsageMode.CHAT)
                            R.string.local_composer_temperature_chat_low
                        else R.string.local_composer_temperature_work_low,
                    ), style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text(stringResource(R.string.local_composer_temperature_natural),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                    Text(stringResource(
                        if (usageMode == LocalUsageMode.CHAT)
                            R.string.local_composer_temperature_chat_high
                        else R.string.local_composer_temperature_work_high,
                    ), style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
                if (temperatureSaveFailed && usageMode == LocalUsageMode.CHAT) {
                    Text(stringResource(R.string.local_composer_temperature_save_failed),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
                if (thinkingBlocksSampling) {
                    Text(stringResource(R.string.local_composer_temperature_thinking),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                } else if (!temperatureEnabled && usageMode == LocalUsageMode.CHAT) {
                    val hint = when {
                        temperatureGroupChat -> R.string.local_composer_temperature_group_hint
                        temperatureSaving -> R.string.local_composer_temperature_saving
                        else -> R.string.local_composer_temperature_busy
                    }
                    Text(stringResource(hint),
                        style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
            }
        }
    }
}

/** Work-only plan action; the running session owns the state. */
@Composable
internal fun LocalComposerPlanAction(
    selected: Boolean,
    enabled: Boolean,
    waitingForApproval: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DsTheme.colors
    val pulse = rememberHapticPulse()
    val label = stringResource(
        when {
            waitingForApproval -> R.string.local_composer_plan_waiting_approval
            selected -> R.string.local_composer_plan_start
            else -> R.string.local_composer_plan_off
        },
    )
    DsComposerAction(
        icon = FeatherIcons.List,
        modifier = Modifier
            .testTag("local-composer-plan-toggle")
            .semantics { stateDescription = label },
        contentDescription = stringResource(R.string.local_composer_plan_title) + " · " + label,
        onClick = { pulse(); onToggle() },
        enabled = enabled && !waitingForApproval,
        tint = if (selected) colors.accent else colors.labelSecondary,
        containerColor = if (selected) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
    )
}
