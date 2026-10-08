package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalImpact
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.agentApprovalPurposeRes
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsBottomSheet
import com.labteto.dshmobile.ui.components.DsSheetChoiceRow
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch

@Composable
internal fun NewSessionModeDialog(
    usageMode: LocalUsageMode,
    onDismiss: () -> Unit,
    onSelect: (LocalConversationMode) -> Unit,
    projectAvailable: Boolean = true,
) {
    val colors = DsTheme.colors
    val chatMode = usageMode == LocalUsageMode.CHAT
    DsBottomSheet(
        title = stringResource(R.string.local_new_session_dialog_title),
        onDismiss = onDismiss,
    ) {
        if (!chatMode) {
            Text(
                stringResource(R.string.local_new_session_dialog_intro),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelSecondary,
            )
        }
        DsSheetChoiceRow(
            title = stringResource(
                if (chatMode) R.string.local_new_session_chat_continue
                else R.string.local_new_session_continue,
            ),
            subtitle = stringResource(
                if (chatMode) R.string.local_new_session_chat_continue_hint
                else R.string.local_new_session_continue_hint,
            ),
            onClick = { onSelect(LocalConversationMode.CONTINUATION) },
        )
        if (!chatMode && projectAvailable) {
            DsSheetChoiceRow(
                title = stringResource(R.string.local_project_start_work_session),
                subtitle = stringResource(R.string.local_project_new_session_hint),
                onClick = { onSelect(LocalConversationMode.PROJECT) },
            )
        }
        DsSheetChoiceRow(
            title = stringResource(
                if (chatMode) R.string.local_new_session_chat_new
                else R.string.local_new_session_independent,
            ),
            subtitle = stringResource(
                if (chatMode) R.string.local_new_session_chat_independent_hint
                else R.string.local_new_session_independent_hint,
            ),
            onClick = { onSelect(LocalConversationMode.INDEPENDENT) },
        )
    }
}

@Composable
internal fun GroupNewSessionDialog(
    onDismiss: () -> Unit,
    onNewGroup: () -> Unit,
    onNewSingle: () -> Unit,
) {
    val colors = DsTheme.colors
    DsBottomSheet(
        title = stringResource(R.string.local_group_new_session_title),
        onDismiss = onDismiss,
    ) {
        Text(
            stringResource(R.string.local_group_new_session_intro),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
        DsSheetChoiceRow(
            title = stringResource(R.string.local_group_new_group),
            onClick = onNewGroup,
        )
        DsSheetChoiceRow(
            title = stringResource(R.string.local_group_new_single),
            onClick = onNewSingle,
        )
    }
}

@Composable
internal fun ChatPersonaPickerDialog(
    entries: List<PersonaGalleryEntry>,
    currentPersona: PersonaProfile,
    currentGalleryId: String?,
    canSwitchPersona: Boolean,
    onSelect: (String) -> Boolean,
    onEditCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsBottomSheet(title = stringResource(R.string.local_persona_picker_title), onDismiss = onDismiss, scrollable = true) {
        Text(
            stringResource(R.string.local_persona_picker_intro),
            style = DsType.small13.withReadingWeight(),
            color = colors.labelSecondary,
        )
        Surface(
            shape = DsShapes.block,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                val currentName = if (currentPersona.id == PersonaProfile.DEFAULT_PERSONA_ID) {
                    stringResource(R.string.local_chat_no_persona)
                } else {
                    currentPersona.name
                }
                Text(
                    stringResource(R.string.local_persona_picker_current, currentName),
                    style = DsType.base16Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                currentPersona.portrait.takeIf(String::isNotBlank)?.let {
                    Text(it, style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                }
            }
        }
        DsButton(
            text = stringResource(R.string.local_persona_picker_edit_current),
            onClick = onEditCurrent,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )

        if (!canSwitchPersona) {
            Text(
                stringResource(R.string.local_persona_picker_switch_requires_new_chat),
                style = DsType.small13.withReadingWeight(),
                color = colors.labelTertiary,
            )
        } else {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.local_persona_picker_empty),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelTertiary,
                )
            } else {
                Text(
                    stringResource(R.string.local_persona_picker_saved),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.labelTertiary,
                )
                entries.forEach { entry ->
                    val selected = currentGalleryId == entry.id
                    Surface(
                        onClick = {
                            if (onSelect(entry.id)) onDismiss()
                        },
                        shape = DsShapes.block,
                        color = colors.wallpaperSurface(
                            WallpaperSurfaceLevel.CARD,
                            base = if (selected) colors.bgLayer2 else colors.bgLayer1,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(DsSpacing.medium),
                            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                        ) {
                            Text(
                                entry.persona.name,
                                style = DsType.std14.withReadingWeight(),
                                color = colors.labelPrimary,
                            )
                            val subtitle = entry.persona.portrait
                                .ifBlank { entry.persona.worldSetting }
                                .ifBlank { stringResource(R.string.local_persona_picker_saved_hint) }
                            Text(
                                subtitle,
                                style = DsType.caption11.withReadingWeight(),
                                color = colors.labelSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApprovalDialog(
    approval: LocalApproval,
    safeAutoApprovalEnabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAutoApprove: () -> Unit,
    onApproveDeviceTurn: () -> Unit,
) {
    val colors = DsTheme.colors
    val impactLabel = stringResource(
        when (approval.impact) {
            LocalApprovalImpact.LOW -> R.string.local_approval_impact_low
            LocalApprovalImpact.MEDIUM -> R.string.local_approval_impact_medium
            LocalApprovalImpact.HIGH -> R.string.local_approval_impact_high
            LocalApprovalImpact.CRITICAL -> R.string.local_approval_impact_critical
        },
    )
    DsBottomSheet(title = stringResource(R.string.local_approval_title), onDismiss = onDeny, scrollable = true) {
        Text(
            stringResource(agentOperationLabelRes(approval.toolName)),
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.labelPrimary,
        )
        Text(
            stringResource(R.string.local_approval_impact, impactLabel),
            style = DsType.caption11Strong.withReadingWeight(),
            color = when (approval.impact) {
                LocalApprovalImpact.LOW -> colors.labelTertiary
                LocalApprovalImpact.MEDIUM -> colors.warnLabel
                LocalApprovalImpact.HIGH,
                LocalApprovalImpact.CRITICAL -> colors.error
            },
        )

        Surface(
            shape = DsShapes.block,
            color = colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    stringResource(R.string.local_approval_purpose_title),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.labelPrimary,
                )
                Text(
                    stringResource(agentApprovalPurposeRes(approval.toolName)),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.labelSecondary,
                )
            }
        }

        Text(
            stringResource(
                when {
                    approval.canAutoApproveSafely -> R.string.local_approval_safe_scope
                    approval.canApproveDeviceTurn -> R.string.local_approval_device_scope
                    else -> R.string.local_approval_high_risk_scope
                },
            ),
            style = DsType.caption11.withReadingWeight(),
            color = colors.labelTertiary,
        )

        DsButton(
            stringResource(R.string.local_approval_once),
            onApprove,
            modifier = Modifier.fillMaxWidth(),
        )
        DsButton(
            stringResource(R.string.local_approval_reject),
            onDeny,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Outline,
        )
        if (!safeAutoApprovalEnabled) {
            DsButton(
                stringResource(R.string.local_approval_enable_safe),
                onAutoApprove,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        } else {
            Text(
                stringResource(R.string.local_approval_safe_enabled),
                style = DsType.caption11.withReadingWeight(),
                color = colors.labelTertiary,
            )
        }
        if (approval.canApproveDeviceTurn) {
            DsButton(
                stringResource(R.string.local_approval_device_turn),
                onApproveDeviceTurn,
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}

@Composable
internal fun QuestionDialog(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var answer by rememberSaveable(question) { mutableStateOf("") }
    DsBottomSheet(
        title = stringResource(R.string.local_question_title),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        Text(question, style = DsType.base16Strong.withReadingWeight(), color = colors.labelPrimary)
        options.forEach { option ->
            DsSheetChoiceRow(
                title = option,
                onClick = { onAnswer(option) },
            )
        }
        DsTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_question_custom_answer)) },
            maxLines = 4,
        )
        DsButton(
            text = stringResource(R.string.local_question_submit),
            onClick = { onAnswer(answer) },
            modifier = Modifier.fillMaxWidth(),
            enabled = answer.isNotBlank(),
        )
        DsButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
    }
}


@Composable
internal fun ChatPersonaDialog(
    profile: PersonaProfile,
    onSave: suspend (PersonaProfile) -> Result<Unit>,
    onAutoFill: suspend (String) -> Result<PersonaProfile>,
    onDismiss: () -> Unit,
    creatingNew: Boolean = false,
) {
    var runtimeProfile by remember(profile.id, profile.updatedAt) { mutableStateOf(profile) }
    var name by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.name) }
    var portrait by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.portrait) }
    var lifeContext by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.lifeContext) }
    var attention by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.attentionBiases.joinToString("\n")) }
    var blindSpots by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.perceptionBlindSpots.joinToString("\n")) }
    var quirks by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.quirks.joinToString("\n")) }
    var limitations by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.limitations.joinToString("\n")) }
    var coreValues by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.coreValues.joinToString("\n")) }
    var coreTension by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.coreTension) }
    var stableTraits by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.stableTraits.joinToString("\n")) }
    var mutableTraits by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.mutableTraits.joinToString("\n")) }
    var initialUserImpression by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.initialUserImpression) }
    var voiceSamples by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.voiceSamples.joinToString("\n")) }
    var worldSetting by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.worldSetting) }
    var constraints by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.hardConstraints.joinToString("\n")) }
    var banned by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.bannedPhrases.joinToString("\n")) }
    var corrections by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.corrections.joinToString("\n")) }
    var aiDescription by rememberSaveable(profile.id) { mutableStateOf("") }
    var advancedOpen by rememberSaveable(profile.id, creatingNew) { mutableStateOf(!creatingNew) }
    var aiGenerating by remember(profile.id) { mutableStateOf(false) }
    var saving by remember(profile.id) { mutableStateOf(false) }
    var saveError by remember(profile.id) { mutableStateOf<String?>(null) }
    var aiSucceeded by remember(profile.id) { mutableStateOf(false) }
    var aiError by remember(profile.id) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)

    fun lines(value: String): List<String> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    fun applyGenerated(generated: PersonaProfile) {
        runtimeProfile = generated
        name = generated.name
        portrait = generated.portrait
        lifeContext = generated.lifeContext
        attention = generated.attentionBiases.joinToString("\n")
        blindSpots = generated.perceptionBlindSpots.joinToString("\n")
        quirks = generated.quirks.joinToString("\n")
        limitations = generated.limitations.joinToString("\n")
        coreValues = generated.coreValues.joinToString("\n")
        coreTension = generated.coreTension
        stableTraits = generated.stableTraits.joinToString("\n")
        mutableTraits = generated.mutableTraits.joinToString("\n")
        initialUserImpression = generated.initialUserImpression
        voiceSamples = generated.voiceSamples.joinToString("\n")
        worldSetting = generated.worldSetting
        constraints = generated.hardConstraints.joinToString("\n")
        banned = generated.bannedPhrases.joinToString("\n")
        corrections = generated.corrections.joinToString("\n")
    }

    DsBottomSheet(
        title = stringResource(R.string.local_persona_title),
        onDismiss = onDismiss,
        scrollable = true,
        dismissEnabled = !aiGenerating && !saving,
        footer = {
            saveError?.let {
                Text(it, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.error)
            }
        DsButton(
            text = stringResource(R.string.local_persona_save),
            onClick = {
                saving = true
                saveError = null
                coroutineScope.launch {
                    val result = runCatching {
                        onSave(
                            runtimeProfile.copy(
                                name = name,
                                portrait = portrait,
                                lifeContext = lifeContext,
                                attentionBiases = lines(attention),
                                perceptionBlindSpots = lines(blindSpots),
                                quirks = lines(quirks),
                                limitations = lines(limitations),
                                coreValues = lines(coreValues),
                                coreTension = coreTension,
                                stableTraits = lines(stableTraits),
                                mutableTraits = lines(mutableTraits),
                                initialUserImpression = initialUserImpression,
                                voiceSamples = lines(voiceSamples),
                                worldSetting = worldSetting,
                                hardConstraints = lines(constraints),
                                bannedPhrases = lines(banned),
                                corrections = lines(corrections),
                            ),
                        )
                    }.getOrElse { Result.failure(it) }
                    result.onSuccess { onDismiss() }
                        .onFailure { saveError = it.message ?: saveFailedText }
                    saving = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank() && !aiGenerating && !saving,
        )
        },
    ) {
        Text(
            stringResource(R.string.local_persona_intro_v3),
            style = DsType.small13.withReadingWeight(),
            color = DsTheme.colors.labelSecondary,
        )

        Surface(
            shape = DsShapes.block,
            color = DsTheme.colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(DsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
            ) {
                Text(
                    stringResource(R.string.local_persona_ai_title),
                    style = DsType.base16Strong.withReadingWeight(),
                    color = DsTheme.colors.labelPrimary,
                )
                Text(
                    stringResource(if (creatingNew) R.string.local_persona_ai_new_hint else R.string.local_persona_ai_hint),
                    style = DsType.small13.withReadingWeight(),
                    color = DsTheme.colors.labelSecondary,
                )
                DsTextField(
                    value = aiDescription,
                    onValueChange = { aiDescription = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_persona_ai_description)) },
                    placeholder = { Text(stringResource(R.string.local_persona_ai_example_v3)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !aiGenerating,
                )
                DsButton(
                    text = stringResource(
                        if (aiGenerating) R.string.local_persona_ai_generating
                        else if (creatingNew) R.string.local_persona_ai_generate_new
                        else R.string.local_persona_ai_generate
                    ),
                    onClick = {
                        if (aiGenerating) return@DsButton
                        aiGenerating = true
                        aiSucceeded = false
                        aiError = null
                        coroutineScope.launch {
                            try {
                                runCatching { onAutoFill(aiDescription) }
                                    .getOrElse { Result.failure(it) }
                                    .onSuccess { generated ->
                                        applyGenerated(generated)
                                        aiSucceeded = true
                                    }
                                    .onFailure { error ->
                                        aiError = error.message ?: "persona_autofill_failed"
                                    }
                            } finally {
                                aiGenerating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !aiGenerating,
                )
                if (aiGenerating) {
                    Text(
                        stringResource(R.string.local_persona_ai_working_hint),
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelTertiary,
                    )
                } else if (aiSucceeded) {
                    Text(
                        stringResource(if (creatingNew) R.string.local_persona_ai_new_draft else R.string.local_persona_ai_synced),
                        style = DsType.caption11.withReadingWeight(),
                        color = DsTheme.colors.labelSecondary,
                    )
                }
                aiError?.takeIf(String::isNotBlank)?.let { error ->
                    val message = when (error) {
                        "persona_autofill_busy" -> stringResource(R.string.local_persona_ai_busy)
                        "persona_autofill_unconfigured" -> stringResource(R.string.local_persona_ai_unconfigured)
                        "persona_autofill_stale" -> stringResource(R.string.local_persona_ai_stale)
                        "persona_autofill_failed" -> stringResource(R.string.local_persona_ai_failed)
                        else -> error
                    }
                    Text(message, style = DsType.caption11.withReadingWeight(), color = DsTheme.colors.error)
                }
            }
        }

        PersonaFormSection(stringResource(R.string.persona_form_basics)) {
            PersonaTextField(stringResource(R.string.local_persona_name), name, { name = it }, singleLine = true)
            PersonaTextField(stringResource(R.string.local_persona_portrait), portrait, { portrait = it })
        }
        DsButton(
            text = stringResource(
                if (advancedOpen) R.string.local_persona_hide_more else R.string.local_persona_show_more
            ),
            onClick = { advancedOpen = !advancedOpen },
            modifier = Modifier.fillMaxWidth(),
            variant = DsButtonVariant.Ghost,
        )
        if (advancedOpen) {
        PersonaFormSection(stringResource(R.string.persona_form_life)) {
            PersonaTextField(stringResource(R.string.local_persona_life_context), lifeContext, { lifeContext = it })
            PersonaTextField(stringResource(R.string.local_persona_attention), attention, { attention = it })
            PersonaTextField(stringResource(R.string.local_persona_blind_spots), blindSpots, { blindSpots = it })
            PersonaTextField(stringResource(R.string.local_persona_quirks), quirks, { quirks = it })
            PersonaTextField(stringResource(R.string.local_persona_limitations), limitations, { limitations = it })
        }
        PersonaFormSection(stringResource(R.string.persona_form_character)) {
            PersonaTextField(stringResource(R.string.local_persona_core_values), coreValues, { coreValues = it })
            PersonaTextField(stringResource(R.string.local_persona_core_tension), coreTension, { coreTension = it })
            PersonaTextField(stringResource(R.string.local_persona_stable_traits), stableTraits, { stableTraits = it })
            PersonaTextField(stringResource(R.string.local_persona_mutable_traits), mutableTraits, { mutableTraits = it })
        }
        PersonaFormSection(stringResource(R.string.persona_form_expression)) {
            PersonaTextField(stringResource(R.string.local_persona_user_impression), initialUserImpression, { initialUserImpression = it })
            PersonaTextField(stringResource(R.string.local_persona_voice_samples), voiceSamples, { voiceSamples = it })
        }
        PersonaFormSection(stringResource(R.string.persona_form_boundaries)) {
            PersonaTextField(stringResource(R.string.local_persona_world_setting), worldSetting, { worldSetting = it })
            PersonaTextField(stringResource(R.string.local_persona_constraints), constraints, { constraints = it })
            PersonaTextField(stringResource(R.string.local_persona_banned), banned, { banned = it })
            PersonaTextField(stringResource(R.string.local_persona_corrections), corrections, { corrections = it })
        }
        }
    }
}

@Composable
private fun PersonaFormSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(title, style = DsType.base16Strong.withReadingWeight(), color = DsTheme.colors.labelPrimary)
        content()
    }
}

@Composable
private fun PersonaTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
) {
    DsTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 6,
    )
}
