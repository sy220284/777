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
    videoTasks: Map<String, String>,
    onVideoTaskChange: (String, String) -> Unit,
    onInsertTranscript: (String) -> Unit,
    viewModel: LocalMediaStudioViewModel = hiltViewModel(),
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember(profiles) { LocalMediaCatalog.offers(profiles) }
    var mode by rememberSaveable { mutableStateOf(LocalMediaKind.IMAGE.name) }
    val kind = LocalMediaKind.valueOf(mode)
    val available = models.filter { it.kind == kind }
    var chosenId by rememberSaveable(mode) { mutableStateOf("") }
    val selected = available.firstOrNull { it.id == chosenId }
        ?: available.firstOrNull { it.profile.id == preferredProfileId }
        ?: available.firstOrNull()
    val activeOfferId by rememberUpdatedState(selected?.id)
    var showModelChoices by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var ratio by rememberSaveable { mutableStateOf("1:1") }
    var voiceId by rememberSaveable(selected?.id) {
        mutableStateOf(if (selected?.provider == LocalMediaProvider.OPENAI) "alloy" else "male-qn-qingse")
    }
    var busy by remember(selected?.id) { mutableStateOf(false) }
    var error by remember(selected?.id) { mutableStateOf<String?>(null) }
    var image by remember(selected?.id) { mutableStateOf<LocalMediaImage?>(null) }
    var videoLink by remember(selected?.id) { mutableStateOf<String?>(null) }
    var videoState by remember(selected?.id) { mutableStateOf<String?>(null) }
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
                val answer = viewModel.transcribe(chosen, uri)
                if (chosen.id == activeOfferId) transcript = answer
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(stringResource(R.string.media_studio_notice), color = colors.labelSecondary,
            style = DsType.caption11.withReadingWeight())
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            listOf(
                LocalMediaKind.IMAGE to R.string.minimax_media_image,
                LocalMediaKind.VIDEO to R.string.minimax_media_video,
                LocalMediaKind.SPEECH to R.string.minimax_media_speech,
                LocalMediaKind.TRANSCRIBE to R.string.minimax_media_asr,
            ).forEach { (kindOption, title) ->
                DsButton(
                    text = stringResource(title),
                    onClick = { if (!busy) { mode = kindOption.name; showModelChoices = false } },
                    variant = if (kindOption.name == mode) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !busy,
                )
            }
        }

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
                            val result = viewModel.image(selected, prompt, ratio)
                            if (selected.id == activeOfferId) image = result
                        } })
                    image?.url?.let { url ->
                        DsButton(text = stringResource(R.string.minimax_media_open_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openMediaUrl(context, url) { error = it } })
                    }
                    image?.file?.let { file ->
                        DsButton(text = stringResource(R.string.media_studio_view_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewMediaFile(context, file, "image/png") { error = it } })
                        DsButton(text = stringResource(R.string.media_studio_share_image),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { shareMediaFile(context, file, "image/png") { error = it } })
                    }
                }
                LocalMediaKind.VIDEO -> {
                    DsButton(text = stringResource(R.string.minimax_media_start_video),
                        enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                        onClick = { submit(selected) {
                            val task = viewModel.startVideo(selected, prompt)
                            // The external creation may already be billable; retain its returned ID even
                            // if the user selected a different creative model while submission was pending.
                            onVideoTaskChange(selected.id, task)
                            if (selected.id == activeOfferId) {
                                videoState = "Queueing"
                                videoLink = null
                            }
                        } })
                    val videoTaskId = videoTasks[selected.id].orEmpty()
                    if (videoTaskId.isNotBlank()) {
                        Text(stringResource(R.string.minimax_media_task_id, videoTaskId),
                            color = colors.labelSecondary, style = DsType.caption11.withReadingWeight())
                        DsButton(text = stringResource(R.string.minimax_media_check_video),
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            onClick = { submit(selected) {
                                val result = viewModel.videoStatus(selected, videoTaskId)
                                if (selected.id == activeOfferId) {
                                    videoState = result.state
                                    videoLink = result.downloadUrl
                                }
                            } })
                        Text(stringResource(when (videoState) {
                            "Success" -> R.string.minimax_media_video_success
                            "Failed" -> R.string.minimax_media_video_failed
                            "Processing" -> R.string.minimax_media_video_processing
                            else -> R.string.minimax_media_video_queued
                        }), color = colors.labelSecondary)
                    }
                    videoLink?.let { link ->
                        DsButton(text = stringResource(R.string.minimax_media_open_video),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openMediaUrl(context, link) { error = it } })
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
                            val file = viewModel.speech(selected, prompt, voiceId)
                            if (selected.id == activeOfferId) audioFile = file
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
    }
}

private fun openMediaUrl(context: Context, url: String, onError: (String) -> Unit) {
    if (!url.startsWith("https://")) {
        onError(context.getString(R.string.minimax_media_invalid_url))
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { onError(it.message.orEmpty()) }
}

private fun viewMediaFile(context: Context, file: File, mime: String, onError: (String) -> Unit) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure { onError(it.message.orEmpty()) }
}

private fun shareMediaFile(context: Context, file: File, mime: String, onError: (String) -> Unit) {
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
