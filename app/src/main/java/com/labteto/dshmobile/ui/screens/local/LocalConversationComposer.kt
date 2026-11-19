package com.labteto.dshmobile.ui.screens.local

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.chat.LocalGroupChatMember
import com.labteto.dshmobile.local.presentation.composerGroupSpeakerLabel
import com.labteto.dshmobile.local.presentation.canSelectPdfPages
import com.labteto.dshmobile.local.presentation.composerDirectedGroupInput
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalReasoningControls
import com.labteto.dshmobile.local.presentation.LocalModelPerformanceControls
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
import com.labteto.dshmobile.ui.shenyan.SyDialog
import com.labteto.dshmobile.ui.shenyan.SyComposerAction
import com.labteto.dshmobile.ui.shenyan.SyComposerField
import com.labteto.dshmobile.ui.shenyan.SyConversationComposer
import com.shenyan.designsystem.components.SyIconButton
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.AppBackgroundState
import com.labteto.dshmobile.ui.theme.BackgroundRegion
import com.labteto.dshmobile.ui.theme.WallpaperSurfaceLevel
import com.shenyan.designsystem.foundation.LocalSyColors
import com.shenyan.designsystem.foundation.SyColors
import com.labteto.dshmobile.ui.theme.LocalAppBackgroundState
import com.labteto.dshmobile.ui.theme.withReadingWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preserve the V6 L2 material instead of pre-compositing an opaque input slab.
 * Wallpaper mode reuses the existing bottom-region contrast analysis. Matte is always solid.
 */
internal fun v6ConversationComposerSurface(background: AppBackgroundState, palette: SyColors): Color =
    if (!background.hasImage || palette.materialL2.alpha == 1f) {
        palette.materialL2
    } else {
        background.wallpaperSurface(
            base = palette.materialL5,
            level = WallpaperSurfaceLevel.INPUT,
            region = BackgroundRegion.BOTTOM,
        )
    }

@Composable
internal fun LocalConversationComposer(
    state: LocalConversationSurfaceState,
    skillDisplayNames: Map<String, String> = emptyMap(),
    activeModelProfile: LocalModelProfile?,
    input: String,
    attachments: List<LocalImportedAttachment>,
    attachmentImportProgress: Pair<Int, Int>? = null,
    onInputChange: (String) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onSelectPdfPages: ((Int, List<Int>) -> Unit)? = null,
    onClearAttachments: () -> Unit,
    onOpenAttachmentPicker: () -> Unit,
    onOpenModelPicker: () -> Unit = {},
    onShowReplySuggestions: () -> Unit,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onSendTeam: (String, List<LocalImportedAttachment>) -> LocalSendResult = onSend,
    teamDispatchSelected: Boolean = false,
    onTeamDispatchChange: (Boolean) -> Unit = {},
    onStop: () -> Unit,
    reasoningMode: LocalReasoningUiMode = LocalReasoningUiMode.DEFAULT,
    onReasoningModeChange: (LocalReasoningUiMode) -> Unit = {},
    temperatureLevel: Int = 2,
    temperaturePosition: Int? = null,
    temperatureEnabled: Boolean = true,
    temperatureSaveFailed: Boolean = false,
    temperatureSaving: Boolean = false,
    temperatureGroupChat: Boolean = false,
    onTemperatureLevelChange: (Int) -> Unit = {},
    onPlanModeChange: (Boolean) -> Unit,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val skillCallTransformation = remember(colors.primary, skillDisplayNames) {
        LocalSkillCallVisualTransformation(colors.primary, skillDisplayNames)
    }
    val backgroundState = LocalAppBackgroundState.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keep real TextField focus across session projection changes. Otherwise the IME can remain
    // visible while the composer is incorrectly reset to its idle one-row state.
    var focused by remember { mutableStateOf(false) }
    var capabilityPanel by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var replySuggestionsLoading by remember(state.sessionId) { mutableStateOf(false) }
    var steerCurrentRun by remember(state.sessionId) { mutableStateOf(false) }
    var steeringChoiceDismissed by remember(state.sessionId) { mutableStateOf(false) }
    LaunchedEffect(state.running, input.isBlank()) {
        if (state.running) capabilityPanel = null
        if (!state.running || input.isBlank()) {
            // No draft or no active run: never retain the previous steering decision.
            steerCurrentRun = false
            steeringChoiceDismissed = false
        }
    }

    // Opening the input reveals the two capabilities; idle chats retain their compact row.
    val expanded = state.usageMode == LocalUsageMode.WORK || focused || input.contains('\n') || attachments.isNotEmpty() ||
        teamDispatchSelected || capabilityPanel != null
    val composerTransition = updateTransition(expanded, label = "composerExpansion")
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val availableHeight = with(density) {
        (windowHeight - WindowInsets.ime.getBottom(this) - WindowInsets.statusBars.getTop(this) -
            WindowInsets.navigationBars.getBottom(this)).toDp().value
    }
    val lineHeight = with(density) { MaterialTheme.typography.bodyMedium.lineHeight.toDp().value }
    val editorMaxLines = localComposerMaxLines(availableHeight, lineHeight)
    val modelLimits by LocalModelPerformanceControls.state.collectAsStateWithLifecycle()
    val modes = LocalReasoningControls.availableModes(activeModelProfile, state.usageMode, modelLimits.reasoningCeiling)
    val reasoningLabel = stringResource(composerReasoningLabel(reasoningMode, modes,
        LocalReasoningControls.requiresBasicReasoning(activeModelProfile, state.usageMode)))
    val modelLabel = activeModelProfile?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.model }
        ?: stringResource(R.string.models_title)
    var groupSpeakerPickerOpen by remember(state.sessionId) { mutableStateOf(false) }
    var groupAllSpeakers by remember(state.sessionId) { mutableStateOf(false) }
    var groupRequestedSpeakerId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val groupRoster = state.groupChat.members
    val groupRequestedSpeaker = groupRoster.firstOrNull { it.galleryId == groupRequestedSpeakerId }
    val groupChatReady = !state.groupChat.enabled || state.groupChat.members.size >= 2
    // A chat temperature change is committed asynchronously. Do not submit the next
    // turn with the old sampling value while the new persona setting is still saving.
    val temperatureWritePending = state.usageMode == LocalUsageMode.CHAT && temperatureSaving
    val canSend = !state.loading && attachmentImportProgress == null &&
        !temperatureWritePending &&
        groupChatReady &&
        (input.isNotBlank() || attachments.isNotEmpty())
    val moreActionsLabel = stringResource(R.string.local_composer_more_actions)
    val replySuggestionsLabel = stringResource(R.string.local_reply_suggestions_open)
    val replySuggestionsAvailable =
        state.usageMode == LocalUsageMode.CHAT &&
            !state.groupChat.enabled &&
            state.messages.any { it.role == "assistant" && it.content.isNotBlank() }

    fun openReplySuggestions() {
        if (!replySuggestionsAvailable || replySuggestionsLoading || state.running) return
        if (state.replySuggestions.any { it.text.isNotBlank() }) {
            onShowReplySuggestions()
            return
        }
        replySuggestionsLoading = true
        scope.launch {
            val generated = try {
                onGenerateReplySuggestions()
            } finally {
                replySuggestionsLoading = false
            }
            if (generated) onShowReplySuggestions()
        }
    }

    fun submit() {
        if (temperatureWritePending) return
        // Bare slash commands are aliases for the same explicit reply-suggestion action
        // as the composer button; never inject a coaching instruction into character chat.
        if (state.usageMode == LocalUsageMode.CHAT && !teamDispatchSelected &&
            attachments.isEmpty() && input.trim() in setOf("/reply", "/回复建议") &&
            replySuggestionsAvailable && !replySuggestionsLoading && !state.running
        ) {
            openReplySuggestions()
            capabilityPanel = null
            onInputChange("")
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            return
        }
        // Sending is a conversation action, even when the model is not configured.
        // Let the send coordinator reject it with visible feedback and keep the draft.
        val result = if (teamDispatchSelected) {
            // Preserve the requested team action even if the visible mode changed.
            // The Work owner reports an explicit rejection rather than sending it as Chat.
            onSendTeam(input, attachments.toList())
        } else {
            onSend(
                if (state.usageMode == LocalUsageMode.WORK && state.running && steerCurrentRun) {
                    "/steer " + input
                } else if (state.groupChat.enabled && (groupAllSpeakers || groupRequestedSpeaker != null)) {
                    composerDirectedGroupInput(input, groupRoster, groupRequestedSpeaker)
                } else input,
                attachments.toList(),
            )
        }
        if (!result.accepted) return
        groupAllSpeakers = false
        groupRequestedSpeakerId = null
        capabilityPanel = null
        steerCurrentRun = false
        onTeamDispatchChange(false)
        onInputChange("")
        onClearAttachments()
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    @Composable
    fun AttachmentControl() {
        if (state.running) return
        SyComposerAction(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_ui_add),
            contentDescription = moreActionsLabel,
            enabled = attachmentImportProgress == null,
            onClick = {
                capabilityPanel = null
                // Both collapsed and expanded composers open the same complete action sheet.
                // Release IME focus before showing it so the sheet never overlays the keyboard.
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onOpenAttachmentPicker()
            },
            tint = colors.text,
            containerColor = Color.Transparent,
        )
    }

    @Composable
    fun ModelControl() {
        Surface(
            onClick = onOpenModelPicker,
            enabled = !state.loading,
            color = colors.primary.copy(alpha = .08f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(SyMetric.radiusControl),
            modifier = Modifier.fillMaxWidth().heightIn(min = SyMetric.touch)
                .testTag("composer-model-selection"),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center) {
                Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = DsType.small13Strong.withReadingWeight(), color = colors.text)
                Text(reasoningLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
            }
        }
    }

    @Composable
    fun ReplySuggestionsControl() {
        if (!replySuggestionsAvailable) return
        SyComposerAction(
            icon = FeatherIcons.Activity,
            contentDescription = if (replySuggestionsLoading) {
                stringResource(R.string.common_loading)
            } else {
                replySuggestionsLabel
            },
            onClick = ::openReplySuggestions,
            enabled = !state.running && !replySuggestionsLoading,
            tint = if (replySuggestionsLoading) colors.textTertiary else colors.textSecondary,
            containerColor = Color.Transparent,
        )
    }

    // Keep one right-hand action across both composer layouts.
    // Running Work draft submission lives above, not beside Stop.
    @Composable
    fun PrimaryActionControl() {
        SyComposerAction(
            icon = null,
            iconPainter = painterResource(
                if (state.running) R.drawable.ic_ui_button_stop_glyph
                else R.drawable.ic_ui_button_send_glyph,
            ),
            iconSize = 32.dp,
            tint = if (state.running || canSend) colors.onPrimary else colors.text,
            contentDescription = stringResource(
                if (state.running) R.string.chat_composer_stop else R.string.chat_composer_send,
            ),
            onClick = if (state.running) onStop else ::submit,
            enabled = state.running || canSend,
            containerColor = colors.primary,
            visualSize = 32.dp,
        )
    }

    SyConversationComposer(
        surfaceColor = v6ConversationComposerSurface(backgroundState, LocalSyColors.current),
        shadowElevation = 0.dp,
        // This composer owns a targeted row reveal. Avoid a second parent size animation while
        // the IME is already animating the whole surface.
        animateSize = false,
    ) {
        if (state.usageMode == LocalUsageMode.CHAT && state.groupChat.enabled && groupChatReady) {
            val speakerChoice = when {
                groupAllSpeakers -> stringResource(R.string.local_group_speakers_all)
                groupRequestedSpeaker != null -> composerGroupSpeakerLabel(groupRequestedSpeaker, groupRoster)
                else -> stringResource(R.string.local_group_speakers_auto)
            }
            SyButton(
                text = stringResource(R.string.local_group_speakers_choice, speakerChoice),
                onClick = { groupSpeakerPickerOpen = true },
                size = SyButtonSize.Small,
                variant = SyButtonVariant.Ghost,
            )
            if (groupSpeakerPickerOpen) {
                LocalGroupSpeakerPicker(groupRoster, onDismiss = { groupSpeakerPickerOpen = false }) { all, member ->
                    groupAllSpeakers = all
                    groupRequestedSpeakerId = member?.galleryId
                    groupSpeakerPickerOpen = false
                }
            }
        }
        if (state.usageMode == LocalUsageMode.WORK && state.planMode) {
            Text(stringResource(R.string.composer_plan_active),
                modifier = Modifier.testTag("composer-plan-state"),
                style = DsType.caption11.withReadingWeight(), color = colors.primaryPressed)
        }
        if (state.usageMode == LocalUsageMode.WORK && state.running &&
            (input.isNotBlank() || attachments.isNotEmpty())) {
            LocalWorkSteeringChoice(
                selected = steerCurrentRun,
                showSteering = shouldShowWorkSteeringChoice(
                    mode = state.usageMode,
                    running = state.running,
                    teamDispatchSelected = teamDispatchSelected,
                    hasDraft = input.isNotBlank(),
                    dismissed = steeringChoiceDismissed,
                ),
                onToggle = { steerCurrentRun = !steerCurrentRun },
                onSubmit = if (canSend) ::submit else null,
                onDismiss = {
                    steerCurrentRun = false // Closing always restores safe default next-turn queueing.
                    steeringChoiceDismissed = true
                },
            )
        }
        if (attachments.size > 1 || attachmentImportProgress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (attachmentImportProgress != null) {
                        "正在添加附件 ${attachmentImportProgress.first}/${attachmentImportProgress.second}"
                    } else "已添加 ${attachments.size} 个附件",
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                )
                if (attachments.isNotEmpty() && attachmentImportProgress == null) {
                    SyButton("清空", onClearAttachments,
                        variant = SyButtonVariant.Ghost, size = SyButtonSize.Small)
                }
            }
        }
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().testTag("composer-attachments"),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
            ) {
                items(
                    items = attachments.mapIndexed { index, attachment -> index to attachment },
                    key = { (index, attachment) -> "$index:${attachment.relativePath}" },
                ) { (index, attachment) ->
                    if (attachment.mediaType.startsWith("image/")) {
                        ImportedImageAttachmentTile(
                            attachment = attachment,
                            workspacePath = state.workspacePath,
                            onRemove = { onRemoveAttachment(index) },
                        )
                    } else {
                        ImportedAttachmentRow(
                            attachment = attachment,
                            onSelectPages = onSelectPdfPages?.let { selected -> { pages -> selected(index, pages) } },
                            onRemove = { onRemoveAttachment(index) },
                        )
                    }
                }
            }
        }

        val motion = com.shenyan.designsystem.foundation.SyMotion
        val reducedMotion = com.shenyan.designsystem.foundation.LocalSyReduceMotion.current
        val tempo = com.shenyan.designsystem.foundation.LocalSyMotionTempo.current
        val composerRevealSpec = tween<IntSize>(
            motion.duration(motion.composerRevealMs, reducedMotion, tempo), easing = motion.ease
        )
        val composerFadeSpec = tween<Float>(
            motion.duration(motion.composerFadeMs, reducedMotion, tempo), easing = motion.ease
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs),
        ) {
            composerTransition.AnimatedVisibility(
                visible = { !it },
                enter = expandHorizontally(composerRevealSpec) + fadeIn(composerFadeSpec),
                exit = shrinkHorizontally(composerRevealSpec) + fadeOut(composerFadeSpec),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapXs)) {
                    AttachmentControl()
                    ReplySuggestionsControl()
                }
            }
            SyComposerField(
                value = input,
                onValueChange = onInputChange,
                visualTransformation = if (state.usageMode == LocalUsageMode.WORK) {
                    skillCallTransformation
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                placeholder = when {
                    state.usageMode == LocalUsageMode.WORK && teamDispatchSelected ->
                        stringResource(R.string.local_team_input_hint)
                    state.usageMode == LocalUsageMode.WORK ->
                        stringResource(R.string.local_work_composer_hint)
                    state.groupChat.enabled ->
                        stringResource(R.string.local_group_chat_composer_hint)
                    state.chatPersona.isUnboundChatPersona() ->
                        stringResource(R.string.local_chat_composer_no_persona_hint)
                    else ->
                        stringResource(R.string.local_chat_composer_persona_hint, state.chatPersona.name)
                },
                modifier = Modifier.weight(1f),
                maxLines = editorMaxLines,
                onFocusedChange = {
                    focused = it
                    onFocusChanged(it)
                },
            )
            if (state.usageMode == LocalUsageMode.WORK) {
                val modeLabel = stringResource(
                    if (teamDispatchSelected) R.string.local_team_selected_hint else R.string.local_team_mode_single,
                )
                val pulse = rememberHapticPulse()
                SyButton(
                    text = stringResource(R.string.local_team_mode_short),
                    icon = if (teamDispatchSelected) FeatherIcons.Check else FeatherIcons.Users,
                    onClick = { pulse(); onTeamDispatchChange(!teamDispatchSelected) },
                    enabled = !state.running,
                    variant = if (teamDispatchSelected) SyButtonVariant.Outline else SyButtonVariant.Ghost,
                    size = SyButtonSize.Small,
                    modifier = Modifier.testTag("composer-team-toggle").semantics {
                        selected = teamDispatchSelected
                        stateDescription = modeLabel
                    },
                )
            } else {
                composerTransition.AnimatedVisibility(
                    visible = { !it },
                    enter = expandHorizontally(composerRevealSpec) + fadeIn(composerFadeSpec),
                    exit = shrinkHorizontally(composerRevealSpec) + fadeOut(composerFadeSpec),
                ) {
                    PrimaryActionControl()
                }
            }
        }

        LocalConversationComposerExpandedRow(
            expansion = composerTransition,
            menuControl = { AttachmentControl() },
            modelControl = { ModelControl() },
            replySuggestionsControl = { ReplySuggestionsControl() },
            planControl = {
                if (state.usageMode == LocalUsageMode.WORK) {
                    LocalComposerPlanAction(
                        selected = state.planMode,
                        enabled = !state.running,
                        waitingForApproval = state.pendingQuestion?.let(::localPlanReviewOf) != null,
                        onToggle = { onPlanModeChange(!state.planMode) },
                    )
                }
            },
            capabilityControls = { showLabels ->
                LocalComposerCapabilityActions(
                    profile = activeModelProfile,
                    usageMode = state.usageMode,
                    reasoningMode = reasoningMode,
                    temperatureLevel = temperatureLevel,
                    temperaturePosition = temperaturePosition,
                    temperatureEnabled = temperatureEnabled,
                    temperatureSaveFailed = temperatureSaveFailed,
                    temperatureSaving = temperatureSaving,
                    temperatureGroupChat = temperatureGroupChat,
                    onTemperatureLevelChange = {
                        if (!state.running) onTemperatureLevelChange(it)
                    },
                    running = state.running,
                    showLabels = showLabels,
                    openPanel = capabilityPanel,
                    onSelectPanel = { capabilityPanel = it },
                    onReasoningModeChange = { if (!state.running) onReasoningModeChange(it) },
                )
            },
            primaryAction = { PrimaryActionControl() },
        )
    }
}

@Composable
private fun ImportedImageAttachmentTile(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = withContext(Dispatchers.IO) {
            decodeLocalAttachmentThumbnail(
                workspacePath = workspacePath,
                relativePath = attachment.relativePath,
                targetPx = 256,
            )
        }
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(DsShapes.block)
            .background(colors.selected)
            .border(1.dp, colors.border, DsShapes.block),
    ) {
        thumbnail?.let { image ->
            Image(
                bitmap = image,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxWidth().size(88.dp).clip(DsShapes.block),
            )
        }
        SyIconButton(
            icon = FeatherIcons.X,
            contentDescription = stringResource(R.string.common_remove),
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd),
            iconSize = 16.dp,
            tint = colors.text,
            containerColor = colors.canvas.copy(alpha = 0.82f),
        )
    }
}

/** Consistent, readable sizes for compact file chips. */
internal fun localAttachmentDisplaySize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024L} KB"
    bytes < 1_073_741_824L -> java.lang.String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    else -> java.lang.String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
}

@Composable
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    onRemove: () -> Unit,
    onSelectPages: ((List<Int>) -> Unit)? = null,
) {
    val isPdf = attachment.canSelectPdfPages()
    var selectPages by remember(attachment.relativePath) { mutableStateOf(false) }
    if (selectPages && onSelectPages != null) {
        LocalPdfPageSelectionDialog(attachment.pdfPages, onDismiss = { selectPages = false }) {
            onSelectPages(it)
            selectPages = false
        }
    }
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val kind = attachment.name.substringAfterLast('.', "").uppercase()
        .takeIf { it.length in 1..7 } ?: "文件"
    Row(
        modifier = Modifier
            .widthIn(max = 276.dp)
            .heightIn(min = 60.dp)
            .clip(DsShapes.block)
            .background(colors.selected.copy(alpha = 0.55f))
            .border(1.dp, colors.border, DsShapes.block)
            .padding(start = SyMetric.gapMd, end = SyMetric.gapXs, top = SyMetric.gapSm, bottom = SyMetric.gapSm),
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FeatherIcons.FileText,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = colors.textSecondary,
        )
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                attachment.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = DsType.small13Strong.withReadingWeight(),
                color = colors.text,
            )
            Text(
                when {
                    attachment.pdfPages.isNotEmpty() ->
                        stringResource(R.string.local_pdf_selected_pages, attachment.pdfPages.joinToString(", "))
                    isPdf -> stringResource(R.string.local_pdf_auto_pages)
                    else -> "$kind · ${localAttachmentDisplaySize(attachment.bytes)}"
                },
                maxLines = 1,
                style = DsType.caption11.withReadingWeight(),
                color = colors.textSecondary,
            )
        }
        if (isPdf && onSelectPages != null) SyIconButton(
            icon = FeatherIcons.FileText,
            contentDescription = stringResource(R.string.local_pdf_choose_pages),
            onClick = { selectPages = true },
            iconSize = 18.dp,
        )
        SyIconButton(
            icon = FeatherIcons.X,
            contentDescription = stringResource(R.string.common_remove) + " " + attachment.name,
            onClick = onRemove,
            iconSize = 16.dp,
            tint = colors.textSecondary,
        )
    }
}


@Composable
private fun LocalGroupSpeakerPicker(
    roster: List<LocalGroupChatMember>,
    onDismiss: () -> Unit,
    onSelect: (Boolean, LocalGroupChatMember?) -> Unit,
) {
    SyDialog(title = stringResource(R.string.local_group_speakers_title), onDismiss = onDismiss) {
        SyButton(
            text = stringResource(R.string.local_group_speakers_auto),
            onClick = { onSelect(false, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        SyButton(
            text = stringResource(R.string.local_group_speakers_all),
            onClick = { onSelect(true, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        roster.forEach { member ->
            SyButton(
                text = composerGroupSpeakerLabel(member, roster),
                onClick = { onSelect(false, member) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
