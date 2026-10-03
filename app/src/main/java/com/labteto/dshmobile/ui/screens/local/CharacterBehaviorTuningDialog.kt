package com.labteto.dshmobile.ui.screens.local

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.chat.CharacterBehaviorTuning
import com.labteto.dshmobile.local.chat.CharacterEvolutionState
import com.labteto.dshmobile.local.chat.ChatCharacterState
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsDialog
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class TuningPreset(
    @StringRes val labelRes: Int,
    val tuning: CharacterBehaviorTuning,
)

@Composable
internal fun CharacterBehaviorTuningDialogHost(
    visible: Boolean,
    persona: PersonaProfile,
    portraitPath: String,
    state: ChatCharacterState,
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
        onSave = { tuning ->
            onConfigurePersona(persona.copy(behaviorTuning = tuning))
        },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterBehaviorTuningDialog(
    personaName: String,
    portraitPath: String,
    relationshipState: String,
    mood: String,
    evolution: CharacterEvolutionState,
    initial: CharacterBehaviorTuning,
    onSave: suspend (CharacterBehaviorTuning) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var draft by remember(initial) { mutableStateOf(initial.normalized()) }
    var advanced by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)
    val presets = remember {
        listOf(
            TuningPreset(R.string.local_character_tuning_preset_natural, CharacterBehaviorTuning()),
            TuningPreset(
                R.string.local_character_tuning_preset_slow,
                CharacterBehaviorTuning(
                    intimacy = 25,
                    persistence = 75,
                    initiative = 50,
                    openness = 25,
                    evolution = 25,
                    emotionalAfterglow = 75,
                    loreAdherence = 75,
                    relationshipPace = 25,
                ),
            ),
            TuningPreset(
                R.string.local_character_tuning_preset_intimate,
                CharacterBehaviorTuning(
                    intimacy = 75,
                    persistence = 75,
                    initiative = 75,
                    openness = 50,
                    evolution = 25,
                    emotionalAfterglow = 75,
                ),
            ),
            TuningPreset(
                R.string.local_character_tuning_preset_story,
                CharacterBehaviorTuning(
                    persistence = 75,
                    initiative = 75,
                    evolution = 75,
                    emotionalAfterglow = 75,
                    novelty = 75,
                    relationshipPace = 75,
                ),
            ),
            TuningPreset(
                R.string.local_character_tuning_preset_lore,
                CharacterBehaviorTuning(
                    evolution = 25,
                    loreAdherence = 100,
                    relationshipPace = 25,
                ),
            ),
        )
    }
    val selectedPreset = presets.firstOrNull { sameTuningShape(it.tuning, draft) }
    val advancedRotation by animateFloatAsState(
        targetValue = if (advanced) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "characterTuningAdvancedChevron",
    )
    val advancedStateDescription = stringResource(
        if (advanced) R.string.common_state_expanded else R.string.common_state_collapsed,
    )

    DsDialog(title = null, onDismiss = { if (!saving) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.characterAccentTertiary,
            border = BorderStroke(1.dp, colors.characterAccent.copy(alpha = 0.18f)),
        ) {
            Row(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                LocalPersonaHeaderAvatar(name = personaName, portraitPath = portraitPath)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        stringResource(R.string.local_character_tuning_title),
                        style = DsType.base16Strong.withReadingWeight(),
                        color = colors.labelPrimary,
                    )
                    Text(
                        listOf(relationshipState, mood)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                            .ifBlank { personaName },
                        style = DsType.small13.withReadingWeight(),
                        color = colors.labelSecondary,
                    )
                }
                Text(
                    selectedPreset?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.local_character_tuning_preset_custom),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.characterAccent,
                )
            }
        }

        Text(
            stringResource(R.string.local_character_tuning_subtitle),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )

        SectionLabel(stringResource(R.string.local_character_tuning_preset_title))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            presets.forEach { preset ->
                val selected = selectedPreset?.labelRes == preset.labelRes
                DsButton(
                    text = stringResource(preset.labelRes),
                    onClick = {
                        draft = preset.tuning.copy(
                            lockRelationshipStage = draft.lockRelationshipStage,
                            updatedAt = draft.updatedAt,
                        )
                    },
                    variant = if (selected) DsButtonVariant.Info else DsButtonVariant.Outline,
                    size = DsButtonSize.Small,
                    enabled = !saving,
                )
            }
        }

        BehaviorSlider(
            title = stringResource(R.string.local_character_tuning_intimacy),
            low = stringResource(R.string.local_character_tuning_intimacy_low),
            high = stringResource(R.string.local_character_tuning_intimacy_high),
            value = draft.intimacy,
            onValueChange = { draft = draft.copy(intimacy = it) },
            enabled = !saving,
        )
        BehaviorSlider(
            title = stringResource(R.string.local_character_tuning_persistence),
            low = stringResource(R.string.local_character_tuning_persistence_low),
            high = stringResource(R.string.local_character_tuning_persistence_high),
            value = draft.persistence,
            onValueChange = { draft = draft.copy(persistence = it) },
            enabled = !saving,
        )
        BehaviorSlider(
            title = stringResource(R.string.local_character_tuning_initiative),
            low = stringResource(R.string.local_character_tuning_initiative_low),
            high = stringResource(R.string.local_character_tuning_initiative_high),
            value = draft.initiative,
            onValueChange = { draft = draft.copy(initiative = it) },
            enabled = !saving,
        )
        BehaviorSlider(
            title = stringResource(R.string.local_character_tuning_openness),
            low = stringResource(R.string.local_character_tuning_openness_low),
            high = stringResource(R.string.local_character_tuning_openness_high),
            value = draft.openness,
            onValueChange = { draft = draft.copy(openness = it) },
            enabled = !saving,
        )
        SectionLabel(stringResource(R.string.local_character_tuning_current_state))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DsShapes.block,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            border = BorderStroke(1.dp, colors.borderL2),
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Text(
                    stringResource(R.string.local_character_tuning_current_hint),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
                ReadOnlyStateRow(
                    label = stringResource(R.string.local_character_tuning_relation),
                    value = relationshipState.ifBlank { "—" },
                )
                ReadOnlyStateRow(
                    label = stringResource(R.string.local_character_tuning_mood),
                    value = mood.ifBlank { "—" },
                )
                if (evolution.observationCount > 0 || evolution.lastMajorMilestone.isNotBlank()) {
                    EvolutionMetric(
                        label = stringResource(R.string.local_character_tuning_growth_initiative),
                        value = evolution.initiativeBaseline,
                        low = stringResource(R.string.local_character_tuning_initiative_low),
                        high = stringResource(R.string.local_character_tuning_initiative_high),
                    )
                    EvolutionMetric(
                        label = stringResource(R.string.local_character_tuning_growth_openness),
                        value = evolution.opennessBaseline,
                        low = stringResource(R.string.local_character_tuning_openness_low),
                        high = stringResource(R.string.local_character_tuning_openness_high),
                    )
                    EvolutionMetric(
                        label = stringResource(R.string.local_character_tuning_growth_security),
                        value = evolution.securityBaseline,
                        low = stringResource(R.string.local_character_tuning_security_low),
                        high = stringResource(R.string.local_character_tuning_security_high),
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DsSpacing.touchTarget)
                .semantics { stateDescription = advancedStateDescription }
                .clickable(enabled = !saving, role = Role.Button) { advanced = !advanced },
            shape = DsShapes.row,
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = DsSpacing.xsmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.local_character_tuning_advanced),
                    style = DsType.std14Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = advancedRotation },
                )
            }
        }

        AnimatedVisibility(
            visible = advanced,
            enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
            exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                BehaviorSlider(
                    title = stringResource(R.string.local_character_tuning_evolution),
                    low = stringResource(R.string.local_character_tuning_evolution_low),
                    high = stringResource(R.string.local_character_tuning_evolution_high),
                    value = draft.evolution,
                    onValueChange = { draft = draft.copy(evolution = it) },
                    enabled = !saving,
                )
                BehaviorSlider(
                    title = stringResource(R.string.local_character_tuning_afterglow),
                    low = stringResource(R.string.local_character_tuning_afterglow_low),
                    high = stringResource(R.string.local_character_tuning_afterglow_high),
                    value = draft.emotionalAfterglow,
                    onValueChange = { draft = draft.copy(emotionalAfterglow = it) },
                    enabled = !saving,
                )
                BehaviorSlider(
                    title = stringResource(R.string.local_character_tuning_novelty),
                    low = stringResource(R.string.local_character_tuning_novelty_low),
                    high = stringResource(R.string.local_character_tuning_novelty_high),
                    value = draft.novelty,
                    onValueChange = { draft = draft.copy(novelty = it) },
                    enabled = !saving,
                )
                BehaviorSlider(
                    title = stringResource(R.string.local_character_tuning_lore),
                    low = stringResource(R.string.local_character_tuning_lore_low),
                    high = stringResource(R.string.local_character_tuning_lore_high),
                    value = draft.loreAdherence,
                    onValueChange = { draft = draft.copy(loreAdherence = it) },
                    enabled = !saving,
                )
                BehaviorSlider(
                    title = stringResource(R.string.local_character_tuning_pace),
                    low = stringResource(R.string.local_character_tuning_pace_low),
                    high = stringResource(R.string.local_character_tuning_pace_high),
                    value = draft.relationshipPace,
                    onValueChange = { draft = draft.copy(relationshipPace = it) },
                    enabled = !saving,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DsShapes.block,
                    color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
                    border = BorderStroke(1.dp, colors.borderL2),
                ) {
                    Row(
                        modifier = Modifier.padding(DsSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.local_character_tuning_lock_stage),
                                style = DsType.small13Strong.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            Text(
                                stringResource(R.string.local_character_tuning_lock_stage_hint),
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                        Switch(
                            checked = draft.lockRelationshipStage,
                            onCheckedChange = { draft = draft.copy(lockRelationshipStage = it) },
                            enabled = !saving,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = colors.characterAccent,
                                checkedThumbColor = colors.bgLayer1,
                                uncheckedTrackColor = colors.borderL3,
                                uncheckedThumbColor = colors.labelTertiary,
                            ),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsButton(
                text = stringResource(R.string.local_character_tuning_restore),
                onClick = { draft = CharacterBehaviorTuning(updatedAt = draft.updatedAt) },
                variant = DsButtonVariant.Ghost,
                enabled = !saving,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.local_character_tuning_done),
                onClick = {
                    if (saving) return@DsButton
                    val submitted = draft.normalized().copy(updatedAt = System.currentTimeMillis())
                    saving = true
                    saveError = null
                    coroutineScope.launch {
                        onSave(submitted)
                            .onSuccess { onDismiss() }
                            .onFailure { saveError = it.message ?: saveFailedText }
                        saving = false
                    }
                },
                enabled = !saving,
            )
        }
        saveError?.let {
            Text(it, style = DsType.caption11.withReadingWeight(), color = colors.error)
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
) {
    val colors = DsTheme.colors
    val clean = value.coerceIn(0, 100)
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
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueLabel,
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.characterAccent,
            )
        }
        Slider(
            value = clean.toFloat(),
            onValueChange = { onValueChange((it / 25f).roundToInt().coerceIn(0, 4) * 25) },
            enabled = enabled,
            valueRange = 0f..100f,
            steps = 3,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = title },
            colors = SliderDefaults.colors(
                thumbColor = colors.characterAccent,
                activeTrackColor = colors.characterAccent,
                inactiveTrackColor = colors.borderL2,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(low, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
            Spacer(Modifier.weight(1f))
            Text(high, style = DsType.caption11.withReadingWeight(), color = colors.labelTertiary)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = DsType.small13Strong.withReadingWeight(),
        color = DsTheme.colors.labelTertiary,
    )
}

@Composable
private fun ReadOnlyStateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = DsType.small13.withReadingWeight(), color = DsTheme.colors.labelSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = DsType.small13Strong.withReadingWeight(), color = DsTheme.colors.labelPrimary)
    }
}

@Composable
private fun EvolutionMetric(label: String, value: Int, low: String, high: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        Text(
            label,
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = DsShapes.pillFull,
            color = colors.characterAccentTertiary,
        ) {
            Text(
                evolutionLabel(value, low, high),
                style = DsType.caption11.withReadingWeight(),
                color = colors.characterAccent,
                modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun evolutionLabel(value: Int, low: String, high: String): String = when {
    value <= 35 -> low
    value >= 65 -> high
    else -> stringResource(R.string.local_character_tuning_balanced)
}

private fun sameTuningShape(left: CharacterBehaviorTuning, right: CharacterBehaviorTuning): Boolean =
    left.copy(updatedAt = 0L) == right.copy(updatedAt = 0L)
