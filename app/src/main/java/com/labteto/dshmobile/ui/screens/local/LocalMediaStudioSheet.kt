package com.labteto.dshmobile.ui.screens.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaCatalog
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaProvider
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.presentation.LocalMediaImage
import com.labteto.dshmobile.local.presentation.LocalMediaVoice
import com.labteto.dshmobile.ui.components.DsButton
import com.labteto.dshmobile.ui.components.DsButtonSize
import com.labteto.dshmobile.ui.components.DsButtonVariant
import com.labteto.dshmobile.ui.components.DsTextField
import com.labteto.dshmobile.ui.theme.DsSpacing
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** '+' is only a surface. Selected creator and credential are independent of the chat model. */
@Composable
internal fun LocalMediaStudioSheetContent(
    profiles: List<LocalModelProfile>,
    preferredProfileId: String?,
    sessionId: String,
    onInsertTranscript: (String) -> Unit,
    onDelivered: () -> Unit = {},
    viewModel: LocalMediaStudioViewModel = hiltViewModel(),
    initialPrompt: String = "",
    initialKind: LocalMediaKind = LocalMediaKind.IMAGE,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember(profiles) { LocalMediaCatalog.offers(profiles) }
    val videoTasks by viewModel.videoTasks.collectAsState()
    val generatedResults by viewModel.generatedResults.collectAsState()
    val storageError by viewModel.storageError.collectAsState()
    var showHistory by remember { mutableStateOf(true) }
    var showVideoArchive by remember { mutableStateOf(false) }
    var videoRefreshBusy by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(initialKind.name) }
    val kind = LocalMediaKind.valueOf(mode)
    val available = models.filter { it.kind == kind }
    var chosenId by rememberSaveable(mode) { mutableStateOf("") }
    val selected = available.firstOrNull { it.id == chosenId }
        ?: available.firstOrNull { it.profile.id == preferredProfileId }
        ?: available.firstOrNull()
    val activeOfferId by rememberUpdatedState(selected?.id)
    var showModelChoices by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voiceId by rememberSaveable(selected?.id) {
        mutableStateOf(if (selected?.provider == LocalMediaProvider.OPENAI) "alloy" else "male-qn-qingse")
    }
    var busy by remember(selected?.id) { mutableStateOf(false) }
    var error by remember(selected?.id) { mutableStateOf<String?>(null) }
    var image by remember(selected?.id) { mutableStateOf<LocalMediaImage?>(null) }
    var viewedVideoTaskId by rememberSaveable(selected?.id) { mutableStateOf("") }
    var transcript by remember(selected?.id) { mutableStateOf<String?>(null) }
    var audioFile by remember(selected?.id) { mutableStateOf<File?>(null) }
    var voices by remember(selected?.id) { mutableStateOf<List<LocalMediaVoice>>(emptyList()) }
    var showVoices by remember(selected?.id) { mutableStateOf(false) }
    var voiceFilter by remember(selected?.id) { mutableStateOf("") }

    fun submit(offer: LocalMediaOffer, action: suspend () -> Unit) {
        if (busy) return
        val originId = offer.id
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (originId == activeOfferId) error = failure.message ?: ""
            } finally {
                if (originId == activeOfferId) busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected != null) {
            val chosen = selected
            submit(chosen) {
                val answer = viewModel.transcribe(chosen, uri, sessionId)
                if (chosen.id == activeOfferId) {
                    transcript = answer
                    onDelivered()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(stringResource(R.string.media_studio_notice), color = colors.labelSecondary,
            style = DsType.caption11.withReadingWeight())
        val mediaKinds = listOf(LocalMediaKind.IMAGE, LocalMediaKind.VIDEO,
            LocalMediaKind.SPEECH, LocalMediaKind.TRANSCRIBE)
        FluidSectionNavigation(
            labels = listOf(R.string.minimax_media_image, R.string.minimax_media_video,
                R.string.minimax_media_speech, R.string.minimax_media_asr).map { stringResource(it) },
            selected = mediaKinds.indexOf(kind),
            onSelect = { if (!busy) { mode = mediaKinds[it].name; showModelChoices = false } },
            tag = "mediaDesignSection", enabled = !busy,
        )

        if (selected == null) {
            Text(stringResource(R.string.media_studio_no_model), color = colors.labelSecondary)
        } else {
            DsButton(
                text = stringResource(
                    R.string.media_studio_selected_model, selected.label,
                    selected.profile.displayName ?: selected.profile.model,
                ),
                onClick = { if (!busy) showModelChoices = !showModelChoices },
                modifier = Modifier.fillMaxWidth(),
                variant = DsButtonVariant.Ghost,
                enabled = !busy,
            )
            if (showModelChoices) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(176.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    items(available, key = { it.id }) { option ->
                        DsButton(
                            text = stringResource(
                                R.string.media_studio_selected_model, option.label,
                                option.profile.displayName ?: option.profile.model,
                            ),
                            onClick = { chosenId = option.id; showModelChoices = false },
                            enabled = !busy,
                            size = DsButtonSize.Small,
                            variant = if (selected.id == option.id) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (kind != LocalMediaKind.TRANSCRIBE) {
                DsTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = { Text(stringResource(
                        if (kind == LocalMediaKind.SPEECH) R.string.minimax_media_speech_input
                        else R.string.minimax_media_prompt
                    )) },
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                )
            }

            when (kind) {
                LocalMediaKind.IMAGE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        listOf("1:1", "16:9", "9:16").forEach { option ->
                            DsButton(text = option, enabled = !busy, size = DsButtonSize.Small,
                                variant = if (ratio == option) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                                onClick = { ratio = option })
                        }
                    }
                    DsButton(text = stringResource(R.string.minimax_media_create_image),
                        enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            val result = viewModel.image(selected, prompt, ratio, sessionId)
                            if (selected.id == activeOfferId) { image = result; onDelivered() }
                        } })
                    image?.url?.let { url ->
                        DsButton(text = stringResource(R.string.minimax_media_open_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openMediaUrl(context, url) { error = it } })
                    }
                    image?.file?.let { file ->
                        val mime = when (file.extension.lowercase()) {
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "webp" -> "image/webp"
                            else -> "application/octet-stream"
                        }
                        DsButton(text = stringResource(R.string.media_studio_view_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewMediaFile(context, file, mime) { error = it } })
                        DsButton(text = stringResource(R.string.media_studio_share_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { shareMediaFile(context, file, mime) { error = it } })
                    }
                }
                LocalMediaKind.VIDEO -> {
                    DsButton(text = stringResource(R.string.minimax_media_start_video),
                        enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            val task = viewModel.createVideo(selected, prompt, sessionId)
                            viewedVideoTaskId = task.id
                            onDelivered()
                        } })
                    val taskRecords = videoTasks.filter { it.belongsTo(selected) }.asReversed()
                    val currentTask = taskRecords.firstOrNull { it.id == viewedVideoTaskId }
                        ?: taskRecords.firstOrNull()
                    if (taskRecords.size > 1) {
                        Text(stringResource(R.string.media_studio_previous_video_tasks),
                            style = DsType.caption11.withReadingWeight(), color = colors.labelSecondary)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(128.dp),
                            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            items(taskRecords, key = { it.id }) { task ->
                                DsButton(
                                    text = "${task.model} · ${task.providerTaskId ?: task.id.take(8)}",
                                    size = DsButtonSize.Small,
                                    variant = if (currentTask?.id == task.id) DsButtonVariant.Primary
                                        else DsButtonVariant.Ghost,
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
                                color = colors.labelSecondary, style = DsType.caption11.withReadingWeight())
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
                            color = colors.labelSecondary,
                        )
                        task.lastError?.let { reason ->
                            Text(reason, color = colors.labelSecondary,
                                style = DsType.caption11.withReadingWeight())
                        }
                        if (task.phase == LocalMediaVideoPhase.QUEUED ||
                            task.phase == LocalMediaVideoPhase.PROCESSING
                        ) {
                            DsButton(text = stringResource(R.string.minimax_media_check_video),
                                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                onClick = { submit(selected) {
                                    viewModel.refreshVideo(selected, task.id)
                                } })
                        }
                        task.downloadUrl?.let { url ->
                            DsButton(text = stringResource(R.string.minimax_media_open_video),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { openMediaUrl(context, url) { error = it } })
                        }
                    }
                }
                LocalMediaKind.SPEECH -> {
                    DsTextField(
                        value = voiceId, onValueChange = { voiceId = it },
                        label = { Text(stringResource(R.string.minimax_media_voice_id)) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    DsButton(text = stringResource(R.string.minimax_media_select_voice),
                        onClick = { submit(selected) {
                            val fetched = viewModel.voices(selected)
                            if (selected.id == activeOfferId) { voices = fetched; showVoices = true }
                        } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth(), variant = DsButtonVariant.Ghost)
                    if (showVoices) {
                        DsTextField(value = voiceFilter, onValueChange = { voiceFilter = it },
                            label = { Text(stringResource(R.string.minimax_media_filter_voice)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(176.dp),
                            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                        ) {
                            items(voices.filter {
                                it.name.contains(voiceFilter, true) || it.id.contains(voiceFilter, true)
                            }, key = { it.id }) { voice ->
                                DsButton(text = voice.name,
                                    onClick = { voiceId = voice.id; showVoices = false },
                                    modifier = Modifier.fillMaxWidth(), size = DsButtonSize.Small,
                                    variant = DsButtonVariant.Ghost)
                            }
                        }
                    }
                    DsButton(text = stringResource(R.string.minimax_media_create_speech),
                        enabled = !busy && prompt.isNotBlank() && voiceId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            val file = viewModel.speech(selected, prompt, voiceId, sessionId)
                            if (selected.id == activeOfferId) { audioFile = file; onDelivered() }
                        } })
                    audioFile?.let { file ->
                        DsButton(text = stringResource(R.string.minimax_media_share_speech),
                            onClick = { shareMediaFile(context, file, "audio/mpeg") { error = it } },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                LocalMediaKind.TRANSCRIBE -> {
                    DsButton(text = stringResource(R.string.minimax_media_pick_audio),
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        onClick = { picker.launch(arrayOf("audio/*")) })
                    transcript?.let { text ->
                        Text(text, color = colors.labelPrimary, style = DsType.std14.withReadingWeight())
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            DsButton(text = stringResource(R.string.minimax_media_insert_text),
                                onClick = { onInsertTranscript(text) }, size = DsButtonSize.Small)
                            DsButton(text = stringResource(R.string.minimax_media_copy_text),
                                onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("transcription", text)) },
                                size = DsButtonSize.Small, variant = DsButtonVariant.Ghost)
                        }
                    }
                }
            }
            if (busy) Text(stringResource(R.string.minimax_media_running),
                color = colors.labelSecondary, style = DsType.caption11.withReadingWeight())
            error?.let { Text(stringResource(R.string.minimax_media_error, it),
                color = colors.labelPrimary, style = DsType.caption11.withReadingWeight()) }
        }
        storageError?.let {
            Text(stringResource(R.string.minimax_media_error, it), color = colors.labelPrimary, style = DsType.caption11.withReadingWeight())
        }
        if (videoTasks.isNotEmpty()) {
            DsButton(
                text = stringResource(R.string.media_studio_video_archive, videoTasks.size),
                onClick = { showVideoArchive = !showVideoArchive },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showVideoArchive) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(232.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    items(videoTasks.asReversed(), key = { it.id }) { task ->
                        val matchingOffer = models.firstOrNull { task.belongsTo(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
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
                                color = colors.labelPrimary,
                                style = DsType.caption11.withReadingWeight(),
                            )
                            Text(
                                task.providerTaskId ?: task.id,
                                color = colors.labelSecondary,
                                style = DsType.caption11.withReadingWeight(),
                            )
                            task.lastError?.let { reason ->
                                Text(reason, color = colors.labelSecondary,
                                    style = DsType.caption11.withReadingWeight())
                            }
                            if (matchingOffer == null) {
                                Text(stringResource(R.string.media_studio_video_account_unavailable),
                                    color = colors.labelSecondary,
                                    style = DsType.caption11.withReadingWeight())
                            } else if (task.phase in setOf(
                                    LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING
                                )
                            ) {
                                DsButton(
                                    text = stringResource(R.string.minimax_media_check_video),
                                    size = DsButtonSize.Small,
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
                            task.downloadUrl?.let { url ->
                                DsButton(
                                    text = stringResource(R.string.minimax_media_open_video),
                                    size = DsButtonSize.Small,
                                    onClick = { openMediaUrl(context, url) { historyError = it } },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (generatedResults.isNotEmpty()) {
            DsButton(
                text = stringResource(R.string.media_studio_history, generatedResults.size),
                onClick = { showHistory = !showHistory },
                variant = DsButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showHistory) {
                Text(stringResource(R.string.media_studio_temporary_links_notice),
                    color = colors.labelSecondary,
                    style = DsType.caption11.withReadingWeight())
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(232.dp),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
                ) {
                    items(generatedResults.asReversed(), key = { it.id }) { asset ->
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            Text(
                                stringResource(
                                    R.string.media_studio_history_item,
                                    asset.provider,
                                    asset.model,
                                    if (asset.kind == LocalMediaKind.SPEECH.name) stringResource(R.string.media_studio_kind_speech) else stringResource(R.string.media_studio_kind_image),
                                ),
                                color = colors.labelPrimary,
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
                            Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                                if (file != null) {
                                    DsButton(
                                        text = stringResource(R.string.media_studio_view_result),
                                        size = DsButtonSize.Small,
                                        onClick = { viewMediaFile(context, file, mime) { historyError = it } },
                                    )
                                    DsButton(
                                        text = stringResource(R.string.media_studio_share),
                                        size = DsButtonSize.Small,
                                        variant = DsButtonVariant.Ghost,
                                        onClick = { shareMediaFile(context, file, mime) { historyError = it } },
                                    )
                                } else if (asset.remoteUrl != null) {
                                    DsButton(
                                        text = stringResource(R.string.media_studio_open_temporary_link),
                                        size = DsButtonSize.Small,
                                        onClick = { openMediaUrl(context, asset.remoteUrl) { historyError = it } },
                                    )
                                } else {
                                    Text(stringResource(R.string.media_studio_result_missing),
                                        style = DsType.caption11.withReadingWeight(),
                                        color = colors.labelSecondary)
                                }
                                DsButton(
                                    text = stringResource(R.string.media_studio_delete_record),
                                    size = DsButtonSize.Small,
                                    variant = DsButtonVariant.Ghost,
                                    onClick = { pendingDeleteId = asset.id },
                                )
                            }
                            if (pendingDeleteId == asset.id) {
                                Text(stringResource(R.string.media_studio_confirm_delete),
                                    style = DsType.caption11.withReadingWeight(),
                                    color = colors.labelSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                                    DsButton(text = stringResource(R.string.media_studio_delete_confirm),
                                        size = DsButtonSize.Small, onClick = {
                                            scope.launch {
                                                try {
                                                    viewModel.removeResult(asset.id)
                                                    pendingDeleteId = null
                                                } catch (failure: Exception) {
                                                    historyError = failure.message.orEmpty()
                                                }
                                            }
                                        })
                                    DsButton(text = stringResource(R.string.media_studio_delete_cancel),
                                        size = DsButtonSize.Small, variant = DsButtonVariant.Ghost,
                                        onClick = { pendingDeleteId = null })
                                }
                            }
                        }
                    }
                }
            }
        }
        historyError?.let { Text(it, color = colors.labelPrimary,
            style = DsType.caption11.withReadingWeight()) }
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
