package com.labteto.dshmobile.ui.screens.local

import com.labteto.dshmobile.local.model.belongsTo

import com.shenyan.designsystem.foundation.SyMetric

import com.shenyan.designsystem.components.SyMaterialTier

import com.shenyan.designsystem.components.SyMaterialSurface


import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.labteto.dshmobile.R
import com.labteto.dshmobile.local.model.LocalMediaCreationJob
import com.labteto.dshmobile.local.model.LocalMediaCreationPhase
import com.labteto.dshmobile.local.model.LocalMediaKind
import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaResult
import com.labteto.dshmobile.local.model.LocalMediaRevisionDraft
import com.labteto.dshmobile.local.model.LocalMediaVideoPhase
import com.labteto.dshmobile.local.model.LocalMediaVideoTask
import com.labteto.dshmobile.local.presentation.LocalArtifactUiItem
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SyPopupMenu
import com.labteto.dshmobile.ui.shenyan.SyFullscreenMediaOverlay
import com.labteto.dshmobile.ui.shenyan.SyMenuItem
import com.labteto.dshmobile.ui.shenyan.SyResponsiveSheet
import com.labteto.dshmobile.ui.shenyan.SyButtonSize
import com.labteto.dshmobile.ui.shenyan.SyButtonVariant
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsType
import com.labteto.dshmobile.ui.theme.withReadingWeight
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat-native presentation of persisted media work. The attached result is NOT a text turn,
 * so a fictional persona/model never gets a fabricated speech message in its history.
 */
/**
 * Pending receipts live in the same lazy list slot as the eventual generated result.
 * A process restart replaces SUBMITTING by OUTCOME_UNKNOWN, never by a fake success.
 */
@Composable
internal fun LocalCreationJobMessageRow(
    job: LocalMediaCreationJob,
    viewModel: LocalMediaStudioViewModel,
    inline: Boolean = false,
) {
    val resources = LocalResources.current
    val phase = when (job.phase) {
        LocalMediaCreationPhase.PREPARING -> R.string.media_creation_preparing
        LocalMediaCreationPhase.SAVING -> R.string.media_creation_saving
        LocalMediaCreationPhase.SAVE_FAILED -> R.string.media_creation_save_failed
        LocalMediaCreationPhase.SUBMITTING -> R.string.media_chat_creating_now
        LocalMediaCreationPhase.OUTCOME_UNKNOWN -> R.string.media_chat_creation_uncertain
        LocalMediaCreationPhase.FAILED -> R.string.media_chat_creation_failed
        LocalMediaCreationPhase.SUCCEEDED -> R.string.media_chat_creation_saved
    }
    MediaMessageCard(
        title = resources.getString(phase),
        subtitle = if (inline) "" else resources.getString(
            if (job.sourceMessageId.isNullOrBlank()) R.string.media_user_created else R.string.media_based_on_reply),
        inline = inline,
    ) {
        job.prompt?.let { prompt ->
            Text(prompt, style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        when (job.phase) {
            LocalMediaCreationPhase.PREPARING, LocalMediaCreationPhase.SAVING, LocalMediaCreationPhase.SUBMITTING -> {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
                    trackColor = com.shenyan.designsystem.foundation.LocalSyColors.current.materialL3,
                )
                Text(resources.getString(R.string.media_chat_creating_hint),
                    style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            LocalMediaCreationPhase.OUTCOME_UNKNOWN, LocalMediaCreationPhase.FAILED, LocalMediaCreationPhase.SAVE_FAILED -> {
                Text(job.error ?: resources.getString(R.string.media_chat_creation_uncertain_hint),
                    style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
                Text(resources.getString(R.string.media_chat_creation_safe_retry),
                    style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            LocalMediaCreationPhase.SUCCEEDED -> {
                Text(resources.getString(R.string.media_chat_creation_saved),
                    style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
        }
    }
}

@Composable
internal fun LocalGeneratedMediaResultRow(
    result: LocalMediaResult,
    viewModel: LocalMediaStudioViewModel,
    onInsertText: (String) -> Unit,
    onRevise: (LocalMediaRevisionDraft) -> Unit = {},
    inline: Boolean = false,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val file = remember(result.id, result.fileName) { viewModel.resultFile(result) }
    var error by remember(result.id) { mutableStateOf<String?>(null) }
    var showImageDetail by remember(result.id) { mutableStateOf(false) }
    val label = when (result.kind) {
        LocalMediaKind.IMAGE.name -> resources.getString(R.string.media_chat_image_done)
        LocalMediaKind.SPEECH.name -> resources.getString(R.string.media_chat_speech_done)
        LocalMediaKind.TRANSCRIBE.name -> resources.getString(R.string.media_chat_transcript_done)
        else -> resources.getString(R.string.media_chat_generic_result)
    }
    MediaMessageCard(
        title = if (inline && result.kind == LocalMediaKind.IMAGE.name) "" else label,
        subtitle = if (inline) "" else resources.getString(
            if (result.sourceMessageId.isNullOrBlank()) R.string.media_user_created else R.string.media_based_on_reply),
        inline = inline,
    ) {
        when (result.kind) {
            LocalMediaKind.IMAGE.name -> {
                MediaInlinePicture(
                    file = file, remoteUrl = result.remoteUrl,
                    onOpen = { showImageDetail = true },
                )
                if (showImageDetail) {
                    MediaImageDetailDialog(file, result.remoteUrl, onDismiss = { showImageDetail = false })
                }
            }
            LocalMediaKind.SPEECH.name -> {
                if (file != null) {
                    InlineSpeechPlayer(file)
                    Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                        MediaSaveButton(file, "audio/mpeg")
                        SyButton(text = resources.getString(R.string.media_action_share), size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                            onClick = { shareMediaFile(context, file, "audio/mpeg") { error = it } })
                    }
                } else Text(resources.getString(R.string.media_chat_audio_missing), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            LocalMediaKind.TRANSCRIBE.name -> {
                result.textOutput?.let { text ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, style = DsType.std14.withReadingWeight(),
                            color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                        SyButton(text = resources.getString(R.string.media_chat_insert_text), size = SyButtonSize.Small,
                            onClick = { onInsertText(text) })
                        SyButton(text = resources.getString(R.string.media_chat_copy), size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                            onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.media_chat_transcription), text))
                            })
                    }
                }
            }
        }
        LocalMediaRevisionDraft.from(result)?.let { draft ->
            SyButton(text = if (draft.kind == LocalMediaKind.TRANSCRIBE) resources.getString(R.string.media_recreate_transcribe_select_audio) else resources.getString(R.string.media_recreate_edit_and_generate),
                size = SyButtonSize.Small, variant = SyButtonVariant.Ghost,
                onClick = { onRevise(draft) })
        }
        error?.let { Text(it, style = DsType.caption11, color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary) }
    }
}

/** A downloaded local video remains playable even after its signed remote URL expires. */
internal fun canPlayGeneratedVideo(
    phase: LocalMediaVideoPhase, localFileExists: Boolean, downloadUrl: String?,
): Boolean = phase == LocalMediaVideoPhase.SUCCEEDED &&
    (localFileExists || !downloadUrl.isNullOrBlank())

@Composable
internal fun LocalGeneratedVideoTaskRow(
    task: LocalMediaVideoTask,
    offers: List<LocalMediaOffer>,
    viewModel: LocalMediaStudioViewModel,
    onRevise: (LocalMediaRevisionDraft) -> Unit = {},
    inline: Boolean = false,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A retry may make the local file available without changing its persisted filename.
    val savedVideo = remember(task) { viewModel.videoFile(task) }
    val localVideo = savedVideo?.takeIf(File::isFile)
    val offer = remember(task.offerId, task.routeFingerprint, offers) {
        offers.firstOrNull { task.belongsTo(it) }
    }
    var refreshing by remember(task.id) { mutableStateOf(false) }
    var showingPlayer by remember(task.id) { mutableStateOf(false) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    // Poll the original provider task while this conversation is visible. The runtime
    // never re-submits a billable video request; it only reads its persisted task ID.
    LaunchedEffect(task.id, task.phase, offer?.id) {
        if (offer != null && task.phase in setOf(
                LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING
            )) {
            var waitMillis = 15_000L
            while (true) {
                delay(waitMillis)
                try {
                    viewModel.refreshVideo(offer, task.id)
                    waitMillis = 15_000L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: resources.getString(R.string.media_chat_poll_failure)
                    waitMillis = (waitMillis * 2).coerceAtMost(90_000L)
                }
            }
        }
    }
    val phase = when (task.phase) {
        LocalMediaVideoPhase.SUBMITTING -> resources.getString(R.string.media_chat_submitting)
        LocalMediaVideoPhase.OUTCOME_UNKNOWN -> resources.getString(R.string.media_chat_outcome_unknown)
        LocalMediaVideoPhase.QUEUED -> resources.getString(R.string.media_chat_queued)
        LocalMediaVideoPhase.PROCESSING -> resources.getString(R.string.media_chat_processing)
        LocalMediaVideoPhase.SUCCEEDED -> resources.getString(R.string.media_chat_video_done)
        LocalMediaVideoPhase.FAILED -> resources.getString(R.string.media_chat_generation_failed)
    }
    MediaMessageCard(
        title = phase,
        subtitle = if (inline) "" else resources.getString(
            if (task.sourceMessageId.isNullOrBlank()) R.string.media_user_created else R.string.media_based_on_reply),
        inline = inline,
    ) {
        task.description?.let {
            Text(it, style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        if (canPlayGeneratedVideo(task.phase, savedVideo?.isFile == true, task.downloadUrl)) {
            if (showingPlayer) {
                InlineVideoPlayer(task.downloadUrl.orEmpty(), localVideo, onError = { error = it })
            } else {
                MediaVideoPoster(localVideo, onPlay = { showingPlayer = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
                if (showingPlayer) SyButton(
                    text = resources.getString(R.string.media_chat_collapse_video),
                    size = SyButtonSize.Small,
                    onClick = { showingPlayer = false },
                )
                if (localVideo != null) {
                    MediaSaveButton(localVideo, "video/mp4")
                    SyButton(text = resources.getString(R.string.media_action_share), size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                        onClick = { shareMediaFile(context, localVideo, "video/mp4") { error = it } })
                } else {
                    // A local-only video has no remote open action; keep the nullable URL guarded.
                    task.downloadUrl?.takeIf(String::isNotBlank)?.let { remoteUrl ->
                        SyButton(text = resources.getString(R.string.media_chat_open_video), size = SyButtonSize.Small,
                            variant = SyButtonVariant.Ghost,
                            onClick = { openMediaUrl(context, remoteUrl) { error = it } })
                    }
                }
            }
        } else {
            task.lastError?.let {
                Text(it, style = DsType.caption11.withReadingWeight(),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            }
            if (task.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) {
                SyButton(text = if (refreshing) resources.getString(R.string.media_chat_polling) else resources.getString(R.string.media_chat_refresh_progress),
                    enabled = !refreshing && offer != null,
                    size = SyButtonSize.Small, onClick = {
                        val bound = offer ?: return@SyButton
                        scope.launch {
                            refreshing = true
                            error = null
                            try { viewModel.refreshVideo(bound, task.id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: resources.getString(R.string.media_chat_refresh_failed) }
                            finally { refreshing = false }
                        }
                    })
            } else if (task.phase == LocalMediaVideoPhase.OUTCOME_UNKNOWN) {
                Text(resources.getString(R.string.media_chat_check_remote_task),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary, style = DsType.caption11.withReadingWeight())
            }
            if (offer == null && task.phase in setOf(LocalMediaVideoPhase.QUEUED, LocalMediaVideoPhase.PROCESSING)) {
                Text(resources.getString(R.string.media_chat_account_missing),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary, style = DsType.caption11.withReadingWeight())
            }
        }
        if (task.phase == LocalMediaVideoPhase.SUCCEEDED && localVideo == null) {
            SyButton(text = resources.getString(R.string.media_retry_save), size = SyButtonSize.Small,
                onClick = { scope.launch {
                    try { viewModel.retryVideoSave(task.id); error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                } })
            if (offer != null) SyButton(text = resources.getString(R.string.media_refresh_link), size = SyButtonSize.Small,
                onClick = { scope.launch {
                    try { viewModel.refreshVideo(offer, task.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                } })
        }
        // Create a new task with an explicit confirmation; querying the old task remains read-only.
        if (task.phase in setOf(LocalMediaVideoPhase.SUCCEEDED, LocalMediaVideoPhase.FAILED)) {
            SyButton(text = resources.getString(R.string.media_recreate_edit_and_generate), size = SyButtonSize.Small,
                variant = SyButtonVariant.Ghost,
                onClick = { onRevise(LocalMediaRevisionDraft.from(task)) })
        }
        task.lastError?.takeIf { task.phase == LocalMediaVideoPhase.SUCCEEDED }?.let {
            Text(it, style = DsType.caption11.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        }
        error?.let { Text(it, color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            style = DsType.caption11.withReadingWeight()) }
    }
}

@Composable
private fun MediaMessageCard(
    title: String, subtitle: String, modifier: Modifier = Modifier,
    inline: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    Column(
        modifier = modifier.fillMaxWidth(if (inline) 1f else 0.90f)
            .widthIn(max = if (inline) 640.dp else 470.dp)
            .padding(vertical = if (inline) SyMetric.gapSm else SyMetric.gapMd),
        verticalArrangement = Arrangement.spacedBy(SyMetric.gapSm),
    ) {
        if (title.isNotBlank()) Text(title, style = DsType.small13Strong.withReadingWeight(),
            color = colors.textSecondary)
        if (subtitle.isNotBlank()) Text(subtitle, style = DsType.caption11.withReadingWeight(),
            color = colors.textTertiary)
        content()
    }
}

/** Explicit user-selected device destination; sharing never masquerades as a save. */
/** The save picker is shared by audio's inline button and the photo viewer's overflow. */
@Composable
private fun rememberMediaSaveAction(
    file: File?, mime: String, onResult: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        file?.inputStream()?.use { input -> input.copyTo(output) }
                            ?: error("源文件不可用")
                    } ?: error("无法写入选定位置")
                }
            }
            onResult(saved.isSuccess)
        }
    }
    return { file?.let { destination.launch(it.name) } }
}

@Composable
private fun MediaSaveButton(file: File, mime: String) {
    val resources = LocalResources.current
    var status by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    val save = rememberMediaSaveAction(file, mime) { success ->
        status = resources.getString(if (success) R.string.media_action_saved else R.string.media_action_save_failed)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SyButton(text = resources.getString(R.string.media_action_save), size = SyButtonSize.Small,
            onClick = { status = null; save() })
        status?.let { Text(it, style = DsType.caption11.withReadingWeight()) }
    }
}

@Composable
internal fun MediaInlinePicture(
    file: File?, remoteUrl: String?, onOpen: (() -> Unit)? = null,
) {
    val resources = LocalResources.current
    var bitmap by remember(file?.absolutePath, remoteUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(file?.absolutePath, remoteUrl) { mutableStateOf(true) }
    LaunchedEffect(file?.absolutePath, remoteUrl) {
        loading = true
        bitmap = withContext(Dispatchers.IO) { decodeMediaPreview(file, remoteUrl) }
        loading = false
    }
    val image = bitmap
    val ratio = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
        ?.coerceIn(0.62f, 1.9f) ?: 1.2f
    val surface = Modifier.fillMaxWidth().aspectRatio(ratio)
        .clip(DsShapes.block)
        .background(com.shenyan.designsystem.foundation.LocalSyColors.current.materialL3)
        .border(1.dp, com.shenyan.designsystem.foundation.LocalSyColors.current.border, DsShapes.block)
    val modifier = if (onOpen != null) surface.clickable(onClick = onOpen) else surface
    if (image != null) {
        Image(bitmap = image, contentDescription = resources.getString(R.string.media_chat_generated_image),
            contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        androidx.compose.foundation.layout.Box(modifier,
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                resources.getString(if (loading) R.string.media_chat_preview_pending
                    else R.string.media_chat_preview_unavailable),
                style = DsType.caption11.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
            )
        }
    }
}

private fun imageMime(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic", "heif" -> "image/heic"
    else -> "image/jpeg"
}

private fun decodeMediaPreview(file: File?, remoteUrl: String?, maxEdge: Int = 1200): ImageBitmap? = runCatching {
    val bytes = when {
        file?.isFile == true -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
            return@runCatching BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        }
        remoteUrl != null -> {
            val uri = URI(remoteUrl)
            require(uri.scheme == "https" && uri.host != null && uri.port == -1)
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                require(connection.responseCode == 200 && connection.contentType?.startsWith("image/") == true)
                require(connection.contentLengthLong in -1..12_000_000L)
                val output = ByteArrayOutputStream()
                connection.inputStream.use { stream ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val size = stream.read(chunk)
                        if (size < 0) break
                        require(output.size() + size <= 12_000_000)
                        output.write(chunk, 0, size)
                    }
                }
                output.toByteArray()
            } finally { connection.disconnect() }
        }
        else -> return@runCatching null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** Fullscreen, wallpaper-matched image viewer. No black slab or permanent action strip. */
@Composable
internal fun MediaImageDetailDialog(
    file: File?, remoteUrl: String?, onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var detail by remember(file?.absolutePath, remoteUrl) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(file?.absolutePath, remoteUrl) { mutableStateOf(true) }
    var actionsOpen by remember(file?.absolutePath, remoteUrl) { mutableStateOf(false) }
    var zoom by remember(file?.absolutePath, remoteUrl) { mutableFloatStateOf(1f) }
    var offsetX by remember(file?.absolutePath, remoteUrl) { mutableFloatStateOf(0f) }
    var offsetY by remember(file?.absolutePath, remoteUrl) { mutableFloatStateOf(0f) }
    LaunchedEffect(file?.absolutePath, remoteUrl) {
        loading = true
        detail = withContext(Dispatchers.IO) { decodeMediaPreview(file, remoteUrl, maxEdge = 4096) }
        loading = false
    }
    val gesture = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (zoom * zoomChange).coerceIn(1f, 5f)
        zoom = next
        offsetX = if (next <= 1f) 0f else (offsetX + panChange.x).coerceIn(-1100f, 1100f)
        offsetY = if (next <= 1f) 0f else (offsetY + panChange.y).coerceIn(-1100f, 1100f)
    }
    val localFile = file?.takeIf(File::isFile)
    val save = rememberMediaSaveAction(localFile, localFile?.let(::imageMime) ?: "image/jpeg") { success ->
        Toast.makeText(context,
            resources.getString(if (success) R.string.media_action_saved else R.string.media_action_save_failed),
            Toast.LENGTH_SHORT).show()
    }
    val actions = buildList {
        if (localFile != null) {
            add(SyMenuItem(
                text = resources.getString(R.string.media_action_save),
                icon = FeatherIcons.Download,
                onClick = save,
            ))
            add(SyMenuItem(
                text = resources.getString(R.string.media_action_share),
                icon = FeatherIcons.ArrowUp,
                onClick = { shareMediaFile(context, localFile, imageMime(localFile)) { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                } },
            ))
        } else remoteUrl?.takeIf(String::isNotBlank)?.let { url ->
            add(SyMenuItem(
                text = resources.getString(R.string.media_chat_share_url),
                icon = FeatherIcons.ArrowUp,
                onClick = { shareMediaUrl(context, url) { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                } },
            ))
        }
    }
    SyFullscreenMediaOverlay(
        onDismiss = onDismiss,
        dismissOnBackgroundTap = zoom <= 1f,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .clipToBounds()
                .transformable(state = gesture),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = detail
            if (bitmap != null) {
                // Real image bounds, rather than the entire viewport, own the tap target.
                // Taps on the image are non-destructive; surrounding space dismisses.
                val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                val displayWidth = minOf(maxWidth, maxHeight * ratio)
                val displayHeight = displayWidth / ratio
                Image(
                    bitmap = bitmap,
                    contentDescription = resources.getString(R.string.media_chat_generated_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(displayWidth).height(displayHeight)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .testTag("mediaImageContent")
                        .clickable(onClick = {}),
                )
            } else {
                Text(
                    resources.getString(if (loading) R.string.media_chat_preview_pending
                        else R.string.media_chat_preview_unavailable),
                    color = colors.textSecondary,
                    style = DsType.caption11.withReadingWeight(),
                )
            }
        }
        if (actions.isNotEmpty()) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            ) {
                IconButton(
                    onClick = { actionsOpen = true },
                    modifier = Modifier.testTag("mediaImageActions"),
                ) {
                    Icon(FeatherIcons.MoreVertical,
                        contentDescription = resources.getString(R.string.fluid_message_actions),
                        tint = colors.text)
                }
                SyPopupMenu(
                    expanded = actionsOpen,
                    onDismiss = { actionsOpen = false },
                    items = actions,
                )
            }
        }
    }
}

/** The first saved video frame replaces the empty playback slab; preparation is off the UI thread. */
@Composable
private fun MediaVideoPoster(file: File?, onPlay: () -> Unit) {
    val resources = LocalResources.current
    val colors = com.shenyan.designsystem.foundation.LocalSyColors.current
    var poster by remember(file?.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file?.absolutePath) {
        poster = withContext(Dispatchers.IO) {
            if (file?.isFile != true) null else runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getScaledFrameAtTime(0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 576)?.asImageBitmap()
                } finally { retriever.release() }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(DsShapes.block).background(colors.materialL3)
            .clickable(onClick = onPlay),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        poster?.let {
            Image(bitmap = it, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        SyButton(text = resources.getString(R.string.media_chat_play_video), onClick = onPlay)
    }
}

@Composable
internal fun InlineSpeechPlayer(file: File) {
    val resources = LocalResources.current
    var playing by remember(file.absolutePath) { mutableStateOf(false) }
    var preparing by remember(file.absolutePath) { mutableStateOf(false) }
    var error by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var player by remember(file.absolutePath) { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember(file.absolutePath) { mutableStateOf(0) }
    var positionMs by remember(file.absolutePath) { mutableStateOf(0) }
    DisposableEffect(file.absolutePath) {
        onDispose { player?.release(); player = null }
    }
    LaunchedEffect(file.absolutePath) {
        durationMs = withContext(Dispatchers.IO) {
            runCatching {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(file.absolutePath)
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                } finally { metadata.release() }
            }.getOrDefault(0)
        }
    }
    LaunchedEffect(playing, file.absolutePath) {
        while (playing) {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(300)
        }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
        SyButton(
            text = if (playing) resources.getString(R.string.media_chat_pause_audio)
                else resources.getString(R.string.media_chat_play_audio),
            size = SyButtonSize.Small,
            enabled = !preparing,
            onClick = {
                if (preparing) return@SyButton
                error = null
                runCatching {
                    val current = player
                    if (current == null) {
                        val created = MediaPlayer()
                        created.setOnCompletionListener {
                            playing = false
                            positionMs = durationMs
                        }
                        created.setOnPreparedListener { ready ->
                            preparing = false
                            durationMs = ready.duration.coerceAtLeast(0)
                            runCatching { ready.start() }
                                .onSuccess { playing = true }
                                .onFailure { failure ->
                                    playing = false
                                    error = failure.message ?: resources.getString(R.string.media_chat_audio_failed)
                                    runCatching { ready.release() }
                                    player = null
                                }
                        }
                        created.setOnErrorListener { _, _, _ ->
                            preparing = false
                            playing = false
                            durationMs = 0
                            positionMs = 0
                            error = resources.getString(R.string.media_chat_audio_failed)
                            player = null
                            runCatching { created.release() }
                            true
                        }
                        // Own the player before I/O so failed setDataSource cannot leak it.
                        player = created
                        created.setDataSource(file.absolutePath)
                        preparing = true
                        created.prepareAsync()
                    } else if (playing) {
                        current.pause()
                        playing = false
                    } else {
                        if (positionMs >= durationMs && durationMs > 0) current.seekTo(0)
                        current.start()
                        playing = true
                    }
                }.onFailure { failure ->
                    preparing = false
                    playing = false
                    error = failure.message ?: resources.getString(R.string.media_chat_audio_failed)
                    runCatching { player?.release() }
                    player = null
                }
            },
        )
        Text(
            "${formatMediaDuration(positionMs)} / ${formatMediaDuration(durationMs)}",
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary,
        )
    }
    androidx.compose.material3.Slider(
        value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        onValueChange = { fraction ->
            val target = (durationMs * fraction).toInt().coerceIn(0, durationMs)
            runCatching { player?.seekTo(target) }.onSuccess { positionMs = target }
        },
        enabled = durationMs > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, style = DsType.caption11.withReadingWeight(),
        color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary) }
}

private fun formatMediaDuration(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun InlineVideoPlayer(url: String?, localFile: File?, onError: (String) -> Unit) {
    val resources = LocalResources.current
    val source = localFile?.takeIf { it.isFile }?.let(Uri::fromFile)
        ?: url?.takeIf(String::isNotBlank)?.let(Uri::parse)
    if (source == null) {
        Text(resources.getString(R.string.media_chat_video_failed),
            style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        return
    }
    var player by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) {
        onDispose { player?.stopPlayback(); player = null }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        factory = { viewContext ->
            VideoView(viewContext).apply {
                val controls = MediaController(viewContext)
                controls.setAnchorView(this)
                setMediaController(controls)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> onError(resources.getString(R.string.media_chat_video_failed)); true }
                player = this
            }
        },
        update = { view ->
            // AndroidView retains its native view across recompositions. Update the media source
            // explicitly instead of leaving an expired URL in a previously created VideoView.
            val sourceKey = source.toString()
            if (view.tag != sourceKey) {
                view.stopPlayback()
                view.tag = sourceKey
                view.setVideoURI(source)
            }
        },
    )
}

private fun shareMediaUrl(context: Context, url: String, onError: (String) -> Unit) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.media_chat_share_image_link)))
    }.onFailure { onError(it.message.orEmpty()) }
}

/** Inline Work delivery: preview first, workspace/file browser is only an optional detail view. */
@Composable
internal fun LocalWorkArtifactMessageRow(
    artifact: LocalArtifactUiItem,
    workspacePath: String,
    actions: LocalWorkFeatureUiActions?,
    onOpen: () -> Unit,
    sessionId: String? = null,
    inline: Boolean = false,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inspected by remember(artifact.reference, artifact.fileObservation) { mutableStateOf<LocalArtifactUiItem?>(null) }
    var inspecting by remember(artifact.reference, artifact.fileObservation) { mutableStateOf(false) }
    val currentVersion = inspected?.versionNow ?: artifact.versionNow
    val file = remember(workspacePath, artifact.reference, artifact.fileObservation, artifact.versionNow) {
        runCatching {
            require(workspacePath.isNotBlank())
            val root = File(workspacePath).canonicalFile
            File(root, artifact.reference).canonicalFile.takeIf {
                it.toPath().startsWith(root.toPath()) && it.isFile
            }
        }.getOrNull()
    }
    var preview by remember(artifact.reference, artifact.fileObservation, artifact.versionNow) {
        mutableStateOf<LocalWorkspaceFilePreview?>(null)
    }
    var expanded by remember(artifact.reference) { mutableStateOf(false) }
    var error by remember(artifact.reference) { mutableStateOf<String?>(null) }
    LaunchedEffect(artifact.reference, artifact.fileObservation, artifact.versionNow, artifact.currentlyAvailable, actions) {
        preview = null
        if (artifact.category == "file" && artifact.currentlyAvailable != false && actions != null) {
            try {
                preview = withContext(Dispatchers.IO) {
                    actions.previewWorkspaceFile(artifact.reference)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: resources.getString(R.string.media_chat_preview_failed)
            }
        }
    }
    val isImage = artifact.category == "file" &&
        artifact.reference.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")
    val name = artifact.reference.substringAfterLast('/').ifBlank { artifact.reference }
    MediaMessageCard(
        title = if (artifact.category == "file") resources.getString(R.string.media_chat_work_file, name) else resources.getString(R.string.media_chat_work_result),
        subtitle = if (artifact.currentlyAvailable == false) resources.getString(R.string.media_chat_work_file_missing) else resources.getString(R.string.media_chat_work_output_here),
        modifier = if (artifact.category == "file" && artifact.currentlyAvailable != false) {
            Modifier.localArtifactSharedBounds(workspacePath, artifact.reference)
        } else Modifier,
    ) {
        if (isImage && file != null) {
            androidx.compose.runtime.key(artifact.fileObservation, currentVersion) {
                MediaInlinePicture(file, null)
            }
        } else if (!preview?.text.isNullOrBlank()) {
            val text = preview!!.text!!
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(if (expanded) text.take(16_000) else text.take(2500),
                    style = DsType.small13.withReadingWeight().copy(fontFamily = DsType.contentFont),
                    color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
            }
            if (text.length > 2500) {
                SyButton(
                    text = if (expanded) resources.getString(R.string.media_chat_collapse_preview) else resources.getString(R.string.media_chat_expand_preview),
                    size = SyButtonSize.Small,
                    variant = SyButtonVariant.Ghost,
                    onClick = { expanded = !expanded },
                )
            }
        } else if (artifact.category == "file") {
            Text(if (file != null) resources.getString(R.string.media_chat_work_file_ready)
                else resources.getString(R.string.media_chat_work_no_preview),
                style = DsType.caption11.withReadingWeight(), color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        } else {
            Text(artifact.reference.take(160),
                style = DsType.small13.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.text)
        }
        if (artifact.versionAtCreation != null && currentVersion != null &&
            artifact.versionAtCreation != currentVersion) {
            Text(resources.getString(R.string.media_chat_work_file_changed), style = DsType.caption11.withReadingWeight(),
                color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
        }
        if (artifact.category == "file" && actions != null && sessionId != null && artifact.currentlyAvailable != false) {
            if (currentVersion == null) Text(resources.getString(R.string.work_evidence_not_checked),
                style = DsType.caption11, color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary)
            SyButton(resources.getString(R.string.work_evidence_current_version), enabled = !inspecting,
                variant = SyButtonVariant.Ghost, size = SyButtonSize.Small, onClick = {
                    inspecting = true
                    scope.launch {
                        try {
                            inspected = withContext(Dispatchers.IO) { actions.inspectArtifact(sessionId, artifact) }
                            if (inspected == null) error = resources.getString(R.string.work_evidence_link_failed)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = resources.getString(R.string.work_evidence_failed) }
                        finally { inspecting = false }
                    }
                })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SyMetric.gapMd)) {
            if (artifact.category == "file") {
                SyButton(resources.getString(R.string.media_chat_view_entire_file), onClick = onOpen, size = SyButtonSize.Small)
                if (file != null) {
                    SyButton(resources.getString(R.string.media_chat_share_save), size = SyButtonSize.Small,
                        variant = SyButtonVariant.Ghost,
                        onClick = {
                            shareMediaFile(context, file, localArtifactMime(file)) { error = it }
                        })
                }
            } else if (artifact.reference.startsWith("https://")) {
                SyButton(resources.getString(R.string.media_chat_open_result), size = SyButtonSize.Small,
                    onClick = { openMediaUrl(context, artifact.reference) { error = it } })
            }
        }
        error?.let { Text(it, style = DsType.caption11.withReadingWeight(),
            color = com.shenyan.designsystem.foundation.LocalSyColors.current.textSecondary) }
    }
}

private fun localArtifactMime(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "md", "txt", "log", "kt", "py", "js", "ts", "json" -> "text/plain"
    "pdf" -> "application/pdf"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
