package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.CharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.CharacterEvolutionState
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.model.LocalModelTemperatureRange
import com.labteto.dshmobile.local.presentation.LocalModelPerformanceControls
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SySlider
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun CharacterBehaviorTuningDialogHost(
    visible: Boolean,
    persona: PersonaProfile,
    portraitPath: String,
    state: ChatCharacterState,
    temperatureTuningAvailable: Boolean = true,
    temperatureRange: LocalModelTemperatureRange? = null,
    onConfigurePersona: suspend (PersonaProfile) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    CharacterBehaviorTuningDialog(
        personaName = persona.name,
        portraitPath = portraitPath,
        relationshipState = state.relationshipState,
        mood = state.mood,
        evolution = state.evolution,
        initial = state.behaviorTuning,
        temperatureTuningAvailable = temperatureTuningAvailable,
        temperatureRange = temperatureRange,
        onSave = { tuning ->
            onConfigurePersona(persona.copy(behaviorTuning = tuning))
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun CharacterBehaviorTuningDialog(
    personaName: String,
    portraitPath: String,
    relationshipState: String,
    mood: String,
    evolution: CharacterEvolutionState,
    initial: CharacterBehaviorTuning,
    temperatureTuningAvailable: Boolean = true,
    temperatureRange: LocalModelTemperatureRange? = null,
    onSave: suspend (CharacterBehaviorTuning) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val performanceLimits by LocalModelPerformanceControls.state.collectAsStateWithLifecycle()
    val positionLimit = temperatureRange?.let(performanceLimits::temperaturePositionLimit) ?: 100
    var draft by remember(initial) { mutableStateOf(initial.normalized()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)
    SyResponsiveSheet(
        title = stringResource(R.string.local_character_tuning_title),
        onDismiss = { if (!saving) onDismiss() },
        seamlessBackground = true,
        scrollable = true,
        dismissEnabled = !saving,
        footer = {
            saveError?.let { Text(it, style = DsType.caption11.withReadingWeight(), color = colors.dangerInk) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SyButton(
                    text = stringResource(R.string.local_character_tuning_restore),
                    onClick = { draft = draft.copy(samplingTemperaturePosition = null, expressionVariation = 50) },
                    variant = SyButtonVariant.Ghost,
                    enabled = !saving,
                )
                Spacer(Modifier.width(SyMetric.gapMd))
                SyButton(
                    text = stringResource(R.string.local_character_tuning_done),
                    enabled = !saving,
                    onClick = {
                        if (saving) return@SyButton
                        val submitted = draft.normalized().copy(updatedAt = System.currentTimeMillis())
                        saving = true
                        saveError = null
                        scope.launch {
                            try {
                                onSave(submitted)
                                    .onSuccess { onDismiss() }
                                    .onFailure { saveError = it.message ?: saveFailedText }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                saveError = failure.message ?: saveFailedText
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            LocalPersonaHeaderAvatar(name = personaName, portraitPath = portraitPath)
            Column {
                Text(personaName, style = DsType.base16Strong.withReadingWeight(), color = colors.text)
                Text(listOf(relationshipState, mood).filter(String::isNotBlank).joinToString(" · "),
                    style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
            }
        }
        Text(stringResource(R.string.local_character_tuning_subtitle),
            style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        if (temperatureTuningAvailable) {
            BehaviorSlider(
                title = stringResource(R.string.local_character_tuning_expression_variation),
                low = stringResource(R.string.local_character_tuning_expression_variation_low),
                high = stringResource(R.string.local_character_tuning_expression_variation_high),
                value = temperatureRange?.let(draft::temperaturePosition)
                    ?: draft.samplingTemperaturePosition ?: draft.expressionVariation,
                maxValue = positionLimit,
                displayValue = temperatureRange?.let { range ->
                    val position = draft.temperaturePosition(range).coerceAtMost(positionLimit)
                    val label = if (range.omitAtChatDefault && position == range.defaultPosition) {
                        R.string.local_character_tuning_temperature_provider_default
                    } else R.string.local_character_tuning_temperature_value
                    stringResource(label, performanceLimits.constrainTemperature(range.at(position))!!, position)
                },
                onValueChange = { draft = draft.copy(samplingTemperaturePosition = it) },
                enabled = !saving,
            )
        } else {
            Text(stringResource(R.string.local_composer_temperature_unavailable),
                style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        }
        evolution.lastMajorMilestone.takeIf(String::isNotBlank)?.let {
            Text(it, style = DsType.small13.withReadingWeight(), color = colors.textSecondary)
        }
    }
}

@Composable
private fun BehaviorSlider(
    title: String,
    low: String,
    high: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean = true,
    displayValue: String? = null,
    maxValue: Int = 100,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val maximum = maxValue.coerceIn(1, 100)
    val clean = value.coerceIn(0, maximum)
    val valueLabel = when {
        clean <= 25 -> low
        clean >= 75 -> high
        else -> stringResource(R.string.local_character_tuning_balanced)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = DsType.std14Strong.withReadingWeight(),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                displayValue ?: "$valueLabel · $clean",
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.lilac,
            )
        }
        SySlider(
            value = clean.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maximum)) },
            enabled = enabled,
            valueRange = 0f..maximum.toFloat(),
            steps = maximum - 1,
            hapticSegments = 10,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = title },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(low, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
            Spacer(Modifier.weight(1f))
            Text(high, style = DsType.caption11.withReadingWeight(), color = colors.textTertiary)
        }
    }
}
