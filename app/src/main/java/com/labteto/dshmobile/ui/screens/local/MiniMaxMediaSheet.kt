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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalModelProfile
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

/** Reuses the existing composer '+' sheet. All API calls are explicit user actions. */
@Composable
internal fun MiniMaxMediaSheetContent(
    profile: LocalModelProfile,
    videoTaskId: String,
    onVideoTaskIdChange: (String) -> Unit,
    onInsertTranscript: (String) -> Unit,
    viewModel: MiniMaxMediaViewModel = hiltViewModel(),
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentProfileId by rememberUpdatedState(profile.id)
    var mode by rememberSaveable(profile.id) { mutableStateOf("image") }
    var prompt by rememberSaveable(profile.id) { mutableStateOf("") }
    var ratio by rememberSaveable(profile.id) { mutableStateOf("1:1") }
    var voiceId by rememberSaveable(profile.id) { mutableStateOf("English_expressive_narrator") }
    var busy by remember(profile.id) { mutableStateOf(false) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    var imageLink by remember(profile.id) { mutableStateOf<String?>(null) }
    var videoLink by remember(profile.id) { mutableStateOf<String?>(null) }
    var videoState by remember(profile.id) { mutableStateOf<String?>(null) }
    var transcript by remember(profile.id) { mutableStateOf<String?>(null) }
    var audioFile by remember(profile.id) { mutableStateOf<File?>(null) }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        val submittedProfileId = profile.id
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (submittedProfileId == currentProfileId) {
                    error = failure.message.orEmpty()
                }
            } finally {
                if (submittedProfileId == currentProfileId) busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) run {
            val answer = viewModel.transcribe(profile, selected)
            if (currentProfileId == profile.id) transcript = answer
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(DsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        Text(stringResource(R.string.minimax_media_notice),
            color = colors.labelSecondary,
            style = DsType.caption11.withReadingWeight())
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            listOf(
                "image" to R.string.minimax_media_image,
                "video" to R.string.minimax_media_video,
                "speech" to R.string.minimax_media_speech,
                "asr" to R.string.minimax_media_asr,
            ).forEach { (id, label) ->
                DsButton(
                    text = stringResource(label),
                    onClick = { if (!busy) { mode = id; error = null } },
                    variant = if (id == mode) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                    enabled = !busy,
                )
            }
        }

        if (mode != "asr") {
            DsTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(
                    if (mode == "speech") R.string.minimax_media_speech_input else R.string.minimax_media_prompt
                )) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }

        when (mode) {
            "image" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    listOf("1:1", "16:9", "9:16").forEach { option ->
                        DsButton(text = option, enabled = !busy, size = DsButtonSize.Small,
                            variant = if (ratio == option) DsButtonVariant.Primary else DsButtonVariant.Ghost,
                            onClick = { ratio = option })
                    }
                }
                DsButton(text = stringResource(R.string.minimax_media_create_image),
                    enabled = !busy && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { run { imageLink = viewModel.image(profile, prompt, ratio) } })
                imageLink?.let { url ->
                    DsButton(text = stringResource(R.string.minimax_media_open_image),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openProviderLink(context, url) { error = it } })
                }
            }
            "video" -> {
                DsButton(text = stringResource(R.string.minimax_media_start_video),
                    enabled = !busy && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { run {
                        val task = viewModel.startVideo(profile, prompt)
                        onVideoTaskIdChange(task)
                        videoLink = null
                        videoState = "Queueing"
                    } })
                if (videoTaskId.isNotBlank()) {
                    Text(stringResource(R.string.minimax_media_task_id, videoTaskId),
                        color = colors.labelSecondary, style = DsType.caption11.withReadingWeight())
                    DsButton(text = stringResource(R.string.minimax_media_check_video),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { run {
                            val result = viewModel.videoStatus(profile, videoTaskId)
                            videoState = result.state
                            videoLink = result.downloadUrl
                        } })
                    Text(stringResource(when (videoState) {
                        "Success" -> R.string.minimax_media_video_success
                        "Failed" -> R.string.minimax_media_video_failed
                        "Processing" -> R.string.minimax_media_video_processing
                        else -> R.string.minimax_media_video_queued
                    }), color = colors.labelSecondary)
                }
                videoLink?.let { url ->
                    DsButton(text = stringResource(R.string.minimax_media_open_video),
                        onClick = { openProviderLink(context, url) { error = it } },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            "speech" -> {
                DsTextField(
                    value = voiceId,
                    onValueChange = { voiceId = it },
                    label = { Text(stringResource(R.string.minimax_media_voice_id)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                DsButton(text = stringResource(R.string.minimax_media_create_speech),
                    enabled = !busy && prompt.isNotBlank() && voiceId.isNotBlank(),
                    onClick = { run { audioFile = viewModel.speech(profile, prompt, voiceId) } },
                    modifier = Modifier.fillMaxWidth())
                audioFile?.let { file ->
                    DsButton(text = stringResource(R.string.minimax_media_share_speech),
                        onClick = { shareAudio(context, file) { error = it } },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            "asr" -> {
                DsButton(text = stringResource(R.string.minimax_media_pick_audio),
                    enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { picker.launch(arrayOf("audio/*")) })
                transcript?.let { text ->
                    Text(text, color = colors.labelPrimary,
                        style = DsType.std14.withReadingWeight())
                    Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        DsButton(text = stringResource(R.string.minimax_media_insert_text),
                            onClick = { onInsertTranscript(text) }, size = DsButtonSize.Small)
                        DsButton(text = stringResource(R.string.minimax_media_copy_text),
                            onClick = { copyText(context, text) }, size = DsButtonSize.Small,
                            variant = DsButtonVariant.Ghost)
                    }
                }
            }
        }
        if (busy) {
            Text(stringResource(R.string.minimax_media_running),
                color = colors.labelSecondary, style = DsType.caption11.withReadingWeight())
        }
        error?.let { Text(stringResource(R.string.minimax_media_error, it),
            color = colors.labelPrimary, style = DsType.caption11.withReadingWeight()) }
    }
}

private fun openProviderLink(context: Context, url: String, onError: (String) -> Unit) {
    if (!url.startsWith("https://")) {
        onError(context.getString(R.string.minimax_media_invalid_url))
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { onError(it.message.orEmpty()) }
}

private fun shareAudio(context: Context, file: File, onError: (String) -> Unit) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.minimax_media_share_speech)))
    }.onFailure { onError(it.message.orEmpty()) }
}

private fun copyText(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("transcription", text))
}
