package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.presentation.isUnboundChatPersona
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalReasoningUiMode
import com.labteto.dshmobile.local.presentation.LocalConversationSurfaceState
import com.labteto.dshmobile.local.send.LocalSendResult
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.shenyan.SyGlassCard
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
    onInputChange: (String) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearAttachments: () -> Unit,
    onOpenAttachmentPicker: () -> Unit,
    onShowReplySuggestions: () -> Unit,
    onGenerateReplySuggestions: suspend () -> Boolean,
    onSend: (String, List<LocalImportedAttachment>) -> LocalSendResult,
    onSendTeam: (String, List<LocalImportedAttachment>) -> LocalSendResult = onSend,
    teamDispatchSelected: Boolean = false,
    onClearTeamDispatch: () -> Unit = {},
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
    val groupChatReady = !state.groupChat.enabled || state.groupChat.members.size >= 2
    // A chat temperature change is committed asynchronously. Do not submit the next
    // turn with the old sampling value while the new persona setting is still saving.
    val temperatureWritePending = state.usageMode == LocalUsageMode.CHAT && temperatureSaving
    val canSend = !state.loading &&
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
                } else input,
                attachments.toList(),
            )
        }
        if (!result.accepted) return
        capabilityPanel = null
        steerCurrentRun = false
        onClearTeamDispatch()
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
            onClick = {
                capabilityPanel = null
                // Both collapsed and expanded composers open the same complete action sheet.
                // Release IME focus before showing it so the sheet never overlays the keyboard.
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onOpenAttachmentPicker()
            },
            tint = colors.text,
            containerColor = colors.selected,
        )
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

    @Composable
    fun SendControl() {
        SyComposerAction(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_ui_button_send_glyph),
            iconSize = 32.dp,
            tint = if (canSend) colors.onPrimary else colors.text,
            contentDescription = stringResource(R.string.chat_composer_send),
            onClick = ::submit,
            enabled = canSend,
            containerColor = colors.primary,
            visualSize = 32.dp,
        )
    }

    @Composable
    fun StopControl() {
        SyComposerAction(
            icon = null,
            iconPainter = painterResource(R.drawable.ic_ui_button_stop_glyph),
            iconSize = 32.dp,
            tint = colors.onPrimary,
            contentDescription = stringResource(R.string.chat_composer_stop),
            onClick = onStop,
            containerColor = colors.primary,
            visualSize = 32.dp,
        )
    }

    // One fixed action in either layout; draft text is retained during a running turn.
    @Composable
    fun RunActionControl() {
        if (state.running) StopControl() else SendControl()
    }

    SyConversationComposer(
        surfaceColor = v6ConversationComposerSurface(backgroundState, LocalSyColors.current),
        shadowElevation = 0.dp,
        // This composer owns a targeted row reveal. Avoid a second parent size animation while
        // the IME is already animating the whole surface.
        animateSize = false,
    ) {
        if (state.usageMode == LocalUsageMode.WORK && teamDispatchSelected) {
            // A compact icon-only indicator keeps team dispatch reversible without
            // repeating its name or squeezing the adjacent text into vertical glyphs.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_create_subagent),
                    contentDescription = stringResource(R.string.local_team_selected_hint),
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
                SyIconButton(
                    icon = FeatherIcons.X,
                    contentDescription = stringResource(R.string.local_team_mode_clear),
                    onClick = onClearTeamDispatch,
                    iconSize = 16.dp,
                    tint = colors.textSecondary,
                )
            }
        }
        if (shouldShowWorkSteeringChoice(
                mode = state.usageMode,
                running = state.running,
                teamDispatchSelected = teamDispatchSelected,
                hasDraft = input.isNotBlank(),
                dismissed = steeringChoiceDismissed,
            )
        ) {
            LocalWorkSteeringChoice(
                selected = steerCurrentRun,
                onToggle = { steerCurrentRun = !steerCurrentRun },
                onDismiss = {
                    steerCurrentRun = false // Closing always restores safe default next-turn queueing.
                    steeringChoiceDismissed = true
                },
            )
        }
        val imageAttachments = attachments.mapIndexedNotNull { index, attachment ->
            (index to attachment).takeIf { attachment.mediaType.startsWith("image/") }
        }
        if (imageAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            ) {
                items(
                    items = imageAttachments,
                    key = { (index, attachment) -> "$index:${attachment.relativePath}" },
                ) { (index, attachment) ->
                    ImportedImageAttachmentTile(
                        attachment = attachment,
                        workspacePath = state.workspacePath,
                        onRemove = { onRemoveAttachment(index) },
                    )
                }
            }
        }
        attachments.forEachIndexed { index, attachment ->
            if (!attachment.mediaType.startsWith("image/")) {
                ImportedAttachmentRow(
                    attachment = attachment,
                    workspacePath = state.workspacePath,
                    onRemove = { onRemoveAttachment(index) },
                )
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
            AnimatedVisibility(
                visible = !expanded,
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
                maxLines = 5,
                onFocusedChange = {
                    focused = it
                    onFocusChanged(it)
                },
            )
            AnimatedVisibility(
                visible = !expanded,
                enter = expandHorizontally(composerRevealSpec) + fadeIn(composerFadeSpec),
                exit = shrinkHorizontally(composerRevealSpec) + fadeOut(composerFadeSpec),
            ) {
                RunActionControl()
            }
        }

        LocalConversationComposerExpandedRow(
            visible = expanded,
            menuControl = { AttachmentControl() },
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
            primaryControl = { RunActionControl() },
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

@Composable
private fun ImportedAttachmentRow(
    attachment: LocalImportedAttachment,
    workspacePath: String,
    onRemove: () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var thumbnail by remember(attachment.relativePath, workspacePath) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(attachment.relativePath, workspacePath) {
        thumbnail = if (attachment.mediaType.startsWith("image/")) {
            withContext(Dispatchers.IO) {
                decodeLocalAttachmentThumbnail(workspacePath, attachment.relativePath)
            }
        } else {
            null
        }
    }
    SyGlassCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            thumbnail?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp).clip(DsShapes.block),
                )
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    attachment.name,
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.text,
                )
                val dimensions = if (attachment.width != null && attachment.height != null) {
                    " · ${attachment.width}×${attachment.height}"
                } else {
                    ""
                }
                Text(
                    "${attachment.mediaType}$dimensions · ${attachment.bytes} B",
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textTertiary,
                )
            }
            SyButton(
                stringResource(R.string.common_remove),
                onRemove,
                variant = SyButtonVariant.Ghost,
                size = SyButtonSize.Small,
            )
        }
    }
}
