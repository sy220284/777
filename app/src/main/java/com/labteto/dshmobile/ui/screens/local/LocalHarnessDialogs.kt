package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric




import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.chat.PersonaGalleryEntry
import com.labteto.dshmobile.local.chat.PersonaProfile
import com.labteto.dshmobile.local.chat.CharacterFact
import com.labteto.dshmobile.local.chat.CharacterFactProvenance
import com.labteto.dshmobile.local.chat.CharacterFactCategories
import com.labteto.dshmobile.local.presentation.PersonaPresetCatalog
import com.labteto.dshmobile.local.chat.PersonaLoreEntry
import com.labteto.dshmobile.local.interaction.LocalApproval
import com.labteto.dshmobile.local.interaction.LocalApprovalImpact
import com.labteto.dshmobile.local.session.LocalConversationMode
import com.labteto.dshmobile.ui.agentApprovalPurposeRes
import com.labteto.dshmobile.ui.agentOperationLabelRes
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.shenyan.designsystem.components.SySheetChoiceRow
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.labteto.dshmobile.ui.theme.wallpaperSurface
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun NewSessionModeDialog(
    usageMode: LocalUsageMode,
    onDismiss: () -> Unit,
    onSelect: (LocalConversationMode) -> Unit,
) {
    val chatMode = usageMode == LocalUsageMode.CHAT
    SyResponsiveSheet(
        title = stringResource(R.string.local_new_session_dialog_title),
        onDismiss = onDismiss,
        seamlessBackground = true,
    ) {
        SySheetChoiceRow(
            modifier = Modifier.heightIn(min = 72.dp),
            title = stringResource(
                if (chatMode) R.string.local_new_session_chat_continue
                else R.string.local_new_session_continue,
            ),
            onClick = { onSelect(LocalConversationMode.CONTINUATION) },
        )
        SySheetChoiceRow(
            modifier = Modifier.heightIn(min = 72.dp),
            title = stringResource(
                if (chatMode) R.string.local_new_session_chat_new
                else R.string.local_new_session_independent,
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
    SyResponsiveSheet(
        title = stringResource(R.string.local_group_new_session_title),
        onDismiss = onDismiss,
        seamlessBackground = true,
    ) {
        SySheetChoiceRow(
            title = stringResource(R.string.local_group_new_group),
            onClick = onNewGroup,
        )
        SySheetChoiceRow(
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val isUnbound = currentPersona.id == PersonaProfile.DEFAULT_PERSONA_ID
    SyResponsiveSheet(
        title = stringResource(R.string.local_persona_picker_title),
        subtitle = stringResource(R.string.local_persona_picker_intro),
        onDismiss = onDismiss,
        seamlessBackground = true,
        trailing = {
            SyButton(
                text = stringResource(
                    if (isUnbound) R.string.local_persona_picker_new
                    else R.string.local_persona_picker_edit_current,
                ),
                onClick = onEditCurrent,
                size = SyButtonSize.Small,
                variant = SyButtonVariant.Ghost,
            )
        },
    ) {
        if (!canSwitchPersona) {
            Text(
                stringResource(R.string.local_persona_picker_switch_requires_new_chat),
                style = DsType.small13.withReadingWeight(),
                color = colors.textSecondary,
            )
        }
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.local_persona_picker_empty),
                style = DsType.small13.withReadingWeight(),
                color = colors.textTertiary,
            )
        } else {
            Text(
                "${stringResource(R.string.local_persona_picker_saved)} · ${entries.size}",
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
            // Keep the sheet header visible; only the portrait grid scrolls.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = SyMetric.gapMd
                val columns = (maxWidth / 108.dp).toInt().coerceIn(3, 6)
                val tileEdge = (maxWidth - gap * (columns - 1)) / columns
                val rows = (entries.size + columns - 1) / columns
                val contentHeight = tileEdge * rows + gap * (rows - 1)
                val maxGridHeight = minOf(432.dp, LocalConfiguration.current.screenHeightDp.dp * 0.53f)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(minOf(contentHeight, maxGridHeight))
                        .testTag("personaPickerAvatarGrid"),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    items(entries, key = PersonaGalleryEntry::id) { entry ->
                        PersonaPickerAvatarTile(
                            entry = entry,
                            selected = currentGalleryId == entry.id,
                            enabled = canSwitchPersona,
                            onClick = {
                                if (onSelect(entry.id)) onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaPickerAvatarTile(
    entry: PersonaGalleryEntry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val portrait by produceState<ImageBitmap?>(null, entry.portraitPath) {
        value = withContext(Dispatchers.IO) {
            decodePersonaPortraitBitmap(entry.portraitPath, maxEdgePx = 256)
        }
    }
    val reduced = com.shenyan.designsystem.foundation.LocalSyReduceMotion.current
    val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
    val motion = com.shenyan.designsystem.foundation.SyMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduced) motion.pressedScale else 1f,
        animationSpec = tween(motion.duration(motion.pressMs, reduced, tempo), easing = motion.ease),
        label = "personaAvatarPress",
    )
    val background by animateColorAsState(
        targetValue = if (selected) colors.selected
        else colors.wallpaperSurface(WallpaperSurfaceLevel.CARD),
        animationSpec = tween(motion.duration(motion.interactionColorMs, reduced, tempo), easing = motion.ease),
        label = "personaAvatarBackground",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.border,
        animationSpec = tween(motion.duration(motion.interactionColorMs, reduced, tempo), easing = motion.ease),
        label = "personaAvatarOutline",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = entry.persona.name
                this.selected = selected
            }
            .testTag("personaPickerAvatar_${entry.id}"),
        shape = DsShapes.block,
        color = background,
        border = BorderStroke(if (selected) 2.dp else 1.dp, outline),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Crossfade(
                targetState = portrait,
                animationSpec = tween(durationMillis = motion.duration(motion.fadeMs, reduced, tempo)),
                label = "personaAvatarReveal",
                modifier = Modifier.fillMaxSize(),
            ) { image ->
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = FeatherIcons.User,
                            contentDescription = null,
                            tint = colors.lilac,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            if (selected) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp),
                    shape = CircleShape,
                    color = colors.primary,
                    border = BorderStroke(2.dp, colors.materialL3),
                ) {
                    Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.padding(4.dp),
                    )
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
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val impactLabel = stringResource(
        when (approval.impact) {
            LocalApprovalImpact.LOW -> R.string.local_approval_impact_low
            LocalApprovalImpact.MEDIUM -> R.string.local_approval_impact_medium
            LocalApprovalImpact.HIGH -> R.string.local_approval_impact_high
            LocalApprovalImpact.CRITICAL -> R.string.local_approval_impact_critical
        },
    )
    SyResponsiveSheet(title = stringResource(R.string.local_approval_title), onDismiss = onDeny,
        scrollable = true, seamlessBackground = false) {
        Text(
            stringResource(agentOperationLabelRes(approval.toolName)),
            style = DsType.base16Strong.withReadingWeight(),
            color = colors.text,
        )
        Text(
            stringResource(R.string.local_approval_impact, impactLabel),
            style = DsType.caption11Strong.withReadingWeight(),
            color = when (approval.impact) {
                LocalApprovalImpact.LOW -> colors.textTertiary
                LocalApprovalImpact.MEDIUM -> colors.warningInk
                LocalApprovalImpact.HIGH,
                LocalApprovalImpact.CRITICAL -> colors.dangerInk
            },
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(SyMetric.gapLg),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                Text(
                    stringResource(R.string.local_approval_purpose_title),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.text,
                )
                Text(
                    stringResource(agentApprovalPurposeRes(approval.toolName)),
                    style = DsType.small13.withReadingWeight(),
                    color = colors.textSecondary,
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
            color = colors.textTertiary,
        )

        SyButton(
            stringResource(R.string.local_approval_once),
            onApprove,
            modifier = Modifier.fillMaxWidth(),
        )
        SyButton(
            stringResource(R.string.local_approval_reject),
            onDeny,
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Outline,
        )
        if (!safeAutoApprovalEnabled) {
            SyButton(
                stringResource(R.string.local_approval_enable_safe),
                onAutoApprove,
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Ghost,
            )
        } else {
            Text(
                stringResource(R.string.local_approval_safe_enabled),
                style = DsType.caption11.withReadingWeight(),
                color = colors.textTertiary,
            )
        }
        if (approval.canApproveDeviceTurn) {
            SyButton(
                stringResource(R.string.local_approval_device_turn),
                onApproveDeviceTurn,
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Ghost,
            )
        }
    }
}

@Composable
internal fun QuestionDialog(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Boolean,
    onDismiss: () -> Unit,
    requireConfirmation: Boolean = false,
    questionId: String = question,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var answer by rememberSaveable(questionId) { mutableStateOf("") }
    var selected by rememberSaveable(questionId) { mutableStateOf("") }
    var failed by rememberSaveable(questionId) { mutableStateOf(false) }
    fun submit(value: String) {
        failed = !onAnswer(value)
    }
    SyResponsiveSheet(
        title = stringResource(R.string.local_question_title),
        onDismiss = onDismiss,
        scrollable = true,
        seamlessBackground = !requireConfirmation,
    ) {
        Text(question, style = DsType.base16Strong.withReadingWeight(), color = colors.text)
        options.forEach { option ->
            SySheetChoiceRow(
                title = option,
                selected = requireConfirmation && selected == option,
                onClick = {
                    if (requireConfirmation) {
                        selected = option
                        answer = ""
                        failed = false
                    } else {
                        submit(option)
                    }
                },
            )
        }
        SyTextField(
            value = answer,
            onValueChange = {
                answer = it
                selected = ""
                failed = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.local_question_custom_answer)) },
            maxLines = 4,
        )
        if (failed) {
            Text(stringResource(R.string.local_question_submit_failed), color = colors.dangerInk)
        }
        SyButton(
            text = stringResource(R.string.local_question_submit),
            onClick = { submit(answer.ifBlank { if (requireConfirmation) selected else "" }) },
            modifier = Modifier.fillMaxWidth(),
            enabled = answer.isNotBlank() || (requireConfirmation && selected.isNotBlank()),
        )
        SyButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Ghost,
        )
    }
}

@Composable
internal fun ChatPersonaDialog(
    profile: PersonaProfile,
    onSave: suspend (PersonaProfile) -> Result<Unit>,
    onAutoFill: suspend (PersonaProfile, String) -> Result<PersonaProfile>,
    onDismiss: () -> Unit,
    creatingNew: Boolean = false,
) {
    var runtimeProfile by remember(profile.id, profile.updatedAt) { mutableStateOf(profile) }
    var name by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.name) }
    var coreIdentity by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.coreIdentity) }
    var factDrafts by remember(profile.id, profile.updatedAt) {
        mutableStateOf(withBlankPersonaFacts(profile.facts.filter { it.category !in setOf(
            "definingChoices", "emotionalImprints", "lifeGravity",
            "unfinishedBusiness", "limitsAndCosts", "sensorySignature",
        ) }))
    }
    var customCategory by rememberSaveable(profile.id) { mutableStateOf("") }
    var franchise by rememberSaveable(profile.id, profile.updatedAt) { mutableStateOf(profile.franchise) }
    var loreEntries by remember(profile.id, profile.updatedAt) { mutableStateOf(profile.loreEntries) }
    val bundledPreset = remember(profile.presetId) { PersonaPresetCatalog.find(profile.presetId) }
    var aiDescription by rememberSaveable(profile.id) { mutableStateOf("") }
    var visibleFields by remember(profile.id, profile.updatedAt) {
        mutableStateOf(factDrafts.map(CharacterFact::category).toSet())
    }
    var addFieldsOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    var customFieldOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    var aiOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    var loreOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    var aiGenerating by remember(profile.id) { mutableStateOf(false) }
    var saving by remember(profile.id) { mutableStateOf(false) }
    var saveError by remember(profile.id) { mutableStateOf<String?>(null) }
    var aiSucceeded by remember(profile.id) { mutableStateOf(false) }
    var aiError by remember(profile.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(profile.id) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun currentDraft(): PersonaProfile = runtimeProfile.copy(
        name = name,
        coreIdentity = coreIdentity,
        facts = factDrafts.filter { it.content.isNotBlank() },
        franchise = franchise,
        loreEntries = loreEntries.filter { it.content.isNotBlank() },
    )
    fun requestDismiss() {
        if (currentDraft() != profile) confirmDiscard = true else onDismiss()
    }
    val saveFailedText = stringResource(R.string.persona_gallery_save_failed)

    fun addFactField(category: String) {
        if (factDrafts.none { it.category == category }) {
            factDrafts = factDrafts + CharacterFact(
                id = UUID.randomUUID().toString(),
                category = category,
                content = "",
                provenance = CharacterFactProvenance.USER_CREATED,
            )
        }
        visibleFields = visibleFields + category
        addFieldsOpen = false
    }

    fun applyGenerated(generated: PersonaProfile) {
        runtimeProfile = generated
        name = generated.name
        coreIdentity = generated.coreIdentity
        factDrafts = withBlankPersonaFacts(generated.facts.filter { fact ->
            fact.category !in setOf(
                "definingChoices", "emotionalImprints", "lifeGravity",
                "unfinishedBusiness", "limitsAndCosts", "sensorySignature",
            )
        })
        visibleFields = factDrafts.map(CharacterFact::category).toSet()
        franchise = generated.franchise
        loreEntries = generated.loreEntries
    }

    SyResponsiveSheet(
        title = stringResource(R.string.local_persona_title),
        onDismiss = ::requestDismiss,
        scrollable = true,
        seamlessBackground = true,
        dismissEnabled = !aiGenerating && !saving,
        footer = {
            saveError?.let {
                Text(it, style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk)
            }
        SyButton(
            text = stringResource(R.string.local_persona_save),
            onClick = {
                saving = true
                saveError = null
                coroutineScope.launch {
                    val result = runCatching {
                        onSave(currentDraft())
                    }.getOrElse { Result.failure(it) }
                    result.onSuccess { onDismiss() }
                        .onFailure { saveError = it.message ?: saveFailedText }
                    saving = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = name.isNotBlank() && !aiGenerating && !saving &&
                (!creatingNew || coreIdentity.isNotBlank() || franchise.isNotBlank() ||
                    factDrafts.any { it.content.isNotBlank() } || loreEntries.any { it.content.isNotBlank() }),
        )
        },
    ) {
        // 从人物内核、主观认知、日常表达，延伸到经历、关系和所属世界。
        val availableFields = listOf(
            "personality" to R.string.persona_v4_personality,
            "valuesAndTradeoffs" to R.string.persona_v4_values_tradeoffs,
            "selfNarrative" to R.string.persona_v4_self_narrative,
            "subjectiveBeliefs" to R.string.persona_v4_subjective_beliefs,
            "identityGap" to R.string.persona_v4_identity_gap,
            "preferencesAndHabits" to R.string.persona_v4_preferences_habits,
            "voiceStyle" to R.string.persona_v4_voice_style,
            "biography" to R.string.persona_v4_biography,
            "relationships" to R.string.persona_v4_relationships,
            "customFacts" to R.string.persona_v4_custom_facts,
        )
        SyButton(
            text = stringResource(if (aiOpen) R.string.persona_v4_hide_ai
                else R.string.persona_v4_expand_ai),
            onClick = { aiOpen = !aiOpen },
            modifier = Modifier.fillMaxWidth(),
            variant = SyButtonVariant.Ghost,
        )
        if (aiOpen) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                SyTextField(
                    value = aiDescription,
                    onValueChange = { aiDescription = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_persona_ai_description)) },
                    placeholder = { Text(stringResource(R.string.local_persona_ai_example_v3)) },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !aiGenerating,
                )
                SyButton(
                    text = stringResource(if (aiGenerating) R.string.local_persona_ai_generating
                        else if (creatingNew) R.string.local_persona_ai_generate_new
                        else R.string.local_persona_ai_generate),
                    onClick = {
                        if (!aiGenerating) {
                            val submittedDraft = currentDraft()
                            aiGenerating = true
                            aiSucceeded = false
                            aiError = null
                            coroutineScope.launch {
                                try {
                                    runCatching { onAutoFill(submittedDraft, aiDescription) }
                                        .getOrElse { Result.failure(it) }
                                        .onSuccess {
                                            applyGenerated(it)
                                            aiSucceeded = true
                                        }
                                        .onFailure { aiError = it.message ?: "persona_autofill_failed" }
                                } finally {
                                    aiGenerating = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !aiGenerating,
                )
                if (aiSucceeded) {
                    Text(
                        stringResource(if (creatingNew) R.string.local_persona_ai_new_draft
                            else R.string.local_persona_ai_synced),
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    )
                }
                aiError?.let { error ->
                    val message = when (error) {
                        "persona_autofill_busy" -> stringResource(R.string.local_persona_ai_busy)
                        "persona_autofill_unconfigured" -> stringResource(R.string.local_persona_ai_unconfigured)
                        "persona_autofill_stale" -> stringResource(R.string.local_persona_ai_stale)
                        "persona_autofill_failed" -> stringResource(R.string.local_persona_ai_failed)
                        else -> error
                    }
                    Text(
                        message,
                        style = DsType.caption11.withReadingWeight(),
                        color = com.shenyan.designsystem.foundation.LocalSyColors.current.dangerInk,
                    )
                }
            }
        }
        PersonaFormSection(stringResource(R.string.persona_v4_section_core_simple)) {
            PersonaTextField(stringResource(R.string.local_persona_name), name, { name = it }, singleLine = true)
            PersonaTextField(stringResource(R.string.persona_v4_core_identity), coreIdentity,
                { coreIdentity = it }, hint = stringResource(R.string.persona_v4_hint_identity))
        }
        availableFields.filterNot { it.first == "customFacts" }.forEach { (category, label) ->
            PersonaFactCategoryEditor(
                category = category,
                title = stringResource(label),
                hint = when (category) {
                    "personality" -> stringResource(R.string.persona_v4_hint_personality)
                    "valuesAndTradeoffs" -> stringResource(R.string.persona_v4_hint_values)
                    "selfNarrative" -> stringResource(R.string.persona_v4_hint_self)
                    "subjectiveBeliefs" -> stringResource(R.string.persona_v4_hint_beliefs)
                    "identityGap" -> stringResource(R.string.persona_v4_hint_gap)
                    "preferencesAndHabits" -> stringResource(R.string.persona_v4_hint_habits)
                    "voiceStyle" -> stringResource(R.string.persona_v4_hint_voice)
                    "biography" -> stringResource(R.string.persona_v4_hint_biography)
                    else -> stringResource(R.string.persona_v4_hint_relationships)
                },
                facts = factDrafts,
                onChange = { factDrafts = it },
            )
        }
        // 社会关系之后才补充所属世界，自定义设定始终放在最后。
        PersonaTextField(
            stringResource(R.string.persona_v4_franchise_simple),
            franchise,
            { franchise = it },
            singleLine = true,
            hint = stringResource(R.string.persona_v4_hint_franchise),
        )
        PersonaFactCategoryEditor(
            category = "customFacts",
            title = stringResource(R.string.persona_v4_custom_facts),
            hint = stringResource(R.string.persona_v4_hint_custom),
            facts = factDrafts,
            onChange = { factDrafts = it },
        )
        factDrafts.map(CharacterFact::category).distinct()
            .filterNot { category -> availableFields.any { it.first == category } }
            .filterNot { it == "franchise" }
            .forEach { category ->
                PersonaFactCategoryEditor(
                    category = category,
                    title = if (category == "identity") stringResource(R.string.persona_v4_extra_identity) else category,
                    facts = factDrafts,
                    onChange = { factDrafts = it },
                )
            }
        // A newly added custom category has no saved fact until the user types content.
        visibleFields.filterNot { category ->
            category == "franchise" || availableFields.any { it.first == category } ||
                factDrafts.any { it.category == category }
        }.forEach { category ->
            PersonaFactCategoryEditor(
                category = category,
                title = if (category == "identity") stringResource(R.string.persona_v4_extra_identity) else category,
                facts = factDrafts,
                onChange = { factDrafts = it },
            )
        }
        if (loreOpen) {
            PersonaFormSection(stringResource(R.string.persona_v4_section_lore)) {
                PersonaWorldBookEditor(entries = loreEntries, onChange = { loreEntries = it })
            }
        }
        SyButton(
            text = stringResource(if (addFieldsOpen) R.string.persona_v4_hide_field_picker
                else R.string.persona_v4_add_field_picker),
            onClick = { addFieldsOpen = !addFieldsOpen },
            modifier = Modifier.fillMaxWidth().testTag("personaAddFieldPicker"),
            variant = SyButtonVariant.Ghost,
        )
        if (addFieldsOpen) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
            ) {
                SySheetChoiceRow(
                    title = stringResource(R.string.persona_v4_section_lore),
                    onClick = { loreOpen = true; addFieldsOpen = false },
                )
                SySheetChoiceRow(
                    title = stringResource(R.string.persona_v4_custom_field_name),
                    onClick = { customFieldOpen = !customFieldOpen },
                )
                if (customFieldOpen) {
                    PersonaTextField(
                        stringResource(R.string.persona_v4_custom_field_name),
                        customCategory,
                        { customCategory = it },
                        singleLine = true,
                    )
                    SyButton(
                        text = stringResource(R.string.persona_v4_add_custom),
                        onClick = {
                            val category = customCategory.trim()
                            if (category.isNotEmpty()) {
                                if (category == "franchise") {
                                    visibleFields = visibleFields + category
                                    addFieldsOpen = false
                                } else {
                                    addFactField(category)
                                }
                                customCategory = ""
                                customFieldOpen = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = SyButtonVariant.Ghost,
                        enabled = customCategory.isNotBlank(),
                    )
                }
                bundledPreset?.let { preset ->
                    SyButton(
                        text = stringResource(R.string.persona_v4_load_preset),
                        onClick = {
                            coreIdentity = preset.persona.coreIdentity
                            factDrafts = preset.persona.facts
                            visibleFields = visibleFields + preset.persona.facts.map(CharacterFact::category)
                            addFieldsOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = SyButtonVariant.Ghost,
                    )
                    SyButton(
                        text = stringResource(R.string.persona_v4_fill_lore),
                        onClick = {
                            val ids = loreEntries.map(PersonaLoreEntry::id).toSet()
                            loreEntries += preset.persona.loreEntries.filterNot { it.id in ids }
                            loreOpen = true
                            addFieldsOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        variant = SyButtonVariant.Ghost,
                    )
                }
            }
        }
        if (loreEntries.isNotEmpty() && !loreOpen) {
            SyButton(
                text = stringResource(R.string.persona_v4_edit_lore, loreEntries.size),
                onClick = { loreOpen = true },
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Ghost,
            )
        }

    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.persona_v4_discard_title)) },
            text = { Text(stringResource(R.string.persona_v4_discard_body)) },
            confirmButton = {
                SyButton(
                    text = stringResource(R.string.persona_v4_discard_confirm),
                    onClick = { confirmDiscard = false; onDismiss() },
                    variant = SyButtonVariant.Ghost,
                )
            },
            dismissButton = {
                SyButton(
                    text = stringResource(R.string.persona_v4_discard_cancel),
                    onClick = { confirmDiscard = false },
                    variant = SyButtonVariant.Ghost,
                )
            },
        )
    }
}

/** Stable empty records make each field immediately writable without a focus-changing node swap. */
private fun withBlankPersonaFacts(input: List<CharacterFact>): List<CharacterFact> {
    val categories = (CharacterFactCategories.canonical + input.map(CharacterFact::category)).distinct()
    return input + categories.filterNot { category ->
        input.any { it.category == category && it.content.isBlank() }
    }.map { category ->
        CharacterFact(id = UUID.randomUUID().toString(), category = category, content = "",
            provenance = CharacterFactProvenance.USER_CREATED)
    }
}

@Composable
private fun PersonaFactCategoryEditor(
    category: String,
    title: String,
    facts: List<CharacterFact>,
    onChange: (List<CharacterFact>) -> Unit,
    hint: String = "",
) {
    val entries = facts.filter { it.category == category }
    // Render the entire field in place, including one trailing blank box.
    // No redundant "add fact" controls, and each existing fact retains its own metadata.
    entries.forEachIndexed { index, entry ->
        val label = when {
            entry.content.isBlank() && index > 0 -> stringResource(R.string.persona_v4_free_append)
            index == 0 -> title
            else -> stringResource(R.string.persona_v4_fact_number, title, index + 1)
        }
        PersonaTextField(label, entry.content, { content ->
            val updated = facts.map { fact ->
                if (fact.id == entry.id) fact.copy(
                    content = content,
                    provenance = if (content == entry.content) entry.provenance
                        else CharacterFactProvenance.USER_CREATED,
                    sourceReference = entry.sourceReference,
                ) else fact
            }
            if (entry.content.isBlank() && content.isNotBlank()) {
                onChange(withBlankPersonaFacts(updated))
            } else onChange(updated)
        }, hint = if (entry.content.isBlank() && index > 0)
            stringResource(R.string.persona_v4_free_append_hint) else hint)
        if (entry.content.isNotBlank()) {
            var detailsOpen by remember(entry.id) { mutableStateOf(false) }
            SyButton(
                text = stringResource(if (detailsOpen) R.string.persona_v4_fact_hide_details
                    else R.string.persona_v4_fact_details),
                onClick = { detailsOpen = !detailsOpen },
                variant = SyButtonVariant.Ghost,
            )
            if (detailsOpen) {
                PersonaTextField(
                    stringResource(R.string.persona_v4_fact_stage),
                    entry.temporalScope,
                    { value -> onChange(facts.map { fact ->
                        if (fact.id == entry.id) fact.copy(temporalScope = value.take(160))
                        else fact
                    }) },
                    singleLine = true,
                )
                PersonaTextField(
                    stringResource(R.string.persona_v4_fact_perspective),
                    entry.perspective,
                    { value -> onChange(facts.map { fact ->
                        if (fact.id == entry.id) fact.copy(perspective = value.take(120))
                        else fact
                    }) },
                    singleLine = true,
                )
                PersonaTextField(
                    stringResource(R.string.persona_v4_fact_source),
                    entry.sourceReference,
                    { value -> onChange(facts.map { fact ->
                        if (fact.id == entry.id) fact.copy(sourceReference = value.take(240))
                        else fact
                    }) },
                )
            }
        }
    }
}

@Composable
private fun PersonaFormSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SyMetric.gapMd),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
    ) {
        Text(title, style = DsType.base16Strong.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
        content()
    }
}

@Composable
private fun PersonaTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
    hint: String = "",
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    SyTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = colors.textSecondary) },
        placeholder = if (hint.isBlank()) null else ({ Text(hint, color = colors.textSecondary) }),
        singleLine = singleLine,
        minLines = if (singleLine || value.isBlank()) 1 else 2,
        maxLines = if (singleLine) 1 else 6,
    )
}
