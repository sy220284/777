package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.belongsTo

import com.shenyan.designsystem.foundation.SyMetric

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.labteto.dshmobile.R
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.rememberHapticPulse
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalMediaRevisionDraft
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalMediaVoice
import com.labteto.dshmobile.local.presentation.LocalMediaVoiceLabels
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.shenyan.designsystem.components.SyTextField
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import com.shenyan.designsystem.foundation.LocalSyColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** '+' is only a surface. Selected creator and credential are independent of the chat model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalMediaStudioSheetContent(
    profiles: List<LocalModelProfile>,
    preferredProfileId: String?,
    sessionId: String,
    onInsertTranscript: (String) -> Unit,
    onDelivered: () -> Unit = {},
    viewModel: LocalMediaStudioViewModel = hiltViewModel(),
    initialPrompt: String = "",
    personaVoiceStyle: String = "",
    sourceMessageId: String? = null,
    initialKind: LocalMediaKind = LocalMediaKind.IMAGE,
    revision: LocalMediaRevisionDraft? = null,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember(profiles) { LocalMediaCatalog.offers(profiles) }
    val archive by viewModel.archive.collectAsState()
    val videoTasks = archive.videos
    val generatedResults = archive.results
    val storageError by viewModel.storageError.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    var archiveRevision by rememberSaveable { mutableStateOf<LocalMediaRevisionDraft?>(null) }
    val activeRevision = archiveRevision ?: revision
    var showVideoArchive by remember { mutableStateOf(false) }
    var videoRefreshBusy by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable(activeRevision?.originId) { mutableStateOf(activeRevision?.kind?.name ?: initialKind.name) }
    val kind = LocalMediaKind.valueOf(mode)
    val matchingRevision = activeRevision?.forKind(kind)
    val available = models.filter { it.kind == kind }
    var chosenId by rememberSaveable(mode, activeRevision?.originId) { mutableStateOf("") }
    // A revision may only inherit the same frozen provider+account route. For deleted or
    // re-keyed accounts, require an explicit choice before any paid submission is possible.
    val originalOffer = matchingRevision?.originalOffer(models)
    // API-key rotation may keep the same profile ID; inspect the real key identity.
    val originalAccountVerified by androidx.compose.runtime.produceState<Boolean?>(
        initialValue = null, matchingRevision?.originId, originalOffer?.id
    ) {
        value = try {
            matchingRevision != null && originalOffer != null &&
                viewModel.matchesOriginalCredential(matchingRevision, originalOffer)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    val selected = if (matchingRevision != null) {
        if (chosenId.isBlank()) originalOffer?.takeIf { originalAccountVerified == true }
        else available.firstOrNull { it.id == chosenId }
    } else LocalMediaCatalog.selectOffer(models, kind, preferredProfileId, chosenId.takeIf(String::isNotBlank))
    val manualRouteActive = chosenId.isNotBlank() && selected?.id == chosenId
    val activeOfferId by rememberUpdatedState(selected?.id)
    var showModelChoices by remember { mutableStateOf(false) }
    var prompt by rememberSaveable(activeRevision?.originId) { mutableStateOf(activeRevision?.prompt ?: initialPrompt) }
    var ratio by rememberSaveable(activeRevision?.originId) { mutableStateOf(activeRevision?.ratio ?: "1:1") }
    var voiceId by rememberSaveable(selected?.id, personaVoiceStyle, activeRevision?.originId) {
        // An old provider voice ID is never valid evidence for a newly selected account/provider.
        val originalVoice = matchingRevision?.voiceId?.takeIf { selected?.id == originalOffer?.id }
        mutableStateOf(originalVoice ?: selected?.provider?.let {
            LocalMediaVoiceLabels.suggestedId(it, personaVoiceStyle)
        }.orEmpty())
    }
    // A saved local image can be used as actual image input only on a wired edit route.
    var editOriginalImage by rememberSaveable(matchingRevision?.originId, selected?.id) {
        mutableStateOf(
            matchingRevision?.originFileName != null &&
                selected?.provider == LocalMediaProvider.OPENAI &&
                selected?.kind == LocalMediaKind.IMAGE
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember(selected?.id) { mutableStateOf<String?>(null) }
    var viewedVideoTaskId by rememberSaveable(selected?.id) { mutableStateOf("") }
    var transcript by remember(selected?.id) { mutableStateOf<String?>(null) }
    var audioFile by remember(selected?.id) { mutableStateOf<File?>(null) }
    var voices by remember(selected?.id) { mutableStateOf<List<LocalMediaVoice>>(emptyList()) }
    var showVoices by remember(selected?.id) { mutableStateOf(false) }
    var showCustomVoiceId by rememberSaveable(selected?.id) { mutableStateOf(false) }
    var voiceFilter by remember(selected?.id) { mutableStateOf("") }

    val credentialChangedText = stringResource(R.string.media_recreate_credential_changed)

    fun submit(offer: LocalMediaOffer, action: suspend () -> Unit) {
        if (busy) return
        // Reserve the UI operation synchronously so two rapid taps cannot queue two paid POSTs.
        busy = true
        error = null
        val originId = offer.id
        scope.launch {
            try {
                // Recheck at submission time: account credentials can change while the sheet is open.
                if (matchingRevision != null && chosenId.isBlank()) {
                    check(viewModel.matchesOriginalCredential(matchingRevision, offer)) {
                        credentialChangedText
                    }
                }
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (originId == activeOfferId) error = failure.message ?: ""
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected != null) {
            val chosen = selected
            submit(chosen) {
                viewModel.submitTranscribe(chosen, uri, sessionId, if (matchingRevision != null) matchingRevision.sourceMessageId else sourceMessageId, matchingRevision?.originId)
                if (chosen.id == activeOfferId) onDelivered()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val mediaKinds = listOf(LocalMediaKind.IMAGE, LocalMediaKind.VIDEO,
            LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE)
        MediaStudioFlatTabs(
            labels = listOf(R.string.minimax_media_image, R.string.minimax_media_video,
                R.string.minimax_media_speech, R.string.minimax_media_asr).map { stringResource(it) },
            selected = mediaKinds.indexOf(kind),
            onSelect = { if (!busy) { mode = mediaKinds[it].name; showModelChoices = false } },
            tag = "mediaDesignSection", enabled = !busy,
        )

        if (selected == null) {
            com.shenyan.designsystem.components.SyEmptyState(FeatherIcons.Image,
                if (matchingRevision != null) stringResource(R.string.media_recreate_route_missing)
                else stringResource(R.string.media_studio_no_model), body = "")
            if (available.isNotEmpty()) {
                Text(stringResource(R.string.media_recreate_change_account_notice), color = colors.textSecondary,
                    style = DsType.caption11.withReadingWeight())
                available.forEach { option ->
                    SyButton(
                        text = "${option.label} · ${option.profile.displayName ?: option.profile.model}",
                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                        onClick = { chosenId = option.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            // 模型仍可切换，但不再用整行大按钮和聊天模型名称挤占创作区。
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .testTag("mediaStudioModelSelector")
                    .clickable(enabled = !busy) { showModelChoices = !showModelChoices },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(if (!manualRouteActive) R.string.media_route_current_auto else R.string.media_route_current_manual, selected.label),
                    modifier = Modifier.weight(1f),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = FeatherIcons.ChevronDown,
                    contentDescription = stringResource(R.string.media_studio_select_model),
                    modifier = Modifier.size(18.dp),
                    tint = colors.textSecondary,
                )
            }
            if (!manualRouteActive && preferredProfileId != null && selected.profile.id != preferredProfileId) {
                Text(
                    text = stringResource(R.string.media_route_account_fallback,
                        selected.profile.displayName ?: selected.profile.model),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showModelChoices) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(152.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "auto") {
                        SyButton(text = stringResource(R.string.media_route_auto),
                            onClick = { chosenId = ""; showModelChoices = false },
                            size = SyButtonSize.Small,
                            variant = if (!manualRouteActive) SyButtonVariant.Primary else SyButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth())
                    }
                    items(available, key = { it.id }) { option ->
                        SyButton(
                            text = stringResource(
                                R.string.media_studio_selected_model, option.label,
                                option.profile.displayName ?: option.profile.model,
                            ),
                            onClick = { chosenId = option.id; showModelChoices = false },
                            enabled = !busy,
                            size = SyButtonSize.Small,
                            variant = if (selected.id == option.id) SyButtonVariant.Primary else SyButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (kind != LocalMediaKind.TRANSCRIBE) {
                SyTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(
                        if (kind == LocalMediaKind.SPEECH) R.string.minimax_media_speech_input
                        else R.string.minimax_media_prompt
                    )) },
                    // Keep long prompts readable without requiring constant inner scrolling.
                    // On short devices the sheet itself scrolls to the generate action.
                    minLines = if (kind == LocalMediaKind.SPEECH) 8 else 12,
                    maxLines = if (kind == LocalMediaKind.SPEECH) 14 else 20,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("mediaStudioPromptField"),
                    enabled = !busy,
                )
            }

            if (matchingRevision == null && sourceMessageId != null && prompt.isBlank() &&
                (kind == LocalMediaKind.IMAGE || kind == LocalMediaKind.VIDEO)
            ) {
                Text(
                    text = stringResource(R.string.media_reply_requires_visual_scene),
                    style = DsType.caption11.withReadingWeight(),
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when (kind) {
                LocalMediaKind.IMAGE -> {
                    val ratios = listOf("1:1", "16:9", "9:16")
                    MediaStudioFlatTabs(
                        labels = ratios,
                        selected = ratios.indexOf(ratio),
                        onSelect = { ratio = ratios[it] },
                        tag = "mediaRatio",
                        enabled = !busy,
                    )
                    if (matchingRevision != null) {
                        if (selected.provider == LocalMediaProvider.OPENAI && matchingRevision.originFileName != null) {
                            MediaStudioFlatTabs(
                                labels = listOf(stringResource(R.string.media_recreate_edit_pixels),
                                    stringResource(R.string.media_recreate_redraw_only)),
                                selected = if (editOriginalImage) 0 else 1,
                                onSelect = { editOriginalImage = it == 0 },
                                tag = "mediaImageRevisionMode",
                                enabled = !busy,
                            )
                        } else {
                            Text(stringResource(R.string.media_recreate_edit_not_supported),
                                color = colors.textSecondary, style = DsType.caption11.withReadingWeight())
                        }
                    }
                    SyButton(text = stringResource(R.string.minimax_media_create_image),
                        enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            viewModel.submitImage(
                                selected, prompt, ratio, sessionId,
                                if (matchingRevision != null) matchingRevision.sourceMessageId else sourceMessageId,
                                matchingRevision?.originId,
                                if (editOriginalImage && matchingRevision != null &&
                                    selected.provider == LocalMediaProvider.OPENAI)
                                    matchingRevision.originId else null,
                            )
                            if (selected.id == activeOfferId) onDelivered()
                        } })
                }
                LocalMediaKind.VIDEO -> {
                    SyButton(text = stringResource(R.string.minimax_media_start_video),
                        enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            val task = viewModel.createVideo(selected, prompt, sessionId, if (matchingRevision != null) matchingRevision.sourceMessageId else sourceMessageId, matchingRevision?.originId)
                            viewedVideoTaskId = task.id
                            onDelivered()
                        } })
                    val taskRecords = videoTasks.filter { it.belongsTo(selected) }.asReversed()
                    val currentTask = taskRecords.firstOrNull { it.id == viewedVideoTaskId }
                        ?: taskRecords.firstOrNull()
                    if (taskRecords.size > 1) {
                        Text(stringResource(R.string.media_studio_previous_video_tasks),
                            style = DsType.caption11.withReadingWeight(), color = colors.textSecondary)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(128.dp),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        ) {
                            items(taskRecords, key = { it.id }) { task ->
                                SyButton(
                                    text = "${task.model} · ${task.providerTaskId ?: task.id.take(8)}",
                                    size = SyButtonSize.Small,
                                    variant = if (currentTask?.id == task.id) SyButtonVariant.Primary
                                        else SyButtonVariant.Ghost,
                                    onClick = { viewedVideoTaskId = task.id },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    currentTask?.let { task ->
                        task.providerTaskId?.let { taskId ->
                            Text(stringResource(R.string.minimax_media_task_id, taskId),
                                color = colors.textSecondary, style = DsType.caption11.withReadingWeight())
                        }
                        Text(
                            stringResource(when (task.phase) {
                                LocalMediaVideoPhase.SUBMITTING -> R.string.media_studio_video_submitting
                                LocalMediaVideoPhase.OUTCOME_UNKNOWN -> R.string.media_studio_video_unknown
                                LocalMediaVideoPhase.QUEUED -> R.string.minimax_media_video_queued
                                LocalMediaVideoPhase.PROCESSING -> R.string.minimax_media_video_processing
                                LocalMediaVideoPhase.SUCCEEDED -> R.string.minimax_media_video_success
                                LocalMediaVideoPhase.FAILED -> R.string.minimax_media_video_failed
                            }),
                            color = colors.textSecondary,
                        )
                        task.lastError?.let { reason ->
                            Text(reason, color = colors.textSecondary,
                                style = DsType.caption11.withReadingWeight())
                        }
                        if (task.phase == LocalMediaVideoPhase.QUEUED ||
                            task.phase == LocalMediaVideoPhase.PROCESSING
                        ) {
                            SyButton(text = stringResource(R.string.minimax_media_check_video),
                                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                onClick = { submit(selected) {
                                    viewModel.refreshVideo(selected, task.id)
                                } })
                        }
                        task.downloadUrl?.let { url ->
                            SyButton(text = stringResource(R.string.minimax_media_open_video),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { openMediaUrl(context, url) { error = it } })
                        }
                    }
                }
                LocalMediaKind.SPEECH -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (voiceId.isBlank()) stringResource(R.string.media_voice_required)
                                else stringResource(R.string.media_voice_current, voices.firstOrNull { it.id == voiceId }?.name ?: LocalMediaVoiceLabels.label(selected.provider, voiceId)),
                            modifier = Modifier.weight(1f),
                            style = DsType.small13Strong.withReadingWeight(),
                            color = colors.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SyButton(text = stringResource(R.string.minimax_media_select_voice),
                            onClick = { submit(selected) {
                                val fetched = viewModel.voices(selected)
                                if (selected.id == activeOfferId) { voices = fetched; showVoices = true }
                            } },
                            enabled = !busy, size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost)
                    }
                    if (showVoices) {
                        SyTextField(value = voiceFilter, onValueChange = { voiceFilter = it },
                            label = { Text(stringResource(R.string.minimax_media_filter_voice)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(176.dp),
                            verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        ) {
                            items(voices.filter {
                                it.name.contains(voiceFilter, true) || it.id.contains(voiceFilter, true)
                            }, key = { it.id }) { voice ->
                                SyButton(text = voice.name,
                                    onClick = { voiceId = voice.id; showVoices = false },
                                    modifier = Modifier.fillMaxWidth(), size = SyButtonSize.Small,
                                    variant = SyButtonVariant.Ghost)
                            }
                        }
                    }
                    SyButton(text = stringResource(R.string.media_voice_custom_toggle),
                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                        onClick = { showCustomVoiceId = !showCustomVoiceId })
                    if (showCustomVoiceId) {
                        SyTextField(value = voiceId, onValueChange = { voiceId = it },
                            label = { Text(stringResource(R.string.media_voice_custom_field)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    }
                    SyButton(text = stringResource(R.string.minimax_media_create_speech),
                        enabled = !busy && prompt.isNotBlank() && voiceId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            viewModel.submitSpeech(selected, prompt, voiceId, sessionId, if (matchingRevision != null) matchingRevision.sourceMessageId else sourceMessageId, matchingRevision?.originId)
                            if (selected.id == activeOfferId) onDelivered()
                        } })
                    audioFile?.let { file ->
                        SyButton(text = stringResource(R.string.minimax_media_share_speech),
                            onClick = { shareMediaFile(context, file, "audio/mpeg") { error = it } },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                LocalMediaKind.TRANSCRIBE -> {
                    SyButton(text = stringResource(R.string.minimax_media_pick_audio),
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = { picker.launch(arrayOf("audio/*")) })
                    transcript?.let { text ->
                        Text(text, color = colors.text, style = DsType.std14.withReadingWeight())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                            SyButton(text = stringResource(R.string.minimax_media_insert_text),
                                onClick = { onInsertTranscript(text) }, size = SyButtonSize.Small)
                            SyButton(text = stringResource(R.string.minimax_media_copy_text),
                                onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("transcription", text)) },
                                size = SyButtonSize.Small, variant = SyButtonVariant.Ghost)
                        }
                    }
                }
            }
            if (busy) Text(stringResource(R.string.minimax_media_running),
                color = colors.textSecondary, style = DsType.caption11.withReadingWeight())
            error?.let { Text(stringResource(R.string.minimax_media_error, it),
                color = colors.text, style = DsType.caption11.withReadingWeight()) }
        }
        storageError?.let {
            SyButton(text = stringResource(R.string.media_retry_storage), onClick = { scope.launch { viewModel.retryStorage() } })
            Text(stringResource(R.string.minimax_media_error, it), color = colors.text, style = DsType.caption11.withReadingWeight())
        }
        if (archive.hasMore) {
            SyButton(text = stringResource(R.string.media_load_older), onClick = viewModel::loadOlderArchive)
        }
        if (videoTasks.isNotEmpty() || generatedResults.isNotEmpty()) {
            SyButton(
                text = stringResource(R.string.media_studio_archive_compact,
                    videoTasks.size + generatedResults.size),
                onClick = {
                    showArchive = !showArchive
                    if (!showArchive) { showHistory = false; showVideoArchive = false }
                },
                modifier = Modifier.fillMaxWidth(),
                variant = SyButtonVariant.Ghost,
            )
        }
        if (showArchive) {
        if (videoTasks.isNotEmpty()) {
            SyButton(
                text = stringResource(R.string.media_studio_video_archive, videoTasks.size),
                onClick = { showVideoArchive = !showVideoArchive },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showVideoArchive) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(232.dp),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                ) {
                    items(videoTasks.asReversed(), key = { it.id }) { task ->
                        val matchingOffer = models.firstOrNull { task.belongsTo(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                            Text(
                                stringResource(R.string.media_studio_history_item,
                                    task.provider, task.model, stringResource(when (task.phase) {
                                        LocalMediaVideoPhase.SUBMITTING -> R.string.media_studio_video_submitting
                                        LocalMediaVideoPhase.OUTCOME_UNKNOWN -> R.string.media_studio_video_unknown
                                        LocalMediaVideoPhase.QUEUED -> R.string.minimax_media_video_queued
                                        LocalMediaVideoPhase.PROCESSING -> R.string.minimax_media_video_processing
                                        LocalMediaVideoPhase.SUCCEEDED -> R.string.minimax_media_video_success
                                        LocalMediaVideoPhase.FAILED -> R.string.minimax_media_video_failed
                                    })),
                                color = colors.text,
                                style = DsType.caption11.withReadingWeight(),
                            )
                            Text(
                                task.providerTaskId ?: task.id,
                                color = colors.textSecondary,
                                style = DsType.caption11.withReadingWeight(),
                            )
                            task.lastError?.let { reason ->
                                Text(reason, color = colors.textSecondary,
                                    style = DsType.caption11.withReadingWeight())
                            }
                            if (matchingOffer == null) {
                                Text(stringResource(R.string.media_studio_video_account_unavailable),
                                    color = colors.textSecondary,
                                    style = DsType.caption11.withReadingWeight())
                            } else if (task.phase in setOf(
                                    LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING, LocalMediaVideoPhase.SUCCEEDED
                                )
                            ) {
                                SyButton(
                                    text = stringResource(R.string.minimax_media_check_video),
                                    size = SyButtonSize.Small,
                                    enabled = videoRefreshBusy == null,
                                    onClick = {
                                        videoRefreshBusy = task.id
                                        scope.launch {
                                            try {
                                                viewModel.refreshVideo(matchingOffer, task.id)
                                            } catch (failure: Exception) {
                                                historyError = failure.message.orEmpty()
                                            } finally {
                                                videoRefreshBusy = null
                                            }
                                        }
                                    },
                                )
                            }
                            if (task.phase == LocalMediaVideoPhase.SUCCEEDED && viewModel.videoFile(task) == null) {
                                SyButton(text = stringResource(R.string.media_retry_save), size = SyButtonSize.Small,
                                    onClick = { scope.launch {
                                        try { viewModel.retryVideoSave(task.id) }
                                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (failure: Exception) { historyError = failure.message.orEmpty() }
                                    } })
                            }
                            if (task.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED, LocalMediaVideoPhase.OUTCOME_UNKNOWN)) {
                                SyButton(text = stringResource(R.string.media_studio_delete_record), size = SyButtonSize.Small,
                                    onClick = { pendingDeleteId = task.id })
                                if (pendingDeleteId == task.id) {
                                    Text(stringResource(R.string.media_delete_video_warning))
                                    SyButton(text = stringResource(R.string.media_studio_delete_confirm), size = SyButtonSize.Small,
                                        onClick = { scope.launch {
                                            try { viewModel.removeResult(task.id); pendingDeleteId = null }
                                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (failure: Exception) { historyError = failure.message.orEmpty() }
                                        } })
                                    SyButton(text = stringResource(R.string.media_studio_delete_cancel), size = SyButtonSize.Small,
                                        onClick = { pendingDeleteId = null })
                                }
                            }
                            if (task.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED)) {
                                SyButton(text = stringResource(R.string.media_recreate_edit_and_generate), size = SyButtonSize.Small,
                                    variant = SyButtonVariant.Ghost, onClick = {
                                        archiveRevision = LocalMediaRevisionDraft.from(task)
                                        showVideoArchive = false
                                    })
                            }
                            task.downloadUrl?.let { url ->
                                SyButton(
                                    text = stringResource(R.string.minimax_media_open_video),
                                    size = SyButtonSize.Small,
                                    onClick = { openMediaUrl(context, url) { historyError = it } },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (generatedResults.isNotEmpty()) {
            SyButton(
                text = stringResource(R.string.media_studio_history, generatedResults.size),
                onClick = { showHistory = !showHistory },
                variant = SyButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showHistory) {
                Text(stringResource(R.string.media_studio_temporary_links_notice),
                    color = colors.textSecondary,
                    style = DsType.caption11.withReadingWeight())
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(232.dp),
                    verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg),
                ) {
                    items(generatedResults.asReversed(), key = { it.id }) { asset ->
                        Column(verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                            Text(
                                stringResource(
                                    R.string.media_studio_history_item,
                                    asset.provider,
                                    asset.model,
                                    when (asset.kind) {
                                        LocalMediaKind.SPEECH.name -> stringResource(R.string.media_studio_kind_speech)
                                        LocalMediaKind.TRANSCRIBE.name -> stringResource(R.string.minimax_media_asr)
                                        else -> stringResource(R.string.media_studio_kind_image)
                                    },
                                ),
                                color = colors.text,
                                style = DsType.caption11.withReadingWeight(),
                            )
                            val file = viewModel.resultFile(asset)
                            val mime = when (file?.extension?.lowercase()) {
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "webp" -> "image/webp"
                                "mp3" -> "audio/mpeg"
                                else -> "application/octet-stream"
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                                if (file != null) {
                                    SyButton(
                                        text = stringResource(R.string.media_studio_view_result),
                                        size = SyButtonSize.Small,
                                        onClick = { viewMediaFile(context, file, mime) { historyError = it } },
                                    )
                                    SyButton(
                                        text = stringResource(R.string.media_studio_share),
                                        size = SyButtonSize.Small,
                                        variant = SyButtonVariant.Ghost,
                                        onClick = { shareMediaFile(context, file, mime) { historyError = it } },
                                    )
                                } else if (asset.textOutput != null) {
                                    SyButton(
                                        text = stringResource(R.string.minimax_media_copy_text),
                                        size = SyButtonSize.Small,
                                        onClick = {
                                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                                .setPrimaryClip(ClipData.newPlainText("transcription", asset.textOutput))
                                        },
                                    )
                                } else if (asset.remoteUrl != null) {
                                    SyButton(
                                        text = stringResource(R.string.media_studio_open_temporary_link),
                                        size = SyButtonSize.Small,
                                        onClick = { openMediaUrl(context, asset.remoteUrl) { historyError = it } },
                                    )
                                } else {
                                    Text(stringResource(R.string.media_studio_result_missing),
                                        style = DsType.caption11.withReadingWeight(),
                                        color = colors.textSecondary)
                                }
                                LocalMediaRevisionDraft.from(asset)?.let { draft ->
                                    SyButton(text = if (draft.kind == LocalMediaKind.TRANSCRIBE)
                                        stringResource(R.string.media_recreate_transcribe_select_audio) else stringResource(R.string.media_recreate_edit_and_generate),
                                        size = SyButtonSize.Small,
                                        variant = SyButtonVariant.Ghost,
                                        onClick = {
                                            archiveRevision = draft
                                            showHistory = false
                                        })
                                }
                                SyButton(
                                    text = stringResource(R.string.media_studio_delete_record),
                                    size = SyButtonSize.Small,
                                    variant = SyButtonVariant.Ghost,
                                    onClick = { pendingDeleteId = asset.id },
                                )
                            }
                            if (pendingDeleteId == asset.id) {
                                Text(stringResource(R.string.media_studio_confirm_delete),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.textSecondary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd),
                        verticalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                                    SyButton(text = stringResource(R.string.media_studio_delete_confirm),
                                        size = SyButtonSize.Small, onClick = {
                                            scope.launch {
                                                try {
                                                    viewModel.removeResult(asset.id)
                                                    pendingDeleteId = null
                                                } catch (failure: Exception) {
                                                    historyError = failure.message.orEmpty()
                                                }
                                            }
                                        })
                                    SyButton(text = stringResource(R.string.media_studio_delete_cancel),
                                        size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                                        onClick = { pendingDeleteId = null })
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        historyError?.let { Text(it, color = colors.text,
            style = DsType.caption11.withReadingWeight()) }
    }
}

/**
 * 媒体创作专用轻量选项栏：仅保留文字和细选中线，不复用带材质底板的通用导航。
 * 保留 selectable/Tab 语义与原有测试标签，窄屏仍有完整触控面积。
 */
@Composable
internal fun MediaStudioFlatTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val colors = LocalSyColors.current
    val pulse = rememberHapticPulse()
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    .testTag("$tag:$index")
                    .selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { if (!active) pulse(); onSelect(index) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
                    style = DsType.small13Strong.withReadingWeight(),
                    color = if (active) colors.text else colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                androidx.compose.foundation.layout.Spacer(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (active) colors.primary else Color.Transparent),
                )
            }
        }
    }
}

internal fun openMediaUrl(context: Context, url: String, onError: (String) -> Unit) {
    if (!url.startsWith("https://")) {
        onError(context.getString(R.string.minimax_media_invalid_url))
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { onError(it.message.orEmpty()) }
}

internal fun viewMediaFile(context: Context, file: File, mime: String, onError: (String) -> Unit) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure { onError(it.message.orEmpty()) }
}

internal fun shareMediaFile(context: Context, file: File, mime: String, onError: (String) -> Unit) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.media_studio_share)))
    }.onFailure { onError(it.message.orEmpty()) }
}
